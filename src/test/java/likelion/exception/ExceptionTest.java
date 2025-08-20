package likelion.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 통합 예외 처리 테스트
 * - 스프링 컨텍스트 전체 로드(@SpringBootTest)
 * - MockMvc로 실제 엔드포인트 호출
 *
 * 전제:
 *  - /api/analysis POST JSON 엔드포인트가 존재
 *  - GlobalExceptionHandler가 @RestControllerAdvice로 등록되어 있음
 *  - 컨트롤러에서 @Valid @RequestBody AnalysisRequest 사용 (DTO에 제약조건 존재)
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExceptionTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("400 - 빈 본문(JSON) 요청")
    void missingRequired_returns400() throws Exception {
        String body = "{}"; // 필수 항목 누락 -> @Valid 검증 실패 기대

        mvc.perform(post("/api/analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        // 필요하면 응답 바디 검증 추가:
        // .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    @DisplayName("400 - 깨진 거 JSON")
    void malformedJson_returns400() throws Exception {
        mvc.perform(post("/api/analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[]{123})) // { 하나만 보내. 꺠진거처럼 ㅋㅋ
                .andExpect(status().isBadRequest());
        // .andExpect(jsonPath("$.title").value("Malformed JSON"));
    }

    @Test
    @DisplayName("415 - 지원하지 않는 Content-Type")
    void unsupportedMediaType_returns415() throws Exception {
        mvc.perform(post("/api/analysis")
                        .contentType(MediaType.TEXT_PLAIN) // JSON 아님
                        .content("addr=37.297,126.837"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("405 - 허용되지 않은 메서드(GET 호출)")
    void methodNotAllowed_returns405() throws Exception {
        mvc.perform(get("/api/analysis"))
                .andExpect(status().isMethodNotAllowed());
    }
}