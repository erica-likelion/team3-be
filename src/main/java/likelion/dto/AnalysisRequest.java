package likelion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 프론트엔드에서 상권 분석을 요청할 때 사용하는 DTO
 * 유저의 창업 조건을 담기
 */
public record AnalysisRequest(
        @NotBlank(message = "주소는 필수입니다.")
        String addr, // "37.297,126.837" 같은 위경도 문자열

        @NotBlank(message = "카테고리 선택은 필수입니다.")
        String category, // "중식","카페"
        String marketingArea, // 대학가/학교 주변

        @NotNull(message = "예산은 필수입니다.")
        MinMax budget, // 만원. 월세 예산
        @NotNull(message = "예산 필수입니다.")
        MinMax deposit, // 만원. 보증금 예산
        @NotBlank(message = "영업방식은 필수입니다.")
        String managementMethod, // "홀 영업 위주"
        @NotBlank(message = "대표메뉴는 필수입니다.")
        String representativeMenuName, // 추가: 대표 메뉴명
        @NotNull(message = "가격은 필수입니다.")
        Integer representativeMenuPrice, // 추가: 대표 메뉴 가격 (원)
        MinMax size, // 평
        Integer height // 층
){
    /**
     * 최소값과 최대값으로 범위를 나타내기 위해 만든 것
     * budget, averagePrice, size 필드에서 사용
     */
    public record MinMax(Integer min, Integer max) {}
}