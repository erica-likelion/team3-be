package likelion.controller;


import jakarta.validation.Valid;
import likelion.controller.dto.AnalysisRequest;
import likelion.controller.dto.AnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import likelion.service.AnalysisService;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequiredArgsConstructor
public class AnalysisController {
    private final AnalysisService analysisService;

    @PostMapping("/api/analysis")
    public AnalysisResponse getAnalysis(@Valid @RequestBody AnalysisRequest request) {
        if (request == null){
            throw new ResponseStatusException(BAD_REQUEST, "요청이 비어있음");
        }
        return analysisService.analyze(request);
    }

}
