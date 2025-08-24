package likelion.service;

import likelion.domain.entity.Category;
import likelion.domain.entity.Comment;
import likelion.domain.entity.Post;
import likelion.domain.entity.StoreCategory;
import likelion.dto.CommentResponseDto;
import likelion.dto.PostCreateRequestDto;
import likelion.dto.PostResponseDto;
import likelion.repository.CommentRepository;
import likelion.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;

    //이미지 저장 경로
    //rivate final Path imageDir = Paths.get("images").toAbsolutePath();

    @Value("${file.upload.dir:${FILE_UPLOAD_DIR:/var/app/images}}")
    private String uploadDir;

    private Path imageDir(){
        return Paths.get(uploadDir).toAbsolutePath();
    }

    @Transactional
    public Long createPost(PostCreateRequestDto req, MultipartFile image) throws IOException {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new IllegalArgumentException("title은 필수입니다.");
        }
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new IllegalArgumentException("content는 필수입니다.");
        }

        Category category = (req.getCategory() == null) ? Category.GENERAL : req.getCategory();

        Post.PostBuilder postBuilder = Post.builder()
                .title(req.getTitle())
                .content(req.getContent())
                .category(category);

        if (category == Category.PARTNERSHIP) {
            if (req.getMyStoreCategory() == null || req.getPartnerStoreCategory() == null) {
                throw new IllegalArgumentException("PARTNERSHIP 카테고리에는 myStoreCategory와 partnerStoreCategory가 필수입니다.");
            }
            postBuilder.myStoreCategory(StoreCategory.valueOf(req.getMyStoreCategory()));
            postBuilder.partnerStoreCategory(StoreCategory.valueOf(req.getPartnerStoreCategory()));
        }

        /**
         * 이미지 처리로직
         */
        String imageUrl = null;
        if (image != null && !image.isEmpty()) {
            String originalName = image.getOriginalFilename();
            String extension = getExtension(originalName);
            String savedFileName = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);

            Path savedPath = imageDir().resolve(savedFileName);
            image.transferTo(savedPath.toFile());

            imageUrl = "/images/" + savedFileName;
        }

        postBuilder.imageUrl(imageUrl);

        Post post = postBuilder.build();
        Post saved = postRepository.save(post);
        return saved.getId();
    }

    @Transactional(readOnly = true)
    public List<PostResponseDto> findAll() {
        return postRepository.findAll().stream()
                .map(PostResponseDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PostResponseDto findOne(Long id) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid post ID: " + id));
        List<Comment> comments = post.getComments();
        List<CommentResponseDto> commentResponseDtos = comments.stream().map(CommentResponseDto::new).collect(Collectors.toList());
        return new PostResponseDto(post, commentResponseDtos);
    }

    //getExtension 메소드
    public static String getExtension(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return (idx >= 0 && idx < filename.length() - 1)
                ? filename.substring(idx + 1).toLowerCase()
                : "";
    }

}
