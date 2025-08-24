package likelion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import likelion.domain.entity.Comment;
import lombok.Getter;

import java.time.LocalDateTime;

@Schema(description = "댓글 응답")
@Getter
public class CommentResponseDto {
    private final Long id;
    private final Long postId;
    private final String content;
    private final LocalDateTime createdAt;

    public CommentResponseDto(Comment c) {
        this.id = c.getId();
        this.postId = c.getPost().getId();
        this.content = c.getContent();
        this.createdAt = c.getCreated_at();
    }
}
