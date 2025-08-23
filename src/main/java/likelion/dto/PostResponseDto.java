package likelion.dto;

import likelion.domain.entity.Category;
import likelion.domain.entity.Post;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public class PostResponseDto {
    private final Long id;
    private final String userName;
    private final String title;
    private final String content;
    private final LocalDateTime createdAt;
    private final Category category;
    private final String imageUrl;
    private List<CommentResponseDto> comments;

    public PostResponseDto(Post post) {
        this.id = post.getId();
        this.userName = post.getUserName();
        this.title = post.getTitle();
        this.content = post.getContent();
        this.createdAt = post.getCreatedAt();
        this.category = post.getCategory();
        this.imageUrl = post.getImageUrl();
    }

    public PostResponseDto(Post post, List<CommentResponseDto> comments) {
        this.id = post.getId();
        this.userName = post.getUserName();
        this.title = post.getTitle();
        this.content = post.getContent();
        this.createdAt = post.getCreatedAt();
        this.category = post.getCategory();
        this.imageUrl = post.getImageUrl();
        this.comments = comments;
    }
}