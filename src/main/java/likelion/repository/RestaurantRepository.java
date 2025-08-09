package likelion.repository;

import likelion.domain.entity.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;


public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {
    /**
     * 인터페이스
     */
}
