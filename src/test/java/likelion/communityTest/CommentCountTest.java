package likelion.communityTest;

import likelion.domain.entity.Post;
import likelion.dto.CommentRequestDto;
import likelion.dto.PostCreateRequestDto;
import likelion.dto.PostResponseDto;
import likelion.repository.CommentRepository;
import likelion.repository.PostRepository;
import likelion.service.CommentService;
import likelion.service.PostService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.annotation.Commit;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class CommentCountTest {

    @Autowired
    private PostService postService;

    @Autowired
    private CommentService commentService;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private CommentRepository commentRepository;

    private Long postId1;
    private Long postId2;
    private Long postId3;

    @BeforeEach
    @Commit
    void setUp() throws IOException {
        // Given
        PostCreateRequestDto postDto1 = new PostCreateRequestDto();
        postDto1.setTitle("title1");
        postDto1.setContent("content1");
        postId1 = postService.createPost(postDto1, null);

        PostCreateRequestDto postDto2 = new PostCreateRequestDto();
        postDto2.setTitle("title2");
        postDto2.setContent("content2");
        postId2 = postService.createPost(postDto2, null);

        PostCreateRequestDto postDto3 = new PostCreateRequestDto();
        postDto3.setTitle("title3");
        postDto3.setContent("content3");
        postId3 = postService.createPost(postDto3, null);
    }

    @AfterEach
    void tearDown() {
        commentRepository.deleteAllInBatch();
        postRepository.deleteAllInBatch();
    }

    @Test
    @Transactional
    void commentCountTest() {
        // When
        commentService.createComment(postId1, new CommentRequestDto("comment1"));
        commentService.createComment(postId1, new CommentRequestDto("comment2"));
        commentService.createComment(postId2, new CommentRequestDto("comment3"));

        // Then
        Post post1Entity = postRepository.findById(postId1).orElseThrow();
        PostResponseDto post1 = new PostResponseDto(post1Entity);
        assertThat(post1.getCommentCount()).isEqualTo(2);

        Post post2Entity = postRepository.findById(postId2).orElseThrow();
        PostResponseDto post2 = new PostResponseDto(post2Entity);
        assertThat(post2.getCommentCount()).isEqualTo(1);

        Post post3Entity = postRepository.findById(postId3).orElseThrow();
        PostResponseDto post3 = new PostResponseDto(post3Entity);
        assertThat(post3.getCommentCount()).isEqualTo(0);
    }

    @Test
    @Transactional
    void findAllPostsSortedByCommentCountAndId() {
        // When
        commentService.createComment(postId2, new CommentRequestDto("comment1"));
        commentService.createComment(postId2, new CommentRequestDto("comment2"));
        commentService.createComment(postId3, new CommentRequestDto("comment3"));

        // Then
        List<PostResponseDto> allPosts = postService.findAll();

        assertThat(allPosts.get(0).getId()).isEqualTo(postId2);
        assertThat(allPosts.get(1).getId()).isEqualTo(postId3);
        assertThat(allPosts.get(2).getId()).isEqualTo(postId1);
    }
}