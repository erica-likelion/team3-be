package likelion.service;

import likelion.domain.entity.Restaurant;
import likelion.repository.RestaurantRepository;
import likelion.service.distance.DistanceCalc; // ★★★ 직접 계산을 위해 필요합니다 ★★★
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;


    public List<Restaurant> getFilteredRestaurant(double latitude, double longitude) {
        int radiusMeters = 150; // 반경은 일단 150
        return getRestaurantsWithinRadius(latitude, longitude, radiusMeters);
    }

    public List<Restaurant> getRestaurantsWithinRadius(double latitude, double longitude, int radius) {
        // DB에서 모든 음식점을 가져오기
        List<Restaurant> allRestaurants = restaurantRepository.findAll();

        // Java Stream을 사용하여 반경 내에 있는 음식점만 필터링
        return allRestaurants.stream()
                .filter(restaurant -> {
                    double distance = DistanceCalc.calculateDistance(
                            latitude, longitude,
                            restaurant.getLatitude(), restaurant.getLongitude()
                    );
                    return distance <= radius;
                })
                .collect(Collectors.toList());
    }
}