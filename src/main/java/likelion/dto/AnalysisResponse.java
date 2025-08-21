package likelion.dto;

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

    // 상세 분석(각 항목들 상세분석 리스트로)
    public record DetailAnalysis(
            List<DetailSection> sections
    ) {}


    // 각 상세 분석 내용들
    public record DetailSection(
            String name,
            String content
    ) {}
}