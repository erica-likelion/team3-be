package likelion.kakaoApiTest;

import likelion.service.kakaoApi.KakaoApiService;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class KakaoApiServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private KakaoApiService kakaoApiService;

    @Value("${spring.kakao.api-key}")
    private String kakaoApiKey;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetLocationByAddress() {
        // 카카오 API 응답 Mock 설정
        String address = "서울특별시 강남구 테헤란로 112";
        String jsonResponse = "{ \"documents\": [ { \"address\": { \"x\": 127.0461234, \"y\": 37.5073798 } } ] }";

        // RestTemplate의 exchange 메소드가 특정 URL에 대해 위 응답을 반환하도록 설정
        when(restTemplate.exchange(
                "https://dapi.kakao.com/v2/local/search/address.json?query=" + address,
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class))
                .thenReturn(ResponseEntity.ok(jsonResponse));

        // 메소드 호출 및 결과 검증
        String result = kakaoApiService.getLocationByAddress(address);
        assertEquals("위도: 37.5073798, 경도: 127.0461234", result);
    }
}