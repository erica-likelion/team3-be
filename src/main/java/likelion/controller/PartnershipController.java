package likelion.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import likelion.dto.PartnershipRequestDto;
import likelion.dto.PartnershipResponseDto;
import likelion.service.PartnershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/partnership")
@Tag(name = "Partnership", description = "제휴업체 추천")
public class PartnershipController {

    private final PartnershipService partnershipService;

    @PostMapping
    public ResponseEntity<PartnershipResponseDto> partnership(@Valid @RequestBody PartnershipRequestDto req){
        PartnershipResponseDto resp = partnershipService.recommend(req);
        return ResponseEntity.ok(resp);
    }
}
