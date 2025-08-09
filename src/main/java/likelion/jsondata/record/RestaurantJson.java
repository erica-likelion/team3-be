package likelion.jsondata.record;

public record RestaurantJson(
        String 가게이름, String 카테고리, String 평점,
        String 평점건수, String 리뷰수, String 주소,
        String 지번, String 영업시간,
        String 상세정보링크
) { }
