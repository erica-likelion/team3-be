package likelion.service;

import likelion.domain.entity.Restaurant;
import likelion.dto.RestaurantGeoDto;
import likelion.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RestaurantGeoService {

    private final RestaurantRepository restaurantRepository;

    @Transactional(readOnly = true)
    public List<RestaurantGeoDto> listAll() {
        return restaurantRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    private RestaurantGeoDto toDto(Restaurant r) {
        String road = Optional.ofNullable(r.getRoadAddress()).orElse("");
        String jibun = Optional.ofNullable(r.getNumberAddress()).orElse("");
        String address = road.isBlank() ? jibun : road;

        Double lat = r.getLatitude();
        Double lon = r.getLongitude();
        String coord = (lat != null && lon != null) ? String.format("%.6f, %.6f", lat, lon) : null;

        return new RestaurantGeoDto(
                Optional.ofNullable(r.getRestaurantName()).orElse(""),
                address, lat, lon, coord
        );
    }
}