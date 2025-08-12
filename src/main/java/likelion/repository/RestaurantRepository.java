package likelion.repository;

import likelion.domain.entity.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;


public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {
    /**
     * 인터페이스
     */
    @Query(value = """
        SELECT * FROM restaurant r
        WHERE ST_Distance_Sphere(POINT(r.longitude, r.latitude), POINT(:longitude, :latitude)) <= :radius
        """, nativeQuery = true)
    List<Restaurant> findRestaurantsWithinRadius(@Param("latitude") double latitude,
                                                 @Param("longitude") double longitude,
                                                 @Param("radius") int radius);
}
