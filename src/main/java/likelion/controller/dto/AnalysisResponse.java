package likelion.controller.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public record AnalysisResponse(
        List<ScoreInfo> scores,
        ReviewAnalysis reviewAnalysis,
        List<Tip> tips
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

    public record ReviewAnalysis(
            String summary,
            List<String> positiveKeywords,
            List<String> negativeKeywords,
            List<ReviewSample> reviewSamples
    ) {}

    public record ReviewSample(
            String storeName,
            double reviewScore,
            List<String> highlights
    ) {}

    public record Tip(
            String type,
            String message
    ) {}
}