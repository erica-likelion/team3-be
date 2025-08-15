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
            scores.add(new AnalysisResponse.ScoreInfo("예산", 80, null, "임시 예산 점수"));
            scores.add(new AnalysisResponse.ScoreInfo("메뉴 적합성", 75, null, "임시 메뉴 점수"));

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

    // ====== 거리/층/경쟁사 기반 위치 점수 계산 ======
    private LocationScoreFactors calculateLocationScore(AnalysisRequest request,
                                                        double latitude, double longitude,
                                                        List<Restaurant> competitorsInRadius) {
        int score = 100;

        // 정문 좌표
        final double ERICA_MAIN_GATE_LAT = 37.300097500612374;
        final double ERICA_MAIN_GATE_LON = 126.83779165311796;

        // 정문부터 50m까진 감점 없고 초과부터는 10m당 2점 감점
        double distance = DistanceCalc.calculateDistance(latitude, longitude, ERICA_MAIN_GATE_LAT, ERICA_MAIN_GATE_LON);
        int distancePenalty = (distance > 50)
                ? (int) Math.ceil((distance - 50) / 10.0) * 2
                : 0;
        score -= distancePenalty;

        // 1층은 감점 없고 2층부터 7점씩 감점, 지하는 층당 10점 감점
        int floor = request.height() != null ? request.height() : 1;
        int floorPenalty;
        if (floor < 0) floorPenalty = Math.abs(floor) * 10;
        else if (floor >= 2) floorPenalty = (floor - 1) * 7;
        else floorPenalty = 0;
        score -= floorPenalty;

        long competitorCount = competitorsInRadius.size();

        // 이름+거리로 정렬하여 5개만
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

        // 경쟁사 하나당 3점 감점
        int competitorPenalty = competitorCount > 1 ? (int) ((competitorCount - 1) * 3) : 0;
        score -= competitorPenalty;

        score = Math.max(0, Math.min(100, score));

        // 사용자용 설명 (이름+거리 포함)
        String reason = generateLocationReason(distance, floor, competitorCount, competitorNamesWithDist);
        return new LocationScoreFactors(score, distance, floor, competitorCount, competitorNamesWithDist, reason);
    }

    // ====== 사용자 설명에 보낼 멘트(위치 점수 관련) ======
    private String generateLocationReason(double distance, int floor, long competitorCount, List<String> competitorNamesWithDist) {
        StringBuilder sb = new StringBuilder();
        if (distance <= 50) sb.append(String.format("정문과 매우 가까워(약 %.0fm) 접근성이 우수합니다. ", distance));
        else if (distance <= 100) sb.append(String.format("정문에서 도보 접근이 무난한 거리(약 %.0fm)입니다. ", distance));
        else sb.append(String.format("정문과 거리가 다소 있어(약 %.0fm) 유동인구 유입이 제한적일 수 있습니다. ", distance));

        if (floor < 0) sb.append(String.format("지하 %d층으로 간판 노출과 접근성이 제한적입니다. ", Math.abs(floor)));
        else if (floor == 1) sb.append("1층이라 고객 노출에 가장 유리합니다. ");
        else if (floor == 2) sb.append("2층이라 1층 대비 고객 유입에 다소 불리할 수 있습니다. ");
        else sb.append(String.format("%d층이라 노출이 적어 고객 유입에 불리할 수 있습니다. ", floor));

        if (competitorCount == 0) {
            sb.append("주변에 동종 업종 경쟁이 거의 없는 점은 큰 장점입니다.");
        } else if (competitorCount == 1) {
            sb.append(String.format("주변 경쟁사: %s 1곳.", competitorNamesWithDist.get(0)));
        } else {
            String list = String.join(", ", competitorNamesWithDist);
            long remain = competitorCount - competitorNamesWithDist.size();
            if (remain > 0) sb.append(String.format("주변 경쟁사 %d곳: %s 외 %d곳이 있습니다. 참고하세요!", competitorCount, list, remain));
            else sb.append(String.format("주변 경쟁사 %d곳: %s.", competitorCount, list));
        }
        return sb.toString().trim();
    }

    // ====== 최종 프롬프트(지금은 접근성/위치 점수만 반영됩니다) ======
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
                {"name": "예산", "score": %d, "expectedPrice": null, "reason": "예산 관련 상세 분석..."},
                {"name": "메뉴 적합성", "score": %d, "expectedPrice": null, "reason": "메뉴 적합성 상세 분석..."}
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
                scores.get(1).score(), scores.get(2).score());
    }

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

        // 치킨
        bucket.put("치킨", Arrays.asList(
                "치킨","닭강정","양념치킨"));

        // 피자
        bucket.put("피자", Arrays.asList(
                "피자"));

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
                "중식","중국","중국요리","마라","짬뽕","짜장","훠궈"));

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

            // 혹시 사용자가 "카페" 같은 단어로만 보내는 경우도 커버
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