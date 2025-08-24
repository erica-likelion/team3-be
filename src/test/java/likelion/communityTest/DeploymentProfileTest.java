package likelion.communityTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import likelion.domain.entity.Category;
import likelion.dto.PostCreateRequestDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("blue") // 클래스 레벨에서 'blue' 프로필 활성화
@TestPropertySource(properties = { "file.upload.dir=./build/test-deploy-images/" }) // 파일 경로만 테스트용으로 오버라이드
public class DeploymentProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("배포 프로필(blue) 컨텍스트 로딩 및 API 테스트")
    void testDeploymentContextLoadsAndApiWorks() throws Exception {
        // given
        PostCreateRequestDto dto = new PostCreateRequestDto();
        dto.setTitle("배포 설정 테스트");
        dto.setContent("내용");
        dto.setCategory(Category.GENERAL);

        MockMultipartFile jsonDto = new MockMultipartFile(
                "dto", "", "application/json", objectMapper.writeValueAsBytes(dto)
        );

        MockMultipartFile image = new MockMultipartFile(
                "image", "test-deploy.png", "image/png", "dummy".getBytes()
        );

        // when & then
        mockMvc.perform(
                        multipart("/api/post")
                                .file(jsonDto)
                                .file(image)
                )
                .andExpect(status().isCreated());
    }
}
