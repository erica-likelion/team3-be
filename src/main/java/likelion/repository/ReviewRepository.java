package likelion.repository;

import likelion.domain.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    @Query("""
        select v
        from Review v
        where v.restaurant.kakaoPlaceId in :placeIds
        order by v.id desc
    """)
    List<Review> findAllByRestaurantKakaoPlaceIdInOrderByIdDesc(@Param("placeIds") Collection<Long> placeIds);
}

