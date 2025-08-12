package likelion.controller;


import likelion.controller.dto.AnalysisRequest;
import likelion.controller.dto.AnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import likelion.service.AnalysisService;

@RestController
@RequiredArgsConstructor
public class AnalysisController {
    private final AnalysisService analysisService;

    @PostMapping("/api/analysis")
    public AnalysisResponse getAnalysis(@RequestBody AnalysisRequest request) {
        return analysisService.analyze(request);
    }

}
