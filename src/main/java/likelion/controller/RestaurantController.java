package likelion.controller;
import likelion.service.RestaurantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RestaurantController {
    /**
     * 위도경도 정보만 받아서
     * 해당 인근 150미터 지점 음식점 리스트 반환하는
     * 테스트 용이에요. 수정하셔서 쓰시면 됩니다.
     * 새로 만드셔도 됩니다. 편하신대로!
     */
    @Autowired
    private RestaurantService restaurantService;

//    @GetMapping("analysis")
//    public List<Restaurant> getRestaurantsWithinRadius(
//            @RequestParam double latitude,
//            @RequestParam double longitude) {
//        return restaurantService.getRestaurantsWithinRadius(latitude, longitude);
//    }
}
