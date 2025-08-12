package likelion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import likelion.controller.dto.AnalysisRequest;
import likelion.controller.dto.AnalysisResponse;
import likelion.domain.entity.Restaurant;
import likelion.repository.RestaurantRepository;
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
    private final AiChatService aiChatService;
    private final ObjectMapper objectMapper;

    public AnalysisResponse analyze(AnalysisRequest request) {
        //addr 변수에 담긴 위도/경도 문자열을 직접 파싱
        String[] locationParts = request.addr().split(",");
        double latitude = Double.parseDouble(locationParts[0].trim());
        double longitude = Double.parseDouble(locationParts[1].trim());

        //해석한 위도, 경도를 사용해서 주변 가게를 찾기
        List<Restaurant> nearbyRestaurants = restaurantRepository.findRestaurantsWithinRadius(latitude, longitude, 50);

        try {
            // 1차 AI 호출: 주변 식당 가격 정보 수집
            AiPriceResponse priceData = getPriceDataFromAi(nearbyRestaurants);

            // 서버가 직접 점수 계산. (계산 로직 및 점수 기준은 바꿔야됨 지금 너무 엉터리 ㅋ.)
            List<AnalysisResponse.ScoreInfo> scores = calculateAllScores(request, nearbyRestaurants, priceData);

            // 2차 AI 호출: 실제 분석
            String finalPrompt = createFinalReportPrompt(request, scores, priceData);
            String aiFinalResponseJson = aiChatService.getAnalysisResponseFromAI(finalPrompt);

            // AI 응답 정리 및 최종 반환
            String cleanJson = aiFinalResponseJson.replace("```json", "").replace("```", "").trim();
            return objectMapper.readValue(cleanJson, AnalysisResponse.class);

        } catch (IOException e) {
            e.printStackTrace();
            return new AnalysisResponse(List.of(), List.of());
        }
    }

    private AiPriceResponse getPriceDataFromAi(List<Restaurant> nearbyRestaurants) throws JsonProcessingException {
        String nearbyInfo = nearbyRestaurants.stream()
                .map(r -> String.format("- 가게명: %s, 주소: %s", r.getRestaurantName(), r.getRoadAddress()))
                .limit(15)
                .collect(Collectors.joining("\n"));
        String prompt = String.format(
                """
                # 역할: 당신은 상권 데이터 분석가입니다.
                # 임무: 아래 식당 목록을 보고, 각 식당의 대표 메뉴와 평균적인 1인분 가격을 추론하여 JSON으로 반환하세요.
                # 분석 대상 식당 목록:\n%s
                # 지시사항: 가격은 반드시 숫자로만 제공하고, 정보가 부족하면 목록에서 제외하세요. 다른 설명 없이, 순수한 JSON 텍스트만 반환해야 합니다.
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

    private String createFinalReportPrompt(AnalysisRequest request, List<AnalysisResponse.ScoreInfo> scores, AiPriceResponse priceData) {
        String scoreInfoForPrompt = scores.stream()
                .map(s -> String.format("- %s: %d점", s.name(), s.score()))
                .collect(Collectors.joining("\n"));
        String priceInfoForPrompt = priceData.prices().stream()
                .map(p -> String.format("- %s(%s): %d원", p.restaurantName(), p.representativeMenu(), p.price()))
                .collect(Collectors.joining("\n"));
        return String.format(
                """
                # 역할: 당신은 뛰어난 상권 분석 보고서 작성가입니다.
                # 임무: 내가 계산한 점수와 데이터를 바탕으로, 각 점수에 대한 설득력 있는 '이유(reason)'와 최종 '조언(tips)'을 작성하여 완벽한 JSON 보고서를 완성해주세요.

                # 창업 희망 조건
                - 희망 위치: 좌표(%s)
                - 희망 업종: %s
                - 희망 월세 예산: %d만원 ~ %d만원
                - 희망 메뉴 평균 가격: %d원 ~ %d원

                # 내가 분석한 주변 상권의 대표 메뉴 및 가격
                %s

                # 내가 계산한 최종 점수
                %s

                # 지시사항
                1. '내가 계산한 최종 점수'의 각 항목에 대해 설득력 있는 '이유(reason)'를 작성해주세요.
                2. '예산 적합성' 점수에 대해 'expectedPrice' 객체에 예상 월세와 보증금을 **반드시 숫자(Integer)로만** 추정하여 채워주세요.
                3. 모든 정보를 종합하여 날카로운 조언('tips')을 3가지 생성해주세요.
                4. 최종 결과물을 아래 JSON 형식에 맞춰서 반환해주세요. 순수한 JSON 텍스트만 반환해야 합니다.

                # 출력 형식 (JSON)
                {
                  "scores": [
                    { "name": "위치", "score": %d, "expectedPrice": null, "reason": "이곳에 이유 작성..." },
                    { "name": "가격", "score": %d, "expectedPrice": null, "reason": "이곳에 이유 작성..." },
                    { "name": "예산 적합성", "score": %d, "expectedPrice": { "monthly": (추정 월세), "securityDeposit": (추정 보증금) }, "reason": "이곳에 이유 작성..." }
                  ],
                  "tips": [
                    { "type": "success", "message": "성공 요인 조언 작성..." },
                    { "type": "warning", "message": "주의할 점 조언 작성..." },
                    { "type": "info", "message": "참고 정보 조언 작성..." }
                  ]
                }
                """,
                request.addr(), request.category(), request.budget().min(), request.budget().max(),
                request.averagePrice().min(), request.averagePrice().max(),
                priceInfoForPrompt,
                scoreInfoForPrompt,
                scores.get(0).score(), scores.get(1).score(), scores.get(2).score()
        );
    }
}