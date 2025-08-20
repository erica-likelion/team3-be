package likelion.cors_setting;

import likelion.Team3BeApplication;
import likelion.config.CorsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {
        Team3BeApplication.class,
        CorsIntegrationTest.DummyController.class, // ✅ 테스트 전용 컨트롤러 포함
        CorsConfig.class                            // ✅ CORS 설정 포함(패키지 스캔 못 잡을 경우 대비)
})
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "cors.allowed-origins=http://localhost:3000,http://localhost:5173"
})
class CorsIntegrationTest {

    @Autowired MockMvc mvc;

    @RestController
    static class DummyController {
        @GetMapping("/test/ping")
        public String ping() { return "ok"; }
    }

    @Test
    void preflight_OPTIONS_should_return_cors_headers() throws Exception {
        mvc.perform(
                        options("/test/ping")
                                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type")
                )
                // 일부 환경은 204(No Content)일 수 있음 → 필요하면 isNoContent()로 변경
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("GET")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("Content-Type")))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void allowed_origin_GET_should_include_cors_headers() throws Exception {
        mvc.perform(get("/test/ping").header(HttpHeaders.ORIGIN, "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void disallowed_origin_GET_should_be_403() throws Exception {
        mvc.perform(get("/test/ping").header(HttpHeaders.ORIGIN, "http://evil.com"))
                .andExpect(status().isForbidden()) // ✅ 200이 아니라 403이 정상
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(content().string(containsString("Invalid CORS request")));
    }
}