package likelion.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import likelion.domain.entity.Category;
import likelion.domain.entity.Post;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public class PostResponseDto {
    private final Long id;
    private final String title;
    private final String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private final LocalDateTime createdAt;
    private final Category category;
    private final String imageUrl;
    private final String myStoreCategory;
    private final String partnerStoreCategory;
    private final Integer commentCount;
    private List<CommentResponseDto> comments;

    public PostResponseDto(Post post) {
        this.id = post.getId();
        this.title = post.getTitle();
        this.content = post.getContent();
        this.createdAt = post.getCreatedAt();
        this.category = post.getCategory();
        this.imageUrl = post.getImageUrl();
        this.myStoreCategory = post.getMyStoreCategory() != null ? post.getMyStoreCategory().getDisplayName() : null;
        this.partnerStoreCategory = post.getPartnerStoreCategory() != null ? post.getPartnerStoreCategory().getDisplayName() : null;
        this.commentCount = post.getCommentCount();
    }

    public PostResponseDto(Post post, List<CommentResponseDto> comments) {
        this.id = post.getId();
        this.title = post.getTitle();
        this.content = post.getContent();
        this.createdAt = post.getCreatedAt();
        this.category = post.getCategory();
        this.imageUrl = post.getImageUrl();
        this.myStoreCategory = post.getMyStoreCategory() != null ? post.getMyStoreCategory().getDisplayName() : null;
        this.partnerStoreCategory = post.getPartnerStoreCategory() != null ? post.getPartnerStoreCategory().getDisplayName() : null;
        this.commentCount = post.getCommentCount();
        this.comments = comments;
    }
}