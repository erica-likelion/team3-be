package likelion.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import likelion.dto.CommentRequestDto;
import likelion.dto.CommentResponseDto;
import likelion.service.CommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/post/{postId}/comment")
@Tag(name = "Comment", description = "게시물 댓글")
public class CommentController {
    private final CommentService commentService;

    @Operation(
            summary = "댓글 등록",
            description = "특정 게시물(postId)에 익명 댓글을 작성합니다. userName은 항상 '익명'으로 저장됩니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "생성됨",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CommentResponseDto.class),
                            examples = @ExampleObject(
                                    name = "성공 예시",
                                    value = """
                    {
                      "id": 501,
                      "postId": 123,
                      "userName": "익명",
                      "content": "댓글내용 댓글내용 예시",
                      "createdAt": "2025-08-23T03:33:12.123"
                    }
                    """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청(내용 누락 등)",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "해당 게시물 없음",
                    content = @Content(mediaType = "application/json")
            )
    })
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<CommentResponseDto> createComment(
            @PathVariable Long postId,
            @Valid @RequestBody CommentRequestDto request
    ) {
        CommentResponseDto created = commentService.createComment(postId, request);
        return ResponseEntity.status(201).body(created);
    }

}
