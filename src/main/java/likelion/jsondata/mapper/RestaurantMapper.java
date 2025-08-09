package likelion.jsondata.mapper;

import likelion.domain.entity.Restaurant;
import likelion.jsondata.record.RestaurantJson;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class RestaurantMapper {
    public Restaurant map(RestaurantJson j){
        Restaurant r = new Restaurant();
        r.setRestaurantName(nvl(j.가게이름()));
        r.setCategory(j.카테고리());
        r.setBusinessTime(j.영업시간());
        r.setRatingCount(parseInt(j.평점건수()));
        r.setRating(parseDecimal(j.평점()));
        r.setReviewCount(parseInt(j.리뷰수()));
        r.setKakaoUrl(j.상세정보링크());
        r.setNumberAddress(j.지번());
        r.setRoadAddress(j.주소());
        r.setKakaoPlaceId(makeKakaoId(j.상세정보링크()));

        return r;
    }

    private Long makeKakaoId(String url){
        return Long.valueOf(url.replaceAll("\\D+",""));
    }

    private BigDecimal parseDecimal(String s){
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        s = s.replaceAll("[^0-9.]", ""); // "4.7점" 같은 경우 대비
        if (s.isEmpty()) return null;
        return new BigDecimal(s);
    }

    private Integer parseInt(String s){
        if (s == null) return null;
        s = s.replaceAll("\\D+", ""); // "(30)" → "30"
        return s.isEmpty() ? null : Integer.valueOf(s);
    }

    private String nvl(String s){ return (s == null || s.isBlank()) ? null : s.trim(); }
}
