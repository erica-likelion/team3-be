package likelion.kakaomapData;

import likelion.domain.entity.Restaurant;
import likelion.jsondata.mapper.RestaurantMapper;
import likelion.jsondata.record.RestaurantJson;
import likelion.repository.RestaurantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

@SpringBootTest
@Transactional // 테스트 후 롤백
class KakaoMapDataInputTest {

    @Autowired
    RestaurantRepository repository;

    @Autowired
    RestaurantMapper mapper;

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

    @Test
    @DisplayName("같은 kakaoPlaceId로 2번 save해도 row는 1개(두 번째가 UPDATE)")
    void duplicateId_savedOnce() {
        // given
        Long dupId = 243846109L;
        var first  = newR(dupId, "코이노커피");
        var second = newR(dupId, "코이노커피(중복)");

        // when
        repository.save(first);      // INSERT
        repository.save(second);     // UPDATE(merge)
        repository.flush();

        // then
        assertThat(repository.count()).isEqualTo(1L);
        var found = repository.findById(dupId).orElseThrow();
        assertThat(found.getName()).isEqualTo("코이노커피(중복)");
    }

    @Test
    @DisplayName("Mapper 경로로도 중복 저장 시 최종 1건 + 마지막 값으로 갱신")
    void duplicateViaMapper_updatesLastOne() {
        // given
        RestaurantJson j1 = new RestaurantJson(
                "코이노커피", "카페", "5.0", "23", "155",
                "도로", "지번", "영업",
                "https://place.map.kakao.com/243846109"
        );
        RestaurantJson j2 = new RestaurantJson(
                "코이노커피(중복)", "카페", "4.0", "10", "100",
                "도로", "지번", "영업",
                "https://place.map.kakao.com/243846109" // 동일 ID
        );

        var r1 = mapper.map(j1);
        var r2 = mapper.map(j2);

        // when
        repository.save(r1);         // INSERT
        repository.save(r2);         // UPDATE
        repository.flush();

        // then
        assertThat(repository.count()).isEqualTo(1L);
        var found = repository.findById(243846109L).orElseThrow();
        assertThat(found.getName()).isEqualTo("코이노커피(중복)");
        assertThat(found.getRating()).isEqualByComparingTo(new BigDecimal("4.0")); // 마지막 값으로 덮였는지 체크
    }
}
