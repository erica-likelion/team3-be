package likelion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import likelion.domain.entity.Category;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

@Schema(description = "게시글 작성 정보")
@Getter
@NoArgsConstructor
public class PostRequestDto {

    private String title;
    private String content;
    private MultipartFile image;
    private String userName;
    private String password;
    @Schema(description = "GENERAL or PARTNERSHIP")
    private Category category; //자유 or 제휴
}
