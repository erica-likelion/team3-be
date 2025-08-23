package likelion.partnershipTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import likelion.domain.entity.Restaurant;
import likelion.dto.PartnershipRequestDto;
import likelion.repository.RestaurantRepository;
import likelion.service.AiChatService;
import likelion.service.PartnershipService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartnershipExcludeExceptionTest {

    @InjectMocks
    private PartnershipService partnershipService;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private AiChatService aiChatService;

    @Mock
    private ObjectMapper objectMapper;

    @ParameterizedTest
    @ValueSource(strings = {
            "이디야커피 안산한대점", "스타벅스 안산한양대점", "메가MGC커피 한양대에리카점",
            "투썸플레이스 안산꿈의교회점", "교촌치킨 사동1호점", "파리바게뜨 안산한양대점",
            "쥬씨 안산한양대점", "아마스빈 안산한양대점"
    })
    void recommend_shouldThrowException_whenTargetIsFranchise(String franchiseName) {
        // Given
        PartnershipRequestDto requestDto = new PartnershipRequestDto();
        requestDto.setStoreName(franchiseName);

        Restaurant franchiseStore = new Restaurant();
        franchiseStore.setRestaurantName(franchiseName);
        franchiseStore.setLatitude(37.2939);
        franchiseStore.setLongitude(126.835);

        when(restaurantRepository.findAll()).thenReturn(List.of(franchiseStore));

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            partnershipService.recommend(requestDto);
        });

        assertEquals("대기업 프랜차이즈는 지원하지 않습니다.", exception.getReason());
    }
}
