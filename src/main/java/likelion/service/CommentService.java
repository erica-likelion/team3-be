package likelion.service;

import jakarta.transaction.Transactional;
import likelion.domain.entity.Comment;
import likelion.domain.entity.Post;
import likelion.dto.CommentRequestDto;
import likelion.dto.CommentResponseDto;
import likelion.repository.CommentRepository;
import likelion.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;

    @Transactional
    public CommentResponseDto createComment(Long postId, CommentRequestDto req) {
        // 게시물 존재 확인
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("해당 게시물이 없습니다."));

        // 내용 검증(프론트에서 하는지 몰라서 일단)
        String content = req.getContent();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("댓글 내용은 필수 작성 항목입니다.");
        }

        content = content.trim();
        if (content.length() > 500) {
            content = content.substring(0, 500);
        }

        // 엔티티 생성. userName이랑 createdAt은 엔티티에서 세팅함
        Comment comment = Comment.builder().post(post).content(content).build();

        post.setCommentCount(post.getCommentCount() + 1);
        postRepository.saveAndFlush(post);

        Comment saved = commentRepository.save(comment);
        return new CommentResponseDto(saved);
    }
}
