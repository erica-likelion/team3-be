package likelion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import likelion.dto.AnalysisRequest;
import likelion.dto.AnalysisResponse;
import likelion.domain.entity.Restaurant;
import likelion.domain.entity.Review;
import likelion.repository.RestaurantRepository;
import likelion.repository.ReviewRepository;
import likelion.service.distance.DistanceCalc;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final RestaurantRepository restaurantRepository;
    private final ReviewRepository reviewRepository;
    private final AiChatService aiChatService;
    private final ObjectMapper objectMapper;

    // 위치 점수 계산 결과
    private record LocationScoreFactors(int score, double distanceToMainGate,
            int floor, long competitorCount, List<String> competitorNames, // "가게명(거리m)" 형태
            String reason
    ) {}


    // ========================= 분석 메서드들 실행하고 반환하는 곳 ============================
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

        // 업종(문자열) 기반 동종업계 1차 필터 (아래에 카테고리-json의 키워드 매핑 코드 있습니다), (동종업계는 위치/접근성에 사용)
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


        //  위치 점수 계산 (최종 = 반경 안 + 동종업계 교집합 기준)
        LocationScoreFactors locationFactors =
                calculateLocationScore(request, latitude, longitude, competitorsInRadius);

        // 점수 배열 구성
        List<AnalysisResponse.ScoreInfo> scores = new ArrayList<>();
        scores.add(new AnalysisResponse.ScoreInfo("접근성", locationFactors.score(), null, locationFactors.reason()));
        scores.add(calculateBudgetSuitabilityScore(request));
        scores.add(calculateMenuSuitabilityScore(request));

        // 동종업계 리뷰 분석 생성(반경 무관, 같은 카테고리 전체)
        AnalysisResponse.ReviewAnalysis reviewAnalysis = buildReviewAnalysisSameCategory(targetCategory);

        // 상세분석 생성: 사용자 인풋 + 점수 reason만 활용해서 일단 최소기능만 구현
        AnalysisResponse.DetailAnalysis detailAnalysis = buildDetailAnalysis(request, scores);

        // scores: 3가지 항목 점수 관련, reviewAnalysis: 리뷰 관련, detailAnalysis: 상세 분석 관련
        return new AnalysisResponse(scores, reviewAnalysis, detailAnalysis);
    }

    // ============================= "위치/접근성" 계산 로직 ===========================
    private LocationScoreFactors calculateLocationScore(AnalysisRequest request, double latitude, double longitude, List<Restaurant> competitorsInRadius) {
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

    // ======================== 사용자 설명에 보낼 멘트(위치 점수 관련) ========================
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

    // ================================= "메뉴 적합성 점수 로직" ======================================
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
            bonusNotes.add("공강시간에 빠르게 먹기 좋은 메뉴라는 점에서 (+5)");
        }
        if (PREFERRED.contains(menu)) {
            bonus += 5;
            bonusNotes.add("해당 대표 메뉴는 학생 설문 선호 메뉴(초밥/국밥)이기에 (+5)");
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
//        String extraSentence = bonusNotes.isEmpty() ? "" : " " + String.join(" / ", bonusNotes);
//
//        String summary = String.format(
//                "해당 대표메뉴 평균가는 약 %,d원으로 추정됩니다. %s%s",
//                avg, priceSentence, extraSentence
//        );

        int pricePenalty = Math.min(0, adjustByPrice); //가격 때문에 깎인 점수
        int priceBonus   = Math.max(0, adjustByPrice); //가격 때문에 오른 점수

        //가점 텍스트는 기존 bonusNotes 그대로 사용 + 가격 가점이 있으면 항목 추가
        List<String> bonusList = new ArrayList<>(bonusNotes);
            if (priceBonus > 0) {
                bonusList.add(String.format("가격 요인 +%d점", priceBonus));
            }
        String bonusStr   = bonusList.isEmpty() ? "없음" : String.join(" / ", bonusList);
        String penaltyStr = (pricePenalty < 0) ? String.format("가격 요인 %d점", pricePenalty) : "없음";

        // 합계 계산 (기존 bonus 변수를 그대로 사용)
        int bonusSum   = priceBonus + bonus;
        int penaltySum = pricePenalty;

        String summary = String.format(
                "해당 대표메뉴 평균가는 약 %,d원으로 추정됩니다. %s 감점: %s(합계 %s점)입니다. " +
                        "가점: %s(합계 +%d점)입니다.",
                avg,
                priceSentence,
                penaltyStr, String.format("%+d", penaltySum),
                bonusStr, bonusSum
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

    // =========================== "예산 적합성" 계산 로직 ================================
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

    private static double ratingAsDouble(Review r) {
        BigDecimal bd = r.getRating();
        return (bd == null) ? 0.0 : bd.doubleValue();
    }

    // 리뷰 분석
    private AnalysisResponse.ReviewAnalysis buildReviewAnalysisSameCategory(String targetCategory) {
        // 동종업계 가게 전부 수집
        List<String> keywords = expandCategoryKeywords(Optional.ofNullable(targetCategory).orElse("").trim());
        List<Restaurant> sameCategoryAll = restaurantRepository.findAll().stream()
                .filter(r -> {
                    String c = Optional.ofNullable(r.getCategory()).orElse("").toLowerCase();
                    return keywords.stream().anyMatch(c::contains);
                })
                .toList();

        if (sameCategoryAll.isEmpty()) {
            return new AnalysisResponse.ReviewAnalysis(
                    null,
                    List.of(),
                    "해당 카테고리의 가게가 없어 리뷰 기반 피드백을 제공하기 어렵습니다."
            );
        }

        // 동종업계 집합 만들고, placeId로 1차 리뷰 조회
        Set<String> nameSet = sameCategoryAll.stream()
                .map(Restaurant::getRestaurantName)
                .filter(Objects::nonNull)
                .map(this::normalizeName)
                .collect(Collectors.toSet());

        List<Long> ids = sameCategoryAll.stream()
                .map(Restaurant::getKakaoPlaceId)
                .filter(Objects::nonNull)
                .toList();

        List<Review> reviews = ids.isEmpty()
                ? List.of()
                : reviewRepository.findAllByRestaurantKakaoPlaceIdInOrderByIdDesc(ids);

        // placeId가 비어있는 데이터가 있다면, 이름 일치로 한 번 더 거르기(안전빵)
        List<Review> sameNameReviews = reviews.stream()
                .filter(r -> {
                    String nm = Optional.ofNullable(r.getRestaurant())
                            .map(Restaurant::getRestaurantName).orElse(null);
                    return nm != null && nameSet.contains(normalizeName(nm));
                })
                .toList();

        List<Review> base = sameNameReviews.isEmpty() ? reviews : sameNameReviews;
        if (base.isEmpty()) {
            return new AnalysisResponse.ReviewAnalysis(
                    null,
                    List.of(),
                    "해당 카테고리의 리뷰 데이터가 없어 리뷰 기반 피드백을 제공하기 어렵습니다."
            );
        }

        // 카테고리 전체 평균 평점
        double avg = base.stream().map(Review::getRating).filter(Objects::nonNull).mapToDouble(java.math.BigDecimal::doubleValue).average().orElse(Double.NaN);
        Double averageRating = Double.isNaN(avg) ? null : Math.round(avg * 10.0) / 10.0;

        // GPT한테 도움 되는 리뷰 4개, 피드백만 json으로 받아오기(피드백 내용 만들때는 모든 리뷰 다 씀)
        // 모든 리뷰를 넘기되, 한 줄로 정제하고 220자 넘는건 컷
        String lines = base.stream()
                .map(r -> {
                    String store = Optional.ofNullable(r.getRestaurant())
                            .map(Restaurant::getRestaurantName).orElse("(이름없음)");
                    double score = ratingOf(r); // BigDecimal -> double
                    String content = safeLine(Optional.ofNullable(r.getContent()).orElse(""));
                    if (content.length() > 220) content = content.substring(0, 220) + "…";
                    return String.format("- [%s | %.1f] %s", store, score, content);
                })
                .collect(Collectors.joining("\n"));

        String prompt = """
                # Role: Review analysis coach
                # Task:
                - You are analyzing reviews for the "%s" category in a **university-area** business context.
                - From ALL reviews below, pick about **4** samples that would most help a prospective owner.
                - Then provide **concise, practical feedback** (in Korean) for running a successful business in this category and area.
                
                # Output rules (MUST):
                - **Write ALL output in Korean.**
                - Return **pure JSON only** (no code block, no extra text).
                - For each item in "reviewSamples":
                  - Keep "storeName" as-is (가게명).
                  - "reviewScore" must be a number (0.0~5.0).
                  - "highlights" must be **an array with exactly ONE sentence** (1줄 요약, 구어체로).
                    - **Remove emojis/repeat chars like ㅋㅋ/ㅎㅎ/ㅠㅠ, URLs, hashtags, @mentions.**
                    - Normalize spacing/punctuation.
                    - Keep it within **80 characters** and end with a period.
                - In "feedback":
                  - **Do not mention any store names**.
                  - Provide **university-area–aware advice** (예: 학생 피크타임 운영, 가성비/포션, 회전율, 소음/분위기, 연령대·모임 수요 등).
                  - 내가 준 모든 리뷰 내용을 고려하여 관련된 조언을 해주고(특히 reviewSamples에 나온 내용 관련해선 꼭 언급해줘).
                  - 한국인들의 친근감이 들 수 있게 친절한 상담사처럼 "~요"체로 말해줘.
                  - 내용이 너무 적으면 너가 서칭해서라도 관련 업계 팁을 최소 4줄 정도 채워줘.
                  - ****반드시 방학(비성수기) / 학기중(성수기) / 시험기간(성수기)의 유동인구 차이를 언급하고, 각 시기별 운영 전략과 팁을 위의 내용들에 추가해줘.(위의 내용들도 언급하고 이것은 추가 언급****
                
                # JSON schema:
                {
                  "reviewSamples": [
                    {"storeName": "가게명", "reviewScore": 4.5, "highlights": ["한 줄 요약."]}
                  ],
                  "feedback": "종합 피드백 (한국어)"
                }
                
                # Reviews
                %s
                """.formatted(targetCategory, lines);

        List<AnalysisResponse.ReviewSample> samples;
        String feedback;
        try {
            String raw = aiChatService.getAnalysisResponseFromAI(prompt)
                    .replace("```", "").trim();

            // GPT 응답 파싱용 임시 레코드
            record Out(List<Map<String, Object>> reviewSamples, String feedback) {}
            Out out = objectMapper.readValue(raw, Out.class);

            // 안전 매핑: storeName, reviewScore, highlights
            samples = Optional.ofNullable(out.reviewSamples())
                    .orElse(List.of())
                    .stream()
                    .limit(4)
                    .map(m -> {
                        String store = String.valueOf(m.getOrDefault("storeName", "(이름없음)"));
                        double score2;
                        try { score2 = Double.parseDouble(String.valueOf(m.getOrDefault("reviewScore", 0.0))); }
                        catch (Exception e) { score2 = 0.0; }
                        @SuppressWarnings("unchecked")
                        List<String> hl = (List<String>) m.getOrDefault("highlights", List.of());
                        if (hl == null) hl = List.of();
                        return new AnalysisResponse.ReviewSample(store, score2, hl);
                    })
                    .toList();

            feedback = Optional.ofNullable(out.feedback()).orElse("리뷰를 바탕으로 운영 팁을 요약했습니다.");
        }
        catch (Exception e) {
            // 파싱 실패 시: 단순 샘플 4개 + 기본 피드백
            samples = base.stream().limit(4)
                    .map(r -> new AnalysisResponse.ReviewSample(
                            Optional.ofNullable(r.getRestaurant()).map(Restaurant::getRestaurantName).orElse("(이름없음)"),
                            ratingOf(r),
                            List.of(snippet(r.getContent(), 80))
                    ))
                    .toList();
            feedback = "리뷰 내용을 참고해 메뉴 품질 일관성, 피크타임 대기 관리, 직원 응대 매뉴얼(인사/설명/불만 응대), 위생·청결체크리스트를 체계화하세요. 상권 피드백이 반복되는 항목은 우선순위로 개선하세요.";
        }
        return new AnalysisResponse.ReviewAnalysis(
                averageRating,
                samples,
                feedback
        );
    }

    // 리뷰 로직 헬퍼
    private double ratingOf(Review r) {
        java.math.BigDecimal b = r.getRating();
        return (b == null) ? 0.0 : b.doubleValue();
    }
    private String normalizeName(String s) {
        return Optional.ofNullable(s).orElse("").replaceAll("\\s+", "").toLowerCase();
    }
    // 리뷰 내용 짧게 자르는 유틸
    private String snippet(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
    // 프롬프트/로그 안전용: 개행/탭 제거,트림
    private String safeLine(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ").replace("\t", " ").trim();
    }

    // ==================================== 상세분석 관련 로직 =========================================
    private AnalysisResponse.DetailAnalysis buildDetailAnalysis(
            AnalysisRequest req,
            List<AnalysisResponse.ScoreInfo> scores
    ) {
        // ai한테 상세 분석에는 사용자가 입력한 인풋이랑 1차 분석에서 나온 점수 이유 넘겨줄거임
        // 사용자 인풋 요약
        String floorKey = floorKeyFrom(req.height());
        String sizeStr = (req.size() == null) ? "미입력"
                : String.format("%s~%s평",
                Optional.ofNullable(req.size().min()).orElse(0),
                Optional.ofNullable(req.size().max()).orElse(0));
        String rentStr = (req.budget() == null) ? "미입력"
                : String.format("%s~%s만원",
                Optional.ofNullable(req.budget().min()).orElse(0),
                Optional.ofNullable(req.budget().max()).orElse(0));
        String depoStr = (req.deposit() == null) ? "미입력"
                : String.format("%s~%s만원",
                Optional.ofNullable(req.deposit().min()).orElse(0),
                Optional.ofNullable(req.deposit().max()).orElse(0));
        String menuStr = (req.representativeMenuName() == null) ? "미선택"
                : String.format("%s(%,d원)", req.representativeMenuName(),
                Optional.ofNullable(req.representativeMenuPrice()).orElse(0));

        String inputSummary = """
        - 위치 좌표: %s
        - 업종 카테고리: %s
        - 상권 타입: %s
        - 희망 면적: %s
        - 희망 층수: %s
        - 월세 예산: %s
        - 보증금 예산: %s
        - 대표메뉴: %s
        """.formatted(
                Optional.ofNullable(req.addr()).orElse("미입력"),
                Optional.ofNullable(req.category()).orElse("미입력"),
                Optional.ofNullable(req.marketingArea()).orElse("미입력"),
                sizeStr, floorKey, rentStr, depoStr, menuStr
        ).trim();

        // 점수 사유를 항목별로 꺼내기(이름으로 매칭)
        String reasonAccess = findReason(scores, "접근성");
        String reasonBudget = findReason(scores, "예산 적합성"); // DTO/프론트 표기에 맞춰 사용
        // if (reasonBudget.isBlank()) reasonBudget = findReason(scores, "예산");
        String reasonMenu   = findReason(scores, "메뉴 적합성");

        // 프롬프트
        String prompt = """
        # Role: Senior retail consultant for **university-area** businesses
        # Task:
        - Based ONLY on the user's inputs and the three score reasons below,
          write a **detailed Korean analysis** for each of:
            1) 접근성
            2) 예산 적합성
            3) 메뉴 적합성
        - For each section, explain why the score likely came out that way,
          and give concrete, prioritized tips to raise the score.
        - Always consider it's a **university-area** (student traffic, peak hours, price sensitivity, group demand, quick turns).

        # Output rules (MUST):
        - **Write ALL output in Korean.**
        - Return **pure JSON only** (no code blocks, no extra text).
        - Structure:
          {
            "sections": [
              {"name": "접근성", "content": "문단 텍스트"},
              {"name": "예산 적합성", "content": "문단 텍스트"},
              {"name": "메뉴 적합성", "content": "문단 텍스트"}
            ]
          }
        - Content should be concise but practical, with quick wins(단기), mid-term(중기), risk watch-outs(리스크)을 포함해도 좋음.
        - Do **not** mention any specific competitor store names.

        [User Inputs]
        %s

        [Score Reasons]
        - 접근성: %s
        - 예산 적합성: %s
        - 메뉴 적합성: %s
        """.formatted(
                inputSummary,
                oneLine(reasonAccess, 500),
                oneLine(reasonBudget, 500),
                oneLine(reasonMenu,   500)
        );

        try {
            String raw = aiChatService.getAnalysisResponseFromAI(prompt)
                    .replace("```", "").trim();

            // 파싱용 임시 레코드
            record SectionOut(String name, String content) {}
            record DetailOut(List<SectionOut> sections) {}

            DetailOut out = objectMapper.readValue(raw, DetailOut.class);

            List<AnalysisResponse.DetailSection> sections = Optional.ofNullable(out.sections())
                    .orElse(List.of())
                    .stream()
                    .filter(s -> s != null && s.name() != null && s.content() != null)
                    .map(s -> new AnalysisResponse.DetailSection(s.name(), s.content().trim()))
                    .toList();

            if (sections.isEmpty()) {
                sections = fallbackSections(reasonAccess, reasonBudget, reasonMenu);
            }
            return new AnalysisResponse.DetailAnalysis(sections);

        } catch (Exception e) {
            return new AnalysisResponse.DetailAnalysis(
                    fallbackSections(reasonAccess, reasonBudget, reasonMenu)
            );
        }
    }

    // 점수 리스트에서 이름으로 reason 찾기
    private String findReason(List<AnalysisResponse.ScoreInfo> scores, String name) {
        if (scores == null) return "";
        return scores.stream()
                .filter(s -> name.equals(s.name()))
                .map(AnalysisResponse.ScoreInfo::reason)
                .findFirst()
                .orElse("");
    }

    // 파싱 실패/빈값 대비 상세분석 디폴트값
    private List<AnalysisResponse.DetailSection> fallbackSections(String r1, String r2, String r3) {
        return List.of(
                new AnalysisResponse.DetailSection(
                        "접근성",
                        "접근성 점수 사유를 바탕으로 간판·가시성, 정문 동선, 피크타임 회전율을 우선 점검하세요. " +
                                "야간 조명, 배너·윈도우사인, 네비/지도상 표기 최적화 등 즉시 실행 가능한 항목부터 개선하면 효과가 큽니다. 사유: " + oneLine(r1, 200)
                ),
                new AnalysisResponse.DetailSection(
                        "예산 적합성",
                        "면적·층수·임대단가를 다시 매칭해 월세/보증금 한도 내 대안을 도출하세요. " +
                                "필요시 면적 축소·층수 유연화, 보증금/임대료 조건 재협상, 초기비용 분산(리스·중고설비)을 검토합니다. 사유: " + oneLine(r2, 200)
                ),
                new AnalysisResponse.DetailSection(
                        "메뉴 적합성",
                        "대표메뉴 가격은 대학가 평균 대비 포지셔닝(가성비/프리미엄)을 명확히 하고 구성을 최적화하세요. " +
                                "학생 선호와 회전율을 고려해 세트/런치/빠른 메뉴를 강화하면 전환이 개선됩니다. 사유: " + oneLine(r3, 200)
                )
        );
    }

    private String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return (t.length() <= max) ? t : t.substring(0, max) + "…";
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
            - "reviewAnalysis.reviewSamples"는 **반드시 객체 배열**이어야 함. 문자열 금지.
               각 원소 형식: { "storeName": "가게명", "reviewScore": 4.5, "highlights": ["요약문1","요약문2"] }
            {
              "scores": [
                {"name": "접근성", "score": %d, "expectedPrice": null, "reason": "%s"},
                {"name": "예산", "score": %d, "expectedPrice": null, "reason": "%s"},
                {"name": "메뉴 적합성", "score": %d, "expectedPrice": null, "reason": "%s"}
              ],
              "reviewAnalysis": {
                "averageRating": null,
                "reviewSamples": [],
                "feedback": "유사업종 리뷰를 바탕으로 종합한 요약/피드백을 간결하게 작성"
              }
       
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
                "말레이","싱가포르","샤브샤브"));

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

        // 주점/술집 (신규)
        bucket.put("주점/술집", Arrays.asList(
                "주점","실내포장마차","호프,요리주점","오뎅바","술집"));


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
}