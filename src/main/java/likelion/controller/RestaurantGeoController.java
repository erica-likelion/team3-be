package likelion.controller;

import likelion.dto.RestaurantGeoDto;
import likelion.service.RestaurantGeoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/restaurants")
public class RestaurantGeoController {

    private final RestaurantGeoService restaurantGeoService;

    @GetMapping(produces = "application/json")
    public ResponseEntity<List<RestaurantGeoDto>> getAllRestaurants() {
        return ResponseEntity.ok(restaurantGeoService.listAll());
    }
}