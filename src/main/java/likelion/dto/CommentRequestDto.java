package likelion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Schema(description = "댓글 작성 요청")
@Getter @Setter @NoArgsConstructor
public class CommentRequestDto {

    @Schema(description = "댓글 내용", example = "댓글내용 댓글내용 예시 예시")
    @NotBlank(message = "content는 필수입니다.")
    private String content;
}