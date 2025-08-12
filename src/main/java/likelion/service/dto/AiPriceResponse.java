package likelion.service.dto;

import java.util.List;


// AI에게 주변 가게들의 가격 정보를 물어봤을 때, 그 응답을 담기 위한 DTO
public record AiPriceResponse(
        List<RestaurantPriceInfo> prices
) {
    public record RestaurantPriceInfo(
            String restaurantName,
            String representativeMenu,
            Integer price
    ) {}
}