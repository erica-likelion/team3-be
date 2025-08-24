package likelion.communityTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import likelion.domain.entity.Post;
import likelion.dto.PostCreateRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;


import likelion.domain.entity.Category;
import likelion.repository.PostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultHandlers;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("local")
@SpringBootTest
@AutoConfigureMockMvc
public class PostControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired PostRepository postRepository;
    @Autowired ObjectMapper objectMapper;

    private final Path imageDir = Paths.get("images").toAbsolutePath();

    @BeforeEach
    void setUp() {
        postRepository.deleteAll();
        postRepository.save(Post.builder().title("제목 1").content("내용 1").category(Category.GENERAL).build());
        postRepository.save(Post.builder().title("제목 2").content("내용 2").category(Category.PARTNERSHIP).build());
    }

    @AfterEach
    void cleanUp() throws IOException {
        // 테스트 시 생성한 이미지 파일 정리 (디렉토리가 존재하면 파일만 삭제)
        if (Files.exists(imageDir)) {
            try (Stream<Path> paths = Files.list(imageDir)) {
                paths.forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
        postRepository.deleteAll(); // DB 롤백과 별도 정리(확실히)
    }

    @Test
    @DisplayName("게시글 생성 - 이미지 포함")
    void create_with_image() throws Exception {
        // given
        PostCreateRequestDto dto = new PostCreateRequestDto();
        dto.setTitle("테스트 제목");
        dto.setContent("테스트 내용");
        dto.setCategory(Category.GENERAL);

        MockMultipartFile jsonDto = new MockMultipartFile(
                "dto", "", "application/json", objectMapper.writeValueAsBytes(dto)
        );

        MockMultipartFile image = new MockMultipartFile(
                "image", "test.png", "image/png", "dummy".getBytes()
        );

        // when & then
        mockMvc.perform(
                        multipart("/api/post")
                                .file(jsonDto)
                                .file(image)
                )
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/posts/")))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andDo(MockMvcResultHandlers.print());
    }

    @Test
    @DisplayName("게시글 생성 - 이미지 없이")
    void create_without_image() throws Exception {
        // given
        PostCreateRequestDto dto = new PostCreateRequestDto();
        dto.setTitle("이미지 없음");
        dto.setContent("본문");
        dto.setCategory(Category.PARTNERSHIP);

        MockMultipartFile jsonDto = new MockMultipartFile(
                "dto", "", "application/json", objectMapper.writeValueAsBytes(dto)
        );

        // when & then
        mockMvc.perform(
                        multipart("/api/post")
                                .file(jsonDto)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andDo(MockMvcResultHandlers.print());
    }

    @Test
    @DisplayName("모든 게시글 조회")
    void get_all_posts() throws Exception {
        mockMvc.perform(get("/api/post"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].title", is("제목 1")))
                .andExpect(jsonPath("$[1].title", is("제목 2")))
                .andDo(MockMvcResultHandlers.print());
    }

    @Test
    @DisplayName("ID로 게시글 조회")
    void get_post_by_id() throws Exception {
        Post post = postRepository.findAll().get(0);

        mockMvc.perform(get("/api/post/" + post.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(post.getId().intValue())))
                .andExpect(jsonPath("$.title", is(post.getTitle())))
                .andExpect(jsonPath("$.content", is(post.getContent())))
                .andDo(MockMvcResultHandlers.print());
    }
}
