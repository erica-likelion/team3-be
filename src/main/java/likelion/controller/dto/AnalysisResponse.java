package likelion.controller.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public record AnalysisResponse(
        List<ScoreInfo> scores,
        ReviewAnalysis reviewAnalysis,

        // 분석 상세보기를 위한 dto 추가
        DetailAnalysis detailAnalysis
) {
    @JsonInclude(JsonInclude.Include.NON_NULL) // expectedPrice가 예산 적합성에만 쓰이는데 나머지는 null임. 그래서 null인 것들은 안 보여주기 위한 어노테이션
    public record ScoreInfo(
            String name,
            int score,
            ExpectedPrice expectedPrice,
            String reason
    ) {}

    public record ExpectedPrice(
            Integer monthly,
            Integer securityDeposit
    ) {}

    // 피그마 내용 바탕으로 수정
    public record ReviewAnalysis(
            Double averageRating,             // 유사업종 평균 평점 (리뷰가 없으면 null)
            List<ReviewSample> reviewSamples, // 대표 리뷰
            String feedback // 리뷰 피드백
    ) {}

    public record ReviewSample(
            String storeName,
            double reviewScore,
            List<String> highlights
    ) {}

    //상세 분석 정보를 담을 record추가(경쟁업체_리스트로, 해당 상권 소비자 특성 분석)
    public record DetailAnalysis(
            List<CompetitorInfo> competitors,
            String consumerProfileAnalysis
    ) {}

    //경쟁업체들의 정보를 담을 record
    public record CompetitorInfo(
            String storeName,
            String category,
            String address,
            double rating,
            int reviewCount,
            int distance, // 사용자가 입력한 위치로부터의 거리
            String representativeMenu, // 대표메뉴랑 가격은 ai한테 맡기기
            int estimatedPrice
    ) {}
}