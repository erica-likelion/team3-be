package likelion.service.kakaoApi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;

import org.json.JSONObject;

@Component
public class KakaoApiService {

    @Value("${spring.kakao.api-key}")
    private String kakaoApiKey;

    public String getLocationByAddress(String address) {
        // 카카오 API URL
        String url = "https://dapi.kakao.com/v2/local/search/address.json?query=" + address;

        // HttpHeaders에 Authorization 헤더 추가
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "KakaoAK " + kakaoApiKey);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        // RestTemplate을 사용하여 API 호출
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

        // JSON 응답에서 위도, 경도 추출
        JSONObject jsonResponse = new JSONObject(response.getBody());

        // 만약 'documents' 배열에 결과가 없다면 기본 메시지 반환
        if (jsonResponse.getJSONArray("documents").length() == 0) {
            return "위치 정보를 찾을 수 없습니다.";
        }

        // 첫 번째 위치 정보에서 위도, 경도 추출
        JSONObject document = jsonResponse.getJSONArray("documents").getJSONObject(0);
        double latitude = document.getJSONObject("address").getDouble("y");
        double longitude = document.getJSONObject("address").getDouble("x");

        // 간단하게 위도, 경도만 반환
        return latitude + "," + longitude;
    }
}
