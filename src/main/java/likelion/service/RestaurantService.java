package likelion.service;

import likelion.domain.entity.Restaurant;
import likelion.repository.RestaurantRepository;
import likelion.service.distance.DistanceCalc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RestaurantService {

    @Autowired
    private RestaurantRepository restaurantRepository;

    public List<Restaurant> getRestaurantsWithinRadius(double latitude, double longitude) {
        // 모든 음식점 조회
        List<Restaurant> allRestaurants = restaurantRepository.findAll();

        // 300m 이내에 있는 음식점만 필터링
        return allRestaurants.stream()
                .filter(restaurant -> {
                    double distance = DistanceCalc.calculateDistance(
                            latitude, longitude,
                            restaurant.getLatitude(), restaurant.getLongitude()
                    );
                    return distance <= 50; // 300m 이내인 경우만
                })
                .collect(Collectors.toList());
    }
}
