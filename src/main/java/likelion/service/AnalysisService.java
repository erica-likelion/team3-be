package likelion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import likelion.controller.dto.AnalysisRequest;
import likelion.controller.dto.AnalysisResponse;
import likelion.domain.entity.Restaurant;
import likelion.domain.entity.Review;
import likelion.repository.RestaurantRepository;
import likelion.repository.ReviewRepository;
import likelion.service.distance.DistanceCalc;
import likelion.service.dto.AiPriceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final RestaurantRepository restaurantRepository;
    private final ReviewRepository reviewRepository;
    private final AiChatService aiChatService;
    private final ObjectMapper objectMapper;

    public AnalysisResponse analyze(AnalysisRequest request) {
        // addr 변수에 담긴 위도/경도 문자열을 직접 파싱
        String[] locationParts = request.addr().split(",");
        double latitude = Double.parseDouble(locationParts[0].trim());
        double longitude = Double.parseDouble(locationParts[1].trim());

        // DB에서 모든 가게를 가져와 서버에서 직접 필터링하는 방식으로 수정
        List<Restaurant> allRestaurants = restaurantRepository.findAll();

//        DB에서 가져온 가게들의 위도 경도가 잘 전달이 되는지 확인용 디버깅 코드(혹시 모르니 남겨놓음)
//        System.out.println("===== DB에서 가져온 가게 좌표 샘플 확인 =====");
//        allRestaurants.stream().limit(10).forEach(r ->
//                System.out.println(String.format("가게: %s, 위도: %f, 경도: %f",
//                        r.getRestaurantName(), r.getLatitude(), r.getLongitude()))
//        );
//        System.out.println("=========================================");

        List<Restaurant> nearbyRestaurants = allRestaurants.stream()
                .filter(restaurant -> {
                    double distance = DistanceCalc.calculateDistance(
                            latitude, longitude,
                            restaurant.getLatitude(), restaurant.getLongitude()
                    );
                    // 거리 계산 잘 되고있는지 디버깅용 코드 (혹시 모르니 남겨둠)
                    // System.out.println(String.format("거리 계산: %s까지 %.2f 미터", restaurant.getRestaurantName(), distance));
                    return distance <= 50; // 200미터 반경으로 필터링
                })
                .collect(Collectors.toList());


        // 주변 가게들의 리뷰 데이터 가져오기
        List<Review> nearbyReviews = new ArrayList<>();
        if (!nearbyRestaurants.isEmpty()) {
            List<Long> nearbyRestaurantIds = nearbyRestaurants.stream()
                    .map(Restaurant::getKakaoPlaceId)
                    .collect(Collectors.toList());
            nearbyReviews = reviewRepository.findAllByRestaurantKakaoPlaceIdInOrderByIdDesc(nearbyRestaurantIds);
        }


//        위에서 1차로 들어온 주변 가게의 리뷰들 잘 가져오는지 디버깅 코드(일단 남겨놓음)
//        System.out.println("===== 주변 가게 리뷰 " + nearbyReviews.size() + "건 조회됨 =====");
//        nearbyReviews.stream()
//                .forEach(review -> System.out.println(
//                        "[" + review.getRestaurant().getRestaurantName() + "] " + review.getContent()
//                ));
//        System.out.println("==========================================");


        try {
            // 1차 AI 호출: 주변 식당 가격 정보 수집
            AiPriceResponse priceData = getPriceDataFromAi(nearbyRestaurants);

            // 서버가 직접 점수 계산
            List<AnalysisResponse.ScoreInfo> scores = calculateAllScores(request, nearbyRestaurants, priceData);

            // 2차 AI 호출: 실제 분석 리포트 작성 요청
            String finalPrompt = createFinalReportPrompt(request, scores, priceData, nearbyReviews);
            String aiFinalResponseJson = aiChatService.getAnalysisResponseFromAI(finalPrompt);

            // AI 응답 정리 및 최종 반환
            String cleanJson = aiFinalResponseJson.replace("```json", "").replace("```", "").trim();
            return objectMapper.readValue(cleanJson, AnalysisResponse.class);

        } catch (IOException e) {
            e.printStackTrace();
            return new AnalysisResponse(List.of(), null, List.of(), null);
        }
    }

    private AiPriceResponse getPriceDataFromAi(List<Restaurant> nearbyRestaurants) throws JsonProcessingException {
        String nearbyInfo = nearbyRestaurants.stream()
                .map(r -> String.format("- 가게명: %s, 주소: %s", r.getRestaurantName(), r.getRoadAddress()))
                .limit(15)
                .collect(Collectors.joining("\n"));
        String prompt = String.format(
                """
                # 역할: 당신은 JSON 데이터만 생성하는 로봇입니다.
                # 임무: 아래 식당 목록을 보고, 각 식당의 대표 메뉴와 평균 1인분 가격을 추론하여 JSON으로 반환하세요.
                # 분석 대상 식당 목록:\n%s
                # 절대 규칙:
                - `price` 필드의 값은 반드시 따옴표 없는 숫자(Integer)여야 합니다. (예: 12000)
                - `restaurantName`, `representativeMenu` 필드의 값은 반드시 따옴표로 감싸진 문자열(String)이어야 합니다.
                - Markdown 코드 블록(```json)이나 다른 설명 없이, 순수한 JSON 텍스트만 응답해야 합니다.

                # 출력 형식 (JSON):
                {
                  "prices": [
                    { "restaurantName": "(가게 이름)", "representativeMenu": "(추론한 대표 메뉴)", "price": (추론한 1인분 가격) }
                  ]
                }
                """, nearbyInfo);

        String aiResponseJson = aiChatService.getAnalysisResponseFromAI(prompt);
        String cleanJson = aiResponseJson.replace("```json", "").replace("```", "").trim();
        return objectMapper.readValue(cleanJson, AiPriceResponse.class);
    }

    private List<AnalysisResponse.ScoreInfo> calculateAllScores(AnalysisRequest request, List<Restaurant> nearbyRestaurants, AiPriceResponse priceData) {
        List<AnalysisResponse.ScoreInfo> scores = new ArrayList<>();
        int nearbyCount = nearbyRestaurants.size();
        int locationScore = 50 + (nearbyCount * 2);
        if (locationScore > 95) locationScore = 95;
        scores.add(new AnalysisResponse.ScoreInfo("위치", locationScore, null, ""));

        OptionalDouble avgCompetitorPriceOpt = priceData.prices().stream()
                .mapToInt(AiPriceResponse.RestaurantPriceInfo::price)
                .average();
        int priceScore = 70;
        if (avgCompetitorPriceOpt.isPresent()) {
            double avgCompetitorPrice = avgCompetitorPriceOpt.getAsDouble();
            int userAvgPrice = (request.averagePrice().min() + request.averagePrice().max()) / 2;
            double priceRatio = userAvgPrice / avgCompetitorPrice;
            if (priceRatio > 1.0) {
                priceScore -= (priceRatio - 1.0) * 100;
            } else {
                priceScore += (1.0 - priceRatio) * 50;
            }
        }
        if (priceScore < 10) priceScore = 10;
        if (priceScore > 95) priceScore = 95;
        scores.add(new AnalysisResponse.ScoreInfo("가격", priceScore, null, ""));

        int budgetScore = 100 - (locationScore / 2);
        if (request.budget().max() > 200) budgetScore += 10;
        if (budgetScore > 95) budgetScore = 95;
        scores.add(new AnalysisResponse.ScoreInfo("예산 적합성", budgetScore, null, ""));
        return scores;
    }

    private String createFinalReportPrompt(AnalysisRequest request, List<AnalysisResponse.ScoreInfo> scores, AiPriceResponse priceData, List<Review> reviews) {
        String scoreInfoForPrompt = scores.stream()
                .map(s -> String.format("- %s: %d점", s.name(), s.score()))
                .collect(Collectors.joining("\n"));
        String priceInfoForPrompt = priceData.prices().stream()
                .map(p -> String.format("- %s(%s): %d원", p.restaurantName(), p.representativeMenu(), p.price()))
                .collect(Collectors.joining("\n"));

        String reviewInfoForPrompt;
        if (reviews.isEmpty()) {
            reviewInfoForPrompt = "분석에 참고할 만한 주변 가게의 리뷰 데이터가 없습니다.";
        } else {
            reviewInfoForPrompt = reviews.stream()
                    .limit(20)
                    .map(r -> String.format("- [%s] \"%s\" (평점: %s)",
                            r.getRestaurant().getRestaurantName(), r.getContent(), r.getRating().toString()))
                    .collect(Collectors.joining("\n"));
        }

        return String.format(
                """
                # 역할: 당신은 JSON 데이터만 생성하는 보고서 작성 로봇입니다.
                # 임무: 내가 제공하는 모든 데이터를 바탕으로, 완벽한 JSON 보고서를 완성해주세요.

                # 창업 희망 조건
                - 희망 위치: 좌표(%s)
                - 희망 업종: %s
                
                # 내가 분석한 주변 상권 데이터
                - 주변 가게 메뉴 및 가격:
                %s
                - 주변 가게 실제 고객 리뷰 (최대 20개):
                %s

                # 내가 계산한 최종 점수
                %s

                # 절대 규칙:
                - `score`, `monthly`, `securityDeposit` 필드의 값은 반드시 따옴표 없는 숫자(Integer)여야 합니다.
                - `expectedPrice` 객체 안의 값에는 절대 '만원', '원' 같은 단위를 포함하지 마세요.
                - 모든 텍스트 값은 반드시 따옴표로 감싸진 문자열(String)이어야 합니다.
                - Markdown 코드 블록(```json)이나 다른 설명 없이, 순수한 JSON 텍스트만 응답해야 합니다.

                # 지시사항
                1. '내가 계산한 최종 점수'의 각 항목에 대해 설득력 있는 '이유(reason)'를 작성해주세요.
                2. '예산 적합성' 점수에 대해 'expectedPrice' 객체에 예상 월세와 보증금을 추정하여 채워주세요.
                3. '주변 가게 실제 고객 리뷰'를 심층 분석하여 `reviewAnalysis` 객체를 생성해주세요.
                4. 모든 정보를 종합하여 날카로운 조언('tips')을 3가지 생성해주세요.

                # 출력 형식 (JSON):
                {
                  "scores": [
                    { "name": "위치", "score": %d, "expectedPrice": null, "reason": "이곳에 이유 작성..." },
                    { "name": "가격", "score": %d, "expectedPrice": null, "reason": "이곳에 이유 작성..." },
                    { "name": "예산 적합성", "score": %d, "expectedPrice": { "monthly": (숫자 월세), "securityDeposit": (숫자 보증금) }, "reason": "이곳에 이유 작성..." }
                  ],
                  "reviewAnalysis": {
                    "summary": "리뷰 데이터를 종합하여 요약문 작성...",
                    "positiveKeywords": ["키워드1", "키워드2"],
                    "negativeKeywords": ["키워드3", "키워드4"],
                    "reviewSamples": [
                      { "storeName": "(리뷰에 나온 가게 이름)", "reviewScore": 4.5, "highlights": ["리뷰에서 찾은 장점1", "장점2"] }
                    ]
                  },
                  "tips": [
                    { "type": "success", "message": "성공 요인 조언 작성..." },
                    { "type": "warning", "message": "주의할 점 조언 작성..." },
                    { "type": "info", "message": "참고 정보 조언 작성..." }
                  ]
                }
                """,
                request.addr(), request.category(),
                priceInfoForPrompt,
                reviewInfoForPrompt,
                scoreInfoForPrompt,
                scores.get(0).score(), scores.get(1).score(), scores.get(2).score()
        );
    }
}