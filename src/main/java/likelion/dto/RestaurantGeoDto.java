package likelion.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RestaurantGeoDto {
    private String name;
    private String address;
    private Double lat;
    private Double lon;
    private String coord;
}
