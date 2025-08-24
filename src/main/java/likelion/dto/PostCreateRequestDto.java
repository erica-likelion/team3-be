package likelion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import likelion.domain.entity.Category;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

@Schema(description = "게시글 작성 정보")
@Getter
@Setter
@NoArgsConstructor
public class PostCreateRequestDto {

    private String title;
    private String content;

    @Schema(description = "GENERAL or PARTNERSHIP")
    private Category category; //자유 or 제휴

    @Schema(description = "내 업종 카테고리")
    private String myStoreCategory;

    @Schema(description = "제휴할 업종 카테고리")
    private String partnerStoreCategory;
}
