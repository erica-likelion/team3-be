package likelion.kakaoApiTest;

import likelion.domain.entity.Restaurant;
import likelion.jsondata.mapper.RestaurantMapper;
import likelion.jsondata.record.RestaurantJson;
import likelion.repository.RestaurantRepository;
import likelion.service.kakaoApi.KakaoApiService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
public class RestaurantMapperTest {

    @Autowired
    RestaurantRepository repository;

    @Autowired
    RestaurantMapper mapper;

    @Mock
    KakaoApiService kakaoApiService;

    private Restaurant newR(Long id, String name) {
        Restaurant r = new Restaurant();
        r.setKakaoPlaceId(id);               // @Id
        r.setRestaurantName(name);
        r.setCategory("카페");
        r.setRoadAddress("주소");
        r.setNumberAddress("지번");
        r.setKakaoUrl("https://place.map.kakao.com/" + id);
        r.setBusinessTime("매일 10:00 ~ 22:00");
        r.setRating(new BigDecimal("4.4"));
        r.setRatingCount(7);
        r.setReviewCount(6);
        return r;
    }

    @Test
    @DisplayName("Mapper가 카카오 API를 사용하여 위도, 경도 매핑 테스트")
    void testMapWithLocationFromKakaoApi() {
        // Given
        RestaurantJson restaurantJson = new RestaurantJson(
                "테스트 식당", "한식", "4.5", "100", "50",
                "경기 안산시 상록구 댕이길 67-1 1층", "지번주소", "10:00-22:00",
                "https://place.map.kakao.com/123456"
        );

        // 카카오 API에서 위도, 경도를 받아오는 부분 Mocking
        when(kakaoApiService.getLocationByAddress("경기 안산시 상록구 댕이길 67-1 1층"))
                .thenReturn("37.2942460913892, 126.844033212535"); // 위도, 경도 Mock 반환

        // When
        Restaurant restaurant = mapper.map(restaurantJson);

        // Then
        assertThat(restaurant).isNotNull();
        assertThat(restaurant.getRestaurantName()).isEqualTo("테스트 식당");
        assertThat(restaurant.getCategory()).isEqualTo("한식");
        assertThat(restaurant.getRating()).isEqualByComparingTo(new BigDecimal("4.5"));
        assertThat(restaurant.getRatingCount()).isEqualTo(100);
        assertThat(restaurant.getReviewCount()).isEqualTo(50);
        assertThat(restaurant.getRoadAddress()).isEqualTo("경기 안산시 상록구 댕이길 67-1 1층");
        assertThat(restaurant.getLatitude()).isEqualTo(37.2942460913892);
        assertThat(restaurant.getLongitude()).isEqualTo(126.844033212535);
    }
}
