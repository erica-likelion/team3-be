package likelion.service;

import likelion.domain.entity.Restaurant;
import likelion.repository.RestaurantRepository;
import likelion.service.distance.DistanceCalc;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;

    public List<Restaurant> getFilteredRestaurant(double latitude, double longitude) {
        int radiusMeters = 50;
        return restaurantRepository.findRestaurantsWithinRadius(latitude, longitude, radiusMeters);

    }
}
