package likelion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import likelion.controller.dto.AnalysisRequest;
import likelion.controller.dto.AnalysisResponse;
import likelion.domain.entity.Restaurant;
import likelion.domain.entity.Review;
import likelion.repository.RestaurantRepository;
import likelion.repository.ReviewRepository;
import likelion.service.distance.DistanceCalc;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final RestaurantRepository restaurantRepository;
    private final ReviewRepository reviewRepository;
    private final AiChatService aiChatService;
    private final ObjectMapper objectMapper;

    // 위치 점수 계산 결과
    private record LocationScoreFactors(
            int score,
            double distanceToMainGate,
            int floor,
            long competitorCount,
            List<String> competitorNames, // "가게명(거리m)" 형태
            String reason
    ) {}

    public AnalysisResponse analyze(AnalysisRequest request) {
        // addr: "위도, 경도"
        String[] locationParts = request.addr().split(",");
        double latitude = Double.parseDouble(locationParts[0].trim());
        double longitude = Double.parseDouble(locationParts[1].trim());

        // 전체 식당 로딩
        List<Restaurant> all = restaurantRepository.findAll();

        // 좌표 없는 데이터 제거 & 반경 내 필터
        double radiusM = 50; // 일단 반경 50m
        List<Restaurant> withCoords = all.stream()
                .filter(r -> r.getLatitude() != null && r.getLongitude() != null)
                .filter(r -> !(Objects.equals(r.getLatitude(), 0.0) && Objects.equals(r.getLongitude(), 0.0)))
                .toList();

        List<Restaurant> withinRadius = withCoords.stream()
                .filter(r -> DistanceCalc.calculateDistance(latitude, longitude, r.getLatitude(), r.getLongitude()) <= radiusM)
                .collect(Collectors.toList());

        // 업종(문자열) 기반 동종업계 1차 필터 (아래에 카테고라-json 키워드 매핑 코드 있습니다), (동종업계는 위치/접근성 및 메뉴 가격 적합도에 사용할 예정)
        String targetCategory = Optional.ofNullable(request.category()).orElse("").trim();
        List<String> categoryKeywords = expandCategoryKeywords(targetCategory);
        List<Restaurant> sameCategory = withCoords.stream()
                .filter(r -> {
                    String c = Optional.ofNullable(r.getCategory()).orElse("").toLowerCase();
                    return categoryKeywords.stream().anyMatch(c::contains);
                })
                .collect(Collectors.toList());

        // 교집합: 반경 안 + 동종업계(룰 기반)
        Set<String> radiusKeys = withinRadius.stream().map(this::keyOf).collect(Collectors.toSet());
        List<Restaurant> competitorsInRadius = sameCategory.stream()
                .filter(r -> radiusKeys.contains(keyOf(r)))
                .collect(Collectors.toList());

        try {
            //  위치 점수 계산 (최종 = 반경 안 + 동종업계 교집합 기준)
            LocationScoreFactors locationFactors =
                    calculateLocationScore(request, latitude, longitude, competitorsInRadius);

            // 점수 배열 구성 (접근성을 제외한 나머지는 임시)
            List<AnalysisResponse.ScoreInfo> scores = new ArrayList<>();
            scores.add(new AnalysisResponse.ScoreInfo("접근성", locationFactors.score(), null, locationFactors.reason()));
            scores.add(calculateBudgetSuitabilityScore(request));
            scores.add(calculateMenuSuitabilityScore(request));

            // 리뷰 로딩(반경 내 식당 기준_추후 반경 내 + 동종업계로 개선하여 더 관련있는 리뷰만 나오게 수정할 예정입니다)
            List<Review> nearbyReviews = new ArrayList<>();
            if (!withinRadius.isEmpty()) {
                List<Long> ids = withinRadius.stream()
                        .map(Restaurant::getKakaoPlaceId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                if (!ids.isEmpty()) {
                    nearbyReviews = reviewRepository.findAllByRestaurantKakaoPlaceIdInOrderByIdDesc(ids);
                }
            }

            // 프롬프트 생성
            String finalPrompt = createSimpleFinalReportPrompt(request, scores, nearbyReviews);
            String aiFinalResponseJson = aiChatService.getAnalysisResponseFromAI(finalPrompt);
            String cleanJson = aiFinalResponseJson.replace("```json", "").replace("```", "").trim();
            return objectMapper.readValue(cleanJson, AnalysisResponse.class);

        } catch (IOException e) {
            e.printStackTrace();
            return new AnalysisResponse(List.of(), null, List.of(), null);
        }
    }

    // "위치/접근성" 계산 로직
    private LocationScoreFactors calculateLocationScore(AnalysisRequest request,
                                                        double latitude, double longitude,
                                                        List<Restaurant> competitorsInRadius) {
        int base = 90;
        int score = base;

        // 정문 좌표
        final double ERICA_MAIN_GATE_LAT = 37.300097500612374;
        final double ERICA_MAIN_GATE_LON = 126.83779165311796;

        // 정문부터 30m까진 감점 없고 초과부터는 10m당 3점 감점
        double distance = DistanceCalc.calculateDistance(latitude, longitude, ERICA_MAIN_GATE_LAT, ERICA_MAIN_GATE_LON);
        int distancePenalty = (distance > 30)
                ? (int) Math.ceil((distance - 30) / 10.0) * 3 : 0;
        score -= distancePenalty;

        // 1층은 감점 없고 2층부터 7점씩 감점, 지하는 층당 10점 감점
        int floor = request.height() != null ? request.height() : 1;
        int floorPenalty;
        if (floor < 0) floorPenalty = Math.abs(floor) * 10;
        else if (floor >= 2) floorPenalty = (floor - 1) * 7;
        else floorPenalty = 0;
        score -= floorPenalty;

        long competitorCount = competitorsInRadius.size();

        // 경쟁사 하나당 3점 감점
        int competitorPenalty = competitorCount > 1 ? (int) ((competitorCount) * 3) : 0;
        score -= competitorPenalty;

        score = Math.max(0, Math.min(100, score));

        // 이름+거리로 정렬하여 5개만 표기(결과에 5개 넘어가는 것은 그 외 n개라고 나오게함)
        DecimalFormat df = new DecimalFormat("#0");
        List<String> competitorNamesWithDist = competitorsInRadius.stream()
                .map(r -> {
                    double d = DistanceCalc.calculateDistance(latitude, longitude, r.getLatitude(), r.getLongitude());
                    return new AbstractMap.SimpleEntry<>(r, d);
                })
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .limit(5)
                .map(e -> String.format("%s(%sm)", safeName(e.getKey()), df.format(e.getValue())))
                .collect(Collectors.toList());

        // 위치/접근성 점수 summary
        String summary = generateLocationReason(distance, floor, competitorCount, competitorNamesWithDist);

        // 점수 산식
        String breakdown = String.format(
                "기본점수: %d%n" +
                        "거리 감점: %s (거리 %.0fm, 30m 초과 10m당 −3)%n" +
                        "층수 감점: %s (층수 %d)%n" +
                        "경쟁사 감점: %s (동종업계 %d곳)%n" +
                        "최종점수: %d",
                base,
                sign(-distancePenalty), distance,
                sign(-floorPenalty), floor,
                sign(-competitorPenalty), competitorCount,
                score
        );

        String reason = summary + "\n\n— 점수 산식 —\n" + breakdown;
        return new LocationScoreFactors(score, distance, floor, competitorCount, competitorNamesWithDist, reason);
    }

    // 부호 붙여주는 헬퍼
    private static String sign(int v) {
        return (v >= 0 ? "+" : "") + v;
    }

    // ====== 사용자 설명에 보낼 멘트(위치 점수 관련) ======
    private String generateLocationReason(double distance, int floor, long competitorCount, List<String> competitorNamesWithDist) {
        StringBuilder sb = new StringBuilder();
        if (distance <= 30) sb.append(String.format("정문과 매우 가까워(약 %.0fm) 접근성이 우수합니다. ", distance));
        else if (distance <= 70) sb.append(String.format("정문에서 도보 접근이 무난한 거리(약 %.0fm)입니다. ", distance));
        else sb.append(String.format("정문과 거리가 다소 있어(약 %.0fm) 유동인구 유입이 제한적일 수 있습니다. ", distance));

        if (floor < 0) sb.append(String.format("지하 %d층으로 간판 노출과 접근성이 제한적입니다. ", Math.abs(floor)));
        else if (floor == 1) sb.append("1층이라 고객 노출에 가장 유리합니다. ");
        else if (floor == 2) sb.append("2층이라 1층 대비 고객 유입에 다소 불리할 수 있습니다. ");
        else sb.append(String.format("%d층이라 노출이 적어 고객 유입에 불리할 수 있습니다. ", floor));

        if (competitorCount == 0) {
            sb.append("주변에 동종 업종 경쟁이 거의 없는 점은 큰 장점입니다.");
        } else if (competitorCount == 1) {
            sb.append(String.format("주변 경쟁사: %s 1곳의 적당한 경쟁이 있을 예정이지만 무난한 조건입니다.", competitorNamesWithDist.get(0)));
        } else {
            String list = String.join(", ", competitorNamesWithDist);
            long remain = competitorCount - competitorNamesWithDist.size();
            if (remain > 0) sb.append(String.format("주변 경쟁사 %d곳: %s 외 %d곳이 있습니다. 따라서 경쟁 업체가 많으므로 좋지 않은 입지 조건입니다.", competitorCount, list, remain));
            else sb.append(String.format("주변 경쟁사 %d곳: %s가(이) 있습니다. 따라서 경쟁 업체가 많으므로 좋지 않은 입지 조건입니다.", competitorCount, list));
        }
        return sb.toString().trim();
    }

    // 메뉴 적합성을 위한 상수 세팅
    private static final int MENU_BASE_SCORE = 90;   // 기본 점수
    private static final int MENU_MAX = 95;
    private static final int MENU_MIN = 10;

    // 학생 친화(공강 시간에 빠르게 먹기 좋은 메뉴) 보너스 + 설문 조사 기반 선호 메뉴(초밥, 국밥) 보너스
    private static final Set<String> FAST_FRIENDLY = Set.of(
            "덮밥/비빔밥","면/국수","라멘/우동/소바","짜장면","짬뽕","덮밥/도시락",
            "샌드위치/샐러드","조각 케이크","아메리카노"
    );
    private static final Set<String> PREFERRED = Set.of("초밥","국밥");

    // AI로 '대표메뉴'의 대학가 평균가 가격 받아오는 메서드
    private Integer fetchMenuAvgPriceFromAI(String representativeMenuName) throws JsonProcessingException {
        if (representativeMenuName == null || representativeMenuName.isBlank()) return null;

        String prompt = String.format("""
      # 역할: 가격 추정 로봇
      # 임무: '안산 대학가(한양대 ERICA 주변 기준)'에서 "%s" 1인 기준 평균 판매가(원)를 추정해 정수 숫자만 JSON으로 반환.
      # 절대 규칙:
      - 코드블록 금지, 순수 JSON만.
      - 단위 '원'이나 텍스트 금지. 정수값만.
      - 값은 과도하게 극단치가 되지 않도록 보수적으로.
      
      { "avgPrice": (정수원) }
      """, representativeMenuName);

        String ai = aiChatService.getAnalysisResponseFromAI(prompt);
        String clean = ai.replace("```json","").replace("```","").trim();

        // {"avgPrice": 6500} 형태 파싱
        record AvgPrice(int avgPrice) {}
        try {
            AvgPrice parsed = objectMapper.readValue(clean, AvgPrice.class);
            return parsed.avgPrice();
        } catch (Exception e) {
            System.err.println("[WARN] Menu avg price AI parse fail: " + e.getMessage());
            return null; // 실패 시 백업 룰 사용
        }
    }

    // AI 실패 시를 위한 보수적 백업 테이블(AI가 갑자기 튀는 값을 넣어서 값이 이상하게 0, 9999999이렇게 나오는 경우가 있다고 해서 만든 메서드입니다)
    private Integer fallbackAvgPrice(String representativeMenuName) {
        if (representativeMenuName == null) return null;
        String key = representativeMenuName.replaceAll("\\s+","").toLowerCase();
        Map<String,Integer> table = Map.ofEntries(
                //내용은 안 중요한 코드라 옆으로 길게 썼습니다
                Map.entry("아메리카노", 2000),Map.entry("조각케이크", 6500), Map.entry("샌드위치/샐러드", 7000), Map.entry("아이스크림/빙수", 8000), Map.entry("구움과자", 4000), Map.entry("국밥", 9000), Map.entry("덮밥/비빔밥", 9000), Map.entry("면/국수", 8000), Map.entry("찜/탕/찌개", 10000), Map.entry("구이/볶음류", 11000), Map.entry("팟타이", 11000), Map.entry("나시고렝", 11000), Map.entry("쌀국수", 11000), Map.entry("똠얌꿍", 13000), Map.entry("반미", 7000),
                Map.entry("파스타", 13000), Map.entry("스테이크", 20000), Map.entry("리조또", 13000), Map.entry("샐러드/브런치", 12000), Map.entry("짜장면", 7000), Map.entry("짬뽕", 9000), Map.entry("탕수육", 16000), Map.entry("마라탕/샹궈", 13000), Map.entry("초밥", 13000), Map.entry("회", 18000), Map.entry("돈카츠", 11000), Map.entry("라멘/우동/소바", 9000), Map.entry("덮밥/도시락", 9000), Map.entry("기타",10000)
        );
        // AI 호출 실패 시 가장 가까운 키 매칭
        for (String k : table.keySet()) {
            if (key.contains(k.replace("/","").toLowerCase())) return table.get(k);
        }
        return null;
    }

    // "메뉴 적합성 점수 로직"
    private AnalysisResponse.ScoreInfo calculateMenuSuitabilityScore(AnalysisRequest request) {
        String menu = request.representativeMenuName();
        Integer userPrice = request.representativeMenuPrice(); // 단위: 원 (프론트 입력 기준)

        // 기본 방어(실패 시)
        if (menu == null || menu.isBlank() || userPrice == null || userPrice <= 0) {
            return new AnalysisResponse.ScoreInfo(
                    "메뉴 적합성", 70, null,
                    "— 점수 산식 —\n기본점수: 70\n사유: 대표메뉴/가격 정보 부족 → 보수적 점수 부여"
            );
        }

        Integer avg = null;
        try { avg = fetchMenuAvgPriceFromAI(menu); } catch (Exception ignore) {}
        if (avg == null) avg = fallbackAvgPrice(menu); // 실패 시 백업
        if (avg == null) {
            return new AnalysisResponse.ScoreInfo(
                    "메뉴 적합성", 70, null, "평균가 추정이 어려워 보수적 점수를 부여했습니다."
            );
        }

        // 90점을 기준으로, 평균가 대비 ±10% 당 ±5점
        // diffRatio > 0 이면 우리 가격이 평균보다 비쌈 → 감점
        double diffRatio = (userPrice - avg) / (double) avg;
        int adjustByPrice = (int) Math.round((diffRatio / 0.10) * -5);
        int score = MENU_BASE_SCORE + adjustByPrice;

        // 대학생 타겟/설문 선호 -> 가산점 부여
        List<String> bonusNotes = new ArrayList<>();
        int bonus = 0;
        if (FAST_FRIENDLY.contains(menu)) {
            bonus += 5;
            bonusNotes.add("공강 시간에 빠르게 먹기 좋은 메뉴입니다.(+5)");
        }
        if (PREFERRED.contains(menu)) {
            bonus += 5;
            bonusNotes.add("해당 대표 메뉴는 학생 설문 선호 메뉴(초밥/국밥)이기에 이점이 있습니다.(+5)");
        }
        score += bonus;

        // 캡핑
        score = Math.max(MENU_MIN, Math.min(MENU_MAX, score));

        // summary 먼저
        double pct = Math.abs(diffRatio) * 100.0;
        boolean nearlyZero = Math.abs(diffRatio) < 0.005; // ±0.5% 이내면 동일 취급
        String priceSentence = nearlyZero
                ? String.format("입력 가격 %,d원은 평균과 거의 동일합니다.", userPrice)
                : String.format("입력 가격 %,d원은 평균 대비 %s%.0f%% %s 편입니다.",
                userPrice,
                (diffRatio >= 0 ? "+" : "-"),
                pct,
                (diffRatio >= 0 ? "높은" : "낮은"));
        String extraSentence = bonusNotes.isEmpty() ? "" : " " + String.join(" / ", bonusNotes);

        String summary = String.format(
                "해당 대표메뉴 평균가는 약 %,d원으로 추정됩니다. %s%s",
                avg, priceSentence, extraSentence
        );

        // 점수 산식은 뒤에
        String breakdown = String.format(
                "기본점수: %d%n" +
                        "가격 가감: %+d (평균가 %,d원 대비 %s%.0f%%)%n" +
                        "보너스 합계: %+d (%s)%n" +
                        "최종점수: %d",
                MENU_BASE_SCORE,
                adjustByPrice, avg, (diffRatio >= 0 ? "+" : "-"), pct,
                bonus, (bonusNotes.isEmpty() ? "-" : String.join(", ", bonusNotes)),
                score
        );

        String reason = summary + "\n\n— 점수 산식 —\n" + breakdown;

        return new AnalysisResponse.ScoreInfo("메뉴 적합성", score, null, reason);
    }

    private static String signed(int v) { return (v >= 0 ? "+" : "") + v; }

    // 예산 적합성 층별 1평당 (보증금, 월세) 상수, 가격 단위는 "원/평"입니다
    public record FloorPrice(int depositPerPy, int monthlyPerPy) {}

    // 각 층별 1평당 보증금/월세 원
    // B는 그냥 지하층 전부 다 포괄. 5층 이상이랑 루프탑/옥상은 별개.
    // 실제 데이터에 루프탑이랑 지하는 없었어서 나머지를 기준으로 보수적으로 잡았습니다.
    private static final Map<String, FloorPrice> PY_PRICE_BY_FLOOR = Map.of(
            "B",    new FloorPrice(607_000, 34_000),
            "1F",   new FloorPrice(3_540_000, 177_000),
            "2F",   new FloorPrice(860_000, 43_000),
            "3F",   new FloorPrice(720_000, 36_000),
            "4F+",   new FloorPrice(680_000, 34_000),
            "ROOF", new FloorPrice(516_000, 27_000)
    );

    // 우리 층 수 String으로 받아오는지 프론트에 물어보기
    private String floorKeyFrom(Integer height) {
        if (height < 0) return "B";
        if (height == 1) return "1F";
        if (height == 2) return "2F";
        if (height == 3) return "3F";
        if (height == 99) return "ROOF"; // 옥상/루프탑 고르면 99로 넘겨달라고 해야징
        return "4F+";
    }

    // 만원 → 원 변환 (예: 150만원 => 1_500_000원)
    private static long manToWon(Integer man) {
        if (man == null) return 0L;
        return man.longValue() * 10_000L;
    }

    // 원 → 만원 문자열 포맷 (콤마, 단위 포함)
    private static String wonToManStr(long won) {
        long man = Math.round(won / 10_000.0);
        return String.format("%,d만원", man);
    }

    // 소수점 없는 퍼센트 문자열
    private static String pct0(double v) {
        return String.format("%.0f%%", v);
    }

    // "예산 적합성" 계산 로직
    private AnalysisResponse.ScoreInfo calculateBudgetSuitabilityScore(AnalysisRequest request) {
        // 방어: 필수 입력
        if (request.size() == null || request.budget() == null) {
            return new AnalysisResponse.ScoreInfo(
                    "예산 적합성", 70, null,
                    "면적 또는 월세 예산 정보가 부족해 보수적으로 평가했습니다."
            );
        }

        // 입력 파라미터
        Integer sizeMin = Optional.ofNullable(request.size().min()).orElse(0);   // 평
        Integer sizeMax = Optional.ofNullable(request.size().max()).orElse(sizeMin);
        Integer rentMinMan = Optional.ofNullable(request.budget().min()).orElse(0); // 만원
        Integer rentMaxMan = Optional.ofNullable(request.budget().max()).orElse(rentMinMan);
        Integer depoMinMan = request.deposit() == null ? 0 : Optional.ofNullable(request.deposit().min()).orElse(0);
        Integer depoMaxMan = request.deposit() == null ? depoMinMan : Optional.ofNullable(request.deposit().max()).orElse(depoMinMan);

        // 층 키 및 1평 단가
        String floorKey = floorKeyFrom(request.height());
        FloorPrice fp = PY_PRICE_BY_FLOOR.getOrDefault(floorKey, PY_PRICE_BY_FLOOR.get("1F"));

        // 예상 총 월세/보증금 (원) : (단가 원/평) × (평수)
        long expRentMin  = (long) fp.monthlyPerPy() * sizeMin;
        long expRentMax  = (long) fp.monthlyPerPy() * sizeMax;
        long expRentMid  = Math.round((expRentMin + expRentMax) / 2.0);

        long expDepoMin  = (long) fp.depositPerPy() * sizeMin;
        long expDepoMax  = (long) fp.depositPerPy() * sizeMax;
        long expDepoMid  = Math.round((expDepoMin + expDepoMax) / 2.0);

        // 사용자 예산(만원) → 원
        long userRentMax = manToWon(rentMaxMan);
        long userDepoMax = manToWon(depoMaxMan);

        // 점수 규칙 (월세/보증금 각각 채점, 가중합)
        int base = 90, minScore = 10, maxScore = 100;

        int rentDelta = scoreDeltaAgainstRange(userRentMax, expRentMin, expRentMid, expRentMax);
        int depoDelta = scoreDeltaAgainstRange(userDepoMax, expDepoMin, expDepoMid, expDepoMax);

        // 가중합: 월세 70%, 보증금 30%
        int score = base + (int) Math.round(rentDelta * 0.7 + depoDelta * 0.3);
        score = Math.max(minScore, Math.min(maxScore, score));

        // 이유(멘트 정해놓고 값 넣어서 주기)
        String summary = String.format(
                "해당 지역의 요청 층수 %s 기준 1평당 예상 단가는 보증금 %,d원/월세 %,d원입니다. " +
                        "희망 면적 %d~%d평을 적용할 때 예상 총 월세는 %s - %s(중앙값 %s), 보증금은 %s~%s(중앙값 %s)로 추정됩니다. " +
                        "입력 예산은 월세 최대 %s, 보증금 최대 %s이며, 이를 기준으로 월세 적합도 %s, 보증금 적합도 %s로 평가했습니다.",
                floorKey, fp.depositPerPy(), fp.monthlyPerPy(),
                sizeMin, sizeMax,
                wonToManStr(expRentMin), wonToManStr(expRentMax), wonToManStr(expRentMid),
                wonToManStr(expDepoMin), wonToManStr(expDepoMax), wonToManStr(expDepoMid),
                String.format("%,d만원", rentMaxMan), String.format("%,d만원", depoMaxMan),
                subScoreLabel(userRentMax, expRentMin, expRentMid, expRentMax),
                subScoreLabel(userDepoMax, expDepoMin, expDepoMid, expDepoMax)
        );

        // 예산 적합도 점수 산식 추가
        String breakdown = String.format(
                "기본점수: %d\n" +
                        "월세 가감: %+d (사용자 최대 %s vs 예상 %s~%s~%s)\n" +
                        "보증금 가감: %+d (사용자 최대 %s vs 예상 %s~%s~%s)\n" +
                        "점수 비중: 월세×70%% / 보증금×30%%\n" +
                        "최종점수: %d",
                base,
                rentDelta, String.format("%,d만원", rentMaxMan), wonToManStr(expRentMin), wonToManStr(expRentMid), wonToManStr(expRentMax),
                depoDelta, String.format("%,d만원", depoMaxMan), wonToManStr(expDepoMin), wonToManStr(expDepoMid), wonToManStr(expDepoMax),
                score
        );

        String reason = summary + "\n\n— 점수 산식 —\n" + breakdown;
        return new AnalysisResponse.ScoreInfo("예산 적합성", score, null, reason);
    }

    /**
     * 사용자 최대치(userMax)를 예상 구간과 비교해 점수 증감을 반환
     *  - userMax >= expMax : +10
     *  - userMax >= expMid : +5
     *  - userMax >= expMin :  0
     *  - userMax  < expMin : 부족비율 10% 당 -5 (최소 -30까지)
     */
    private int scoreDeltaAgainstRange(long userMax, long expMin, long expMid, long expMax) {
        if (userMax >= expMax) return +10;
        if (userMax >= expMid) return +5;
        if (userMax >= expMin) return 0;

        // 부족비율 (expMin 대비)
        double gap = (expMin - userMax) / (double) expMin; // 0~1+
        int steps = (int) Math.ceil(gap / 0.10);           // 10% 단위
        return Math.max(-30, -5 * steps);
    }

    // 서브 라벨: 사용자 최대가 구간 어디에 있는지 표현
    private String subScoreLabel(long userMax, long expMin, long expMid, long expMax) {
        if (userMax >= expMax) return "충분(상한 이상)";
        if (userMax >= expMid) return "양호(중앙값 이상)";
        if (userMax >= expMin) return "가능(최소값 이상)";
        double gap = (expMin - userMax) / (double) expMin;
        return "부족(" + pct0(gap * 100) + " 부족)";
    }


    // 최종 프롬프트
    private String createSimpleFinalReportPrompt(AnalysisRequest request, List<AnalysisResponse.ScoreInfo> scores, List<Review> reviews) {
        String scoreInfo = scores.stream()
                .map(s -> String.format("- %s: %d점 (%s)", s.name(), s.score(), s.reason()))
                .collect(Collectors.joining("\n"));

        String reviewInfo = reviews.isEmpty()
                ? "분석에 참고할 만한 주변 가게의 리뷰 데이터가 없습니다."
                : reviews.stream()
                .limit(10)
                .map(r -> String.format("- [%s] \"%s\" (평점: %s)",
                        r.getRestaurant().getRestaurantName(), r.getContent(), r.getRating().toString()))
                .collect(Collectors.joining("\n"));

        return String.format("""
            # 역할: JSON 보고서 작성 전문가
            # 임무: 아래 데이터를 바탕으로 창업 분석 보고서를 JSON으로 작성
            ## 창업 희망 조건
            - 희망 위치: %s
            - 희망 업종: %s
            ## 계산된 점수
            %s
            ## 주변 리뷰 데이터
            %s
            ## 출력 규칙
            - 순수 JSON (코드블록 금지)
            - 숫자는 따옴표 없이, 텍스트는 따옴표로
            {
              "scores": [
                {"name": "접근성", "score": %d, "expectedPrice": null, "reason": "%s"},
                {"name": "예산", "score": %d, "expectedPrice": null, "reason": "%s"},
                {"name": "메뉴 적합성", "score": %d, "expectedPrice": null, "reason": "%s"}
              ],
              "reviewAnalysis": {
                "summary": "주변 리뷰 종합 분석...",
                "positiveKeywords": ["키워드1", "키워드2"],
                "negativeKeywords": ["키워드3", "키워드4"],
                "reviewSamples": []
              },
              "tips": [
                {"type": "success", "message": "성공 요인 조언..."},
                {"type": "warning", "message": "주의사항 조언..."},
                {"type": "info", "message": "참고사항 조언..."}
              ]
            }
            """,
                request.addr(), request.category(),
                scoreInfo, reviewInfo,
                scores.get(0).score(), escapeForJson(scores.get(0).reason()),
                scores.get(1).score(), escapeForJson(scores.get(1).reason()),
                scores.get(2).score(), escapeForJson(scores.get(2).reason())
        );}

    /**
     * 클라이언트 대분류를 카카오 세부 카테고리까지 포괄하도록 확장.
     * 분류: "카페/디저트", "치킨", "피자", "패스트푸드", "한식", "아시안", "양식", "중식", "일식" (피그마 내용 따랐습니다)
     */
    private List<String> expandCategoryKeywords(String category) {
        String c = Optional.ofNullable(category).orElse("").toLowerCase();

        // 각 대분류에 대응하는 세부 키워드 목록
        Map<String, List<String>> bucket = new LinkedHashMap<>();

        // 카페/디저트
        bucket.put("카페/디저트", Arrays.asList(
                "카페","커피전문점","디저트카페","제과,베이커리","제과","베이커리",
                "갤러리카페","테마카페","무인카페","생과일전문점","전통찻집",
                "아이스크림","빙수","디저트","도넛","브런치카페","커피"));

        // 피자/치킨
        bucket.put("피자/치킨", Arrays.asList(
                "피자","치킨","닭강정","양념치킨"));

        // 패스트푸드 (간편식 포함)
        bucket.put("패스트푸드", Arrays.asList(
                "햄버거","패스트푸드","핫도그","샌드위치","도시락","주먹밥",
                "분식","김밥","떡볶이"));

        // 한식 (국/찌개/고기/탕류 등 폭넓게 커버)
        bucket.put("한식", Arrays.asList(
                "한식","국밥","국수","칼국수","냉면",
                "찌개,전골","찌개","전골","순대","감자탕","해장국",
                "육류,고기","삼겹살","곱창,막창","곱창","막창","족발,보쌈","족발","보쌈",
                "오리","삼계탕","매운탕,해물탕","매운탕","해물탕","해물,생선","해물","생선",
                "죽","덮밥"));

        // 아시안 (태국/베트남/인도 등 동남아/남아시아 계열)
        bucket.put("아시안", Arrays.asList(
                "아시안","아시아","태국","베트남","쌀국수","포","인도","카레",
                "말레이","싱가포르","샤브샤브" // 향후 확장 대비
        ));

        // 양식 (이탈리안/스테이크/브라질, 멕시칸 포함 — JSON에 존재)
        bucket.put("양식", Arrays.asList(
                "양식","이탈리안","파스타","스테이크,립","스테이크","립",
                "경양식","브런치","멕시칸,브라질","멕시칸","브라질"));

        // 중식
        bucket.put("중식", Arrays.asList(
                "중식","중국","중국요리","마라","짬뽕","짜장면","훠궈"));

        // 일식
        bucket.put("일식", Arrays.asList(
                "일식","돈까스,우동","돈까스","우동","라멘","라면","스시","초밥","퓨전일식","연어"));

        // 입력 대분류에 따라 매칭 키워드 생성
        Set<String> set = new LinkedHashSet<>();
        if (!c.isBlank()) {
            // 정확 일치 우선 (대분류 이름이 그대로 들어온 경우)
            bucket.forEach((k, v) -> {
                if (k.equalsIgnoreCase(c)) set.addAll(v);
            });

            // 혹시 사용자가 "카페" 같은 단어로만 보내는 경우도 커버(근데 아마 드랍다운으로 강제할거라 괜찮을듯. 이거 아래 if문까지도.
            bucket.forEach((k, v) -> {
                if (k.toLowerCase().contains(c) || c.contains(k.toLowerCase())) set.addAll(v);
            });

            // 그래도 비어있다면, 입력 자체를 키워드로 사용 (fallback)
            if (set.isEmpty()) set.add(c);
        }

        // 항상 소문자 비교할 것이므로 소문자화
        return set.stream().map(String::toLowerCase).distinct().toList();
    }

    // 교집합 키 (가능하면 placeId가 안정적)
    private String keyOf(Restaurant r) {
        if (r.getKakaoPlaceId() != null) return "id:" + r.getKakaoPlaceId();
        return "name:" + normalize(r.getRestaurantName());
    }

    private String normalize(String s) {
        return Optional.ofNullable(s).orElse("")
                .replaceAll("\\s+", "")
                .toLowerCase();
    }

    private String escapeForJson(String s) {
        return Optional.ofNullable(s).orElse("")
                .replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String safeName(Restaurant r) { return Optional.ofNullable(r.getRestaurantName()).orElse("(이름없음)"); }
    private String safeCat(Restaurant r) { return Optional.ofNullable(r.getCategory()).orElse("(카테고리없음)"); }
}