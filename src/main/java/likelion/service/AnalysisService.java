package likelion.service;


import likelion.controller.dto.AnalysisRequest;
import likelion.controller.dto.AnalysisResponse;
import likelion.domain.entity.Restaurant;
import likelion.repository.RestaurantRepository;
import likelion.service.kakaoApi.KakaoApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final RestaurantRepository restaurantRepository;
    private final KakaoApiService kakaoApiService;

    public AnalysisResponse analyze(AnalysisRequest request) {
        //주소->좌표 변환 먼저
        String address = request.addr();
        String locationString = kakaoApiService.getLocationByAddress(address);

        //좌표로 위경도 얻기
        String[] locationParts = locationString.split(",");
        double latitude = Double.parseDouble(locationParts[0].trim());
        double longitude = Double.parseDouble(locationParts[1].trim());

        // repository에서 주변 식당 필터링해서 리스트 받아오기. radius는 조절하면서 알맞은 거리 찾아보기
        int radius = 50;
        List<Restaurant> competitors = restaurantRepository.findRestaurantsWithinRadius(latitude, longitude, radius);

        //필터링 잘 되는지 콘솔 확인용
        System.out.println("===== 주변 경쟁사 필터링 결과 =====");
        for (Restaurant competitor : competitors) {
            System.out.println("가게 이름: " + competitor.getRestaurantName());
        }
        System.out.println("===================================");

        //아직 ai연동 안 해서 null 반환
        return new AnalysisResponse(List.of(), List.of()); // 지금은 ScoreInfo,Tip 2개만 dto에서 반환하지만 리뷰 분석 부분은 나중에 추가하면 여기도 추가
    }


}
