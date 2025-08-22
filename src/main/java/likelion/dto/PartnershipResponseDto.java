package likelion.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "주변 제휴 추천매장 응답")
public record PartnershipResponseDto(
        @Schema(description = "타겟 매장 이름")
        String targetStoreName,

        @Schema(description = "업종")
        String targetCategory,

        @Schema(description = "주변 추천 제휴 후보 2개")
        List<PartnerInfo> partners,

        @Schema(description = "이벤트")
        List<EventSuggestion> events
) {

    /**
     * 제휴 후보
     */
    public record PartnerInfo(
            @Schema(description = "제휴 후보 매장")
            String name,

            @Schema(description = "매장 업종")
            String category,

            @Schema(description = "타겟 매장과의 거리(m)")
            int distanceMeters,

            @Schema(description = "상세정보 링크(카카오 맵 url")
            String kakaomapUrl,

            @Schema(description = "주소(도로명 없으면 지번)")
            String address
    ){ }

    /**
     * 이벤트 제안
     */
    public record EventSuggestion(
            @Schema(description = "이벤트 제목")
            String eventTitle,

            @Schema(description = "이벤트 상세 설명")
            String description,

            @Schema(description = "해당 이벤트를 추천하는 이유")
            String reason
    ){ }

}
