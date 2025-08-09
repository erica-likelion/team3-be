package likelion.kakaomapData;

import likelion.domain.entity.Restaurant;
import likelion.repository.RestaurantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional // 테스트 후 롤백
class KakaoMapDataInputTest {

    @Autowired
    RestaurantRepository repository;

    @Test
    @DisplayName("Restaurant 저장/조회 기본 동작")
    void saveAndFind() {
        // given
        Restaurant r = new Restaurant();   // 기본 생성자 + setter 사용
        r.setRestaurantName("테스트 식당");
        r.setCategory("한식");
        r.setRoadAddress("경기 안산시 상록구 학사1길 1");
        r.setNumberAddress("사동 111-1");
        r.setKakaoUrl("https://place.map.kakao.com/123456");
        r.setKakaoPlaceId(123456L);               // Long/nullable 권장
        r.setBusinessTime("09:00~21:00");
        r.setReviewCount(10);                      // Integer(래퍼) 권장
        r.setRatingCount(5);
        r.setRating(new BigDecimal("4.5"));       // BigDecimal 권장

        // when
        repository.save(r);

        // then
        List<Restaurant> all = repository.findAll();
        assertThat(all).isNotEmpty();
        assertThat(all.get(0).getName()).isEqualTo("테스트 식당");
    }
}
