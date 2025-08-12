package likelion.repository;

import likelion.domain.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByRestaurant_KakaoPlaceIdAndSourceReviewId(Long placeId, String sourceId);
}

