package likelion.controller.dto;

import java.util.List;

public record AnalysisResponse(
        // "접근성", "가격" 등 각 항목별 점수 정보 리스트
        List<ScoreInfo> scores,

        // 주변 경쟁 가게들의 리뷰를 분석한  (추후 추가 예정)
        // ReviewAnalysis reviewAnalysis,

        // 성공/경고/정보 메시지 리스트
        List<Tip> tips
) {
    public record ScoreInfo(
            String name,
            int score,
            String reason
    ) {}

//    나중에 리뷰 데이터 받으면 주석 풀고 다듬어서 만들겠습니다
//    public record ReviewAnalysis(
//            String summary,
//            List<String> positiveKeywords,
//            List<String> negativeKeywords
//    ) {}

    public record Tip(
            String type, // "success", "warning", "info" 등 팁의 종류랄까.. 노션 api명세서 response부분 보시면 뭔지 알겁니다!
            String message
    ) {}
}