package likelion.controller;

import likelion.dto.PostCreateRequestDto;
import likelion.dto.PostResponseDto;
import likelion.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/post")
public class PostController {

    private final PostService postService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(@RequestPart("dto") PostCreateRequestDto dto,
                                  @RequestPart(value = "image", required = false) MultipartFile image) throws IOException {
        Long id = postService.createPost(dto, image);
        return ResponseEntity.created(URI.create("/api/posts/" + id))
                .body(Map.of("id", id));
    }

    @GetMapping
    public ResponseEntity<List<PostResponseDto>> getAllPosts() {
        List<PostResponseDto> posts = postService.findAll();
        return ResponseEntity.ok(posts);
    }

    @GetMapping("/{postId}")
    public ResponseEntity<PostResponseDto> getPostById(@PathVariable Long postId) {
        PostResponseDto post = postService.findOne(postId);
        return ResponseEntity.ok(post);
    }
}
