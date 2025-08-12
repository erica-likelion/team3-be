package likelion.jsondata.mapper;

import likelion.domain.entity.Restaurant;
import likelion.domain.entity.Review;
import likelion.jsondata.record.ReviewJson;
import likelion.repository.RestaurantRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class ReviewMapper {
    private final RestaurantRepository restaurantRepository;

    public ReviewMapper(RestaurantRepository restaurantRepository) {
        this.restaurantRepository = restaurantRepository;
    }

    public Review map(ReviewJson j) {
        Long placeId = makeKakaoId(j.review_url());
        Restaurant restaurant = restaurantRepository.getReferenceById(placeId);

        Review r = new Review();
        r.setRestaurant(restaurant);
        r.setSourceReviewId(makeDeterministicId(j));
        r.setRating(parseDecimal(j.review_rating()));
        r.setContent(nvl(j.review_content()));
        return r;
    }

    private Long makeKakaoId(String url){
        if (url == null) throw new IllegalArgumentException("review_url is null");
        var m = java.util.regex.Pattern
                .compile("place\\.map\\.kakao\\.com/(\\d+)")
                .matcher(url);
        if (!m.find()) throw new IllegalArgumentException("Invalid kakao url: " + url);
        return Long.valueOf(m.group(1));
    }


    private String makeDeterministicId(ReviewJson j) {
        String base = (nvl(j.review_content()) + "||" + nvl(j.review_url())).trim();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(base.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception e) {
            return Integer.toHexString(base.hashCode());
        }
    }
    private String nvl(String s){ return (s == null || s.isBlank()) ? "_" : s.trim(); }


    private BigDecimal parseDecimal(String s){
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        s = s.replaceAll("[^0-9.]", ""); // "4.7점" 같은 경우 대비
        if (s.isEmpty()) return null;
        return new BigDecimal(s);
    }

}
