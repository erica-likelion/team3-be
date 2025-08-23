package likelion.service;

import likelion.domain.entity.Category;
import likelion.domain.entity.Comment;
import likelion.domain.entity.Post;
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

    //게시글 생성
    @Transactional
    public Long createPost(PostCreateRequestDto req, MultipartFile image) throws IOException {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new IllegalArgumentException("title은 필수입니다.");
        }
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new IllegalArgumentException("content는 필수입니다.");
        }
        if (req.getUserName() == null || req.getUserName().isBlank()) {
            throw new IllegalArgumentException("userName은 필수입니다.");
        }

        Category category = (req.getCategory() == null) ? Category.GENERAL : req.getCategory();

        /**
         * 이미지 처리로직
         */
        // 디렉토리 보장
        //Files.createDirectories(imageDir());

        String imageUrl = null;
        if (image != null && !image.isEmpty()) {
            String originalName = image.getOriginalFilename(); // 원본 파일명 (null 가능)
            String extension = getExtension(originalName);     // png, jpg, ...
            String savedFileName = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);

            Path savedPath = imageDir().resolve(savedFileName);
            image.transferTo(savedPath.toFile());

            // 정적 리소스 매핑 기준 URL ("/image" → "/images/..." 로 수정)
            imageUrl = "/images/" + savedFileName;
        }

        Post post = Post.builder()
                .userName(req.getUserName())
                .title(req.getTitle())
                .content(req.getContent())
                .category(category)
                .imageUrl(imageUrl)
                .build();

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
