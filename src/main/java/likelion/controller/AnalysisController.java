package likelion.controller;

import jakarta.validation.Valid;
import likelion.dto.AnalysisRequest;
import likelion.dto.AnalysisResponse;
import likelion.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
//Swagger/OpenAPI용 import
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.media.*;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequiredArgsConstructor
@Tag(name = "Analysis", description = "창업 분석")
public class AnalysisController {

    private final AnalysisService analysisService;

    @PostMapping("/api/analysis")
    @Operation(summary = "창업 분석 요청")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AnalysisResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    public AnalysisResponse getAnalysis(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AnalysisRequest.class),
                            examples = {
                                    @ExampleObject(
                                            name = "대학가-카페 예시",
                                            value = """
                        {
                          "addr": "37.299873897875194, 126.8390046169429",
                          "category": "카페/디저트",
                          "marketingArea": "대학가/학교 주변",
                          "budget": { "min": 150, "max": 200 },
                          "deposit": { "min": 2000, "max": 3000 },
                          "managementMethod": "홀 영업 위주",
                          "representativeMenuName": "아메리카노",
                          "representativeMenuPrice": 5000,
                          "size": { "min": 15, "max": 20 },
                          "height": "1"
                        }
                        """
                                    )
                            }
                    )
            )
            @Valid @RequestBody AnalysisRequest request
    ) {
        if (request == null) {
            throw new ResponseStatusException(BAD_REQUEST, "요청이 비어있음");
        }
        return analysisService.analyze(request);
    }
}