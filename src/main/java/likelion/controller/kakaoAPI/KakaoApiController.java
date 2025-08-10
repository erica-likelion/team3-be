package likelion.controller.kakaoAPI;

import likelion.service.kakaoApi.KakaoApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KakaoApiController {
    @Autowired
    private KakaoApiService kakaoApiService;

    @GetMapping("/test/kakao")
    public String getKakaoLocation(@RequestParam String address) {
        return kakaoApiService.getLocationByAddress(address);
    }
}
