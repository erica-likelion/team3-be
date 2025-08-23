package likelion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import likelion.domain.entity.Restaurant;
import likelion.dto.PartnershipRequestDto;
import likelion.dto.PartnershipResponseDto;
import likelion.repository.RestaurantRepository;
import likelion.service.distance.DistanceCalc;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.*;

@Service
@RequiredArgsConstructor
public class PartnershipService {

    private final RestaurantRepository restaurantRepository;
    private final AiChatService aiChatService;   // (옵션) 나중에 카피를 더 다듬고 싶으면 사용
    private final ObjectMapper objectMapper;

    public PartnershipResponseDto recommend(PartnershipRequestDto dto) {
        if (dto == null || dto.getStoreName() == null || dto.getStoreName().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "가게명을 입력해 주세요.");
        }

        // 1) 타겟 매장 찾기 (간단 부분일치 허용)
        Restaurant target = restaurantRepository.findAll().stream()
                .filter(r -> safe(r.getRestaurantName()).contains(safe(dto.getStoreName())))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "해당 매장을 찾을 수 없어요."));

        if (target.getLatitude() == null || target.getLongitude() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "해당 매장의 좌표 정보가 없어요.");
        }

        // 2) 보완 카테고리 판정: 음식점이면 카페 추천, 카페면 음식점 추천
        boolean isTargetCafe = isCafeCategory(target.getCategory());
        String partnerTypeWanted = isTargetCafe ? "음식점" : "카페";

        // 3) 반경 50m 내 후보 추출
        double lat = target.getLatitude();
        double lon = target.getLongitude();
        double radiusM = 50.0;

        List<Restaurant> all = restaurantRepository.findAll();
        List<Restaurant> nearby = all.stream()
                .filter(r -> r.getLatitude() != null && r.getLongitude() != null)
                .filter(r -> DistanceCalc.calculateDistance(lat, lon, r.getLatitude(), r.getLongitude()) <= radiusM)
                .filter(r -> !Objects.equals(r.getKakaoPlaceId(), target.getKakaoPlaceId()))
                .collect(Collectors.toList());

        // 4) 상호보완 카테고리 필터
        List<Restaurant> partners = nearby.stream()
                .filter(r -> isTargetCafe ? isFoodCategory(r.getCategory()) : isCafeCategory(r.getCategory()))
                .sorted(Comparator.comparingDouble(r ->
                        DistanceCalc.calculateDistance(lat, lon, r.getLatitude(), r.getLongitude())))
                .limit(3)
                .toList();

        if (partners.isEmpty()) {
            throw new ResponseStatusException(NO_CONTENT, "주변 반경 50m 내에 적합한 제휴 후보가 없어요.");
        }

        // 5) DTO 매핑
        List<PartnershipResponseDto.PartnerInfo> partnerDtos = partners.stream()
                .map(r -> new PartnershipResponseDto.PartnerInfo(
                        nvl(r.getRestaurantName(), "(이름없음)"),
                        nvl(r.getCategory(), partnerTypeWanted),
                        (int) Math.round(DistanceCalc.calculateDistance(lat, lon, r.getLatitude(), r.getLongitude())),
                        nvl(r.getKakaoUrl(), ""),
                        nvl(r.getRoadAddress(), nvl(r.getNumberAddress(), "주소 정보 없음"))
                ))
                .toList();

        // 6) 이유문 (AI가 생성하도록 변경)
        // 7) 이벤트 2개 추천 (룰 기반, Wi-Fi 같은 기본 제공 서비스 제외)
        List<PartnershipResponseDto.EventSuggestion> events = buildEventSuggestions(
                isTargetCafe ? "카페" : "음식점",
                partnerTypeWanted,
                partnerDtos
        );

        return new PartnershipResponseDto(
                nvl(target.getRestaurantName(), ""),
                isTargetCafe ? "카페" : "음식점",
                partnerDtos,
                events
        );
    }

    // ====== 헬퍼들 ======

    private String safe(String s) {
        return Optional.ofNullable(s).orElse("").replaceAll("\\s+", "").toLowerCase();
    }

    private String nvl(String s, String d) {
        return (s == null || s.isBlank()) ? d : s;
    }

    private boolean isCafeCategory(String raw) {
        String c = Optional.ofNullable(raw).orElse("").toLowerCase();
        return containsAny(c, List.of("카페", "커피", "베이커리", "제과", "디저트", "빙수", "도넛", "브런치"));
    }

    private boolean isFoodCategory(String raw) {
        String c = Optional.ofNullable(raw).orElse("").toLowerCase();
        return containsAny(c, List.of(
                "한식", "중식", "일식", "양식", "아시안", "분식", "국밥", "칼국수", "면", "라멘", "초밥", "돈까스", "치킨", "피자", "파스타", "탕", "찌개", "덮밥", "도시락"));
    }

    private boolean containsAny(String text, List<String> keys) {
        for (String k : keys) if (text.contains(k)) return true;
        return false;
    }

    private List<PartnershipResponseDto.EventSuggestion> buildEventSuggestions(
            String targetType,
            String partnerType,
            List<PartnershipResponseDto.PartnerInfo> partnerDtos
    ) {
        List<PartnershipResponseDto.EventSuggestion> suggestions = new ArrayList<>();
        for (PartnershipResponseDto.PartnerInfo partner : partnerDtos) {
            try {
                String prompt = buildEventSuggestionPromptForPartner(targetType, partnerType, partner);

                String rawResponse = aiChatService.getAnalysisResponseFromAI(prompt)
                        .replace("```json", "")
                        .replace("```", "")
                        .trim();

                record EventListWrapper(List<PartnershipResponseDto.EventSuggestion> events) {}
                EventListWrapper wrapper = objectMapper.readValue(rawResponse, EventListWrapper.class);

                if (wrapper.events() != null && !wrapper.events().isEmpty()) {
                    suggestions.addAll(wrapper.events());
                } else {
                    suggestions.add(createGenericFallbackEvent(partner));
                }
            } catch (Exception e) {
                System.err.println("Error generating event for partner " + partner.name() + ": " + e.getMessage());
                suggestions.add(createGenericFallbackEvent(partner));
            }
        }
        // Ensure we return exactly 2 events as per DTO (if possible, otherwise fill with fallbacks)
        while (suggestions.size() < 2) {
            suggestions.add(createGenericFallbackEvent(null));
        }
        return suggestions.stream().limit(2).collect(Collectors.toList());
    }

    private String buildEventSuggestionPromptForPartner(
            String targetType,
            String partnerType,
            PartnershipResponseDto.PartnerInfo partner
    ) {
        return """
                # Role: 창의적인 대학가 상권끼리의 제휴 이벤트에 대한 아이디어를 던지는 기계
                # Goal: 주어진 타겟 매장과 파트너 매장 정보를 바탕으로, 학생들에게 매력적인 제휴 이벤트 안내 문구를 1개 생성합니다.
                - 내용은 매번 다른 아이디어로, 템플릿처럼 보이면 안 됩니다.
                - 아이디어를 홍보하는 것이 아닙니다.
                - "~이런 이벤트를 진행하면 매장에 도움이 될 것 같습니다"와 같은 느낌의 멘트를 줘야합니다.

                [타겟 매장 정보]
                - 업종: %s

                [파트너 매장 정보]
                - 이름: %s
                - 업종: %s
                - 거리: %dm

                # Instructions for 'description'
                - **내용**: 타겟과 파트너, 양쪽 모두에게 이득이 되는 시나리오를 구상하세요.
                - **스타일**: 고객에게 제안하며, 친근하고 매력적인 **~해요체**를 사용하세요. 문장 끝에는 이벤트의 매력을 요약하는 문장을 추가하고, 어울리는 이모지(1~2개)를 사용해도 좋습니다.
                - **필수 요소**: 누가, 어디서, 무엇을 하면, 어떤 혜택을 받는지, 그리고 구체적인 조건(기간, 시간, 증빙 방법 등)을 명확하게 포함해야 합니다.
                - **스타일 참고 예시 (내용은 반드시 다르게 구성할 것!):**예시! \"묵커피바에서 커피를 구매한 손님에게 탐나는바지락손칼국수를 함께 주문하면 커피 20%% 할인 혜택을 제공해요. 비피크 시간대(12:00-15:00)에만 적용되며, 상호 영수증을 제시해주세요. 일일 1회만 사용 가능하며, 2주간의 파이롯 기간 동안 진행돼요. 점심 시간대에 커피와 손칼국수를 함께 즐길 수 있는 할인 혜택이 매력적일 것 같아요! 🍜\"
                - **필수 조건**: 두 제휴업체의 추천 eventTitle 이 같으면 안됩니다.

                # Output (JSON only)
                {
                  "events": [
                    {
                      "eventTitle": "쿠폰|연계할인|세트혜택|스탬프|타임세일|영수증교차혜택|사이드서비스|첫방문혜택 중 1개",
                      "description": "위 Instructions에 따라 작성된, 창의적이고 친근한 이벤트 안내 문구. 150-250자.",
                      "reason": "해당 매장을 제휴 파트너 매장으로 선택한 이유를 설명하세요. distance를 언급하며 "거리가 가까운 점에서 추천하게 되었습니다"와 같은 멘트를 주세요"
                    }
                  ]
                }

                # Hard Bans
                - **홍보성 멘트 금지**: "이런 이벤트가 있어요~", "이벤트 참여해보세요~" 등 사용 금지.
                - **템플릿 사용 금지**: 예시와 똑같은 구조나 내용을 사용하지 마세요.
                - **논리적 오류 금지**: 한 매장이 다른 매장의 메뉴를 판매하는 등의 오류.
                """.formatted(
                targetType,
                partner.name(), partner.category(), partner.distanceMeters()
        );
    }

    private PartnershipResponseDto.EventSuggestion createGenericFallbackEvent(PartnershipResponseDto.PartnerInfo partner) {
        String name = (partner != null) ? partner.name() : "알 수 없는 매장";
        String distance = (partner != null) ? String.valueOf(partner.distanceMeters()) : "0";
        return new PartnershipResponseDto.EventSuggestion(
                "연계할인",
                String.format("파트너 매장 %s(%sm)와 연계하여 새로운 혜택을 구상 중입니다.", name, distance),
                String.format("파트너 매장 %s(%sm)와 가까워 시너지가 기대됩니다.", name, distance)
        );
    }

    // ===================== 헬퍼들 =====================

    private record Candidate(Restaurant r, String type, int distanceMeters) {}

    private boolean sameRestaurant(Restaurant a, Restaurant b) {
        // placeId가 있으면 그걸 우선 비교
        if (a.getKakaoPlaceId() != null && b.getKakaoPlaceId() != null) {
            return Objects.equals(a.getKakaoPlaceId(), b.getKakaoPlaceId());
        }
        return normalize(a.getRestaurantName()).equals(normalize(b.getRestaurantName()));
    }

    private String normalize(String s) {
        return Optional.ofNullable(s).orElse("").replaceAll("\\s+", "").toLowerCase();
    }

    private String safeName(Restaurant r) {
        return Optional.ofNullable(r.getRestaurantName()).orElse("(이름없음)");
    }

    private double safeRating(Restaurant r) {
        return Optional.ofNullable(r.getRating()).map(BigDecimal::doubleValue).orElse(0.0);
    }

    private String bestAddress(Restaurant r) {
        String road = r.getRoadAddress();
        String num  = r.getNumberAddress();
        if (road != null && !road.isBlank()) return road;
        if (num  != null && !num.isBlank())  return num;
        return "";
    }

    /**
     * 카테고리 문자열을 간단히 두 축으로 정규화
     *  - 카페/디저트/베이커리/커피/아이스크림/빙수 → "카페"
     *  - 그 외 식사류(한식/중식/일식/양식/아시안/분식 등) → "음식점"
     *  - 매핑 실패 → "기타"
     */
    private String toSimpleType(String raw) {
        String c = Optional.ofNullable(raw).orElse("").toLowerCase();
        // 카페 계열 키워드
        String[] cafeKeys = {"카페","커피","디저트","베이커리","제과","아이스크림","빙수","도넛","브런치"};
        for (String k : cafeKeys) if (c.contains(k)) return "카페";
        // 나머지는 음식점으로
        String[] foodKeys = {"한식","중식","일식","양식","아시안","분식","피자","치킨","탕","국","면","우동","라멘","스시","초밥","돈가스","덮밥","파스타","스테이크","마라"};
        for (String k : foodKeys) if (c.contains(k)) return "음식점";
        return "음식점"; // 디폴트로 음식점 취급
    }

    private String oppositeType(String simpleType) {
        if ("카페".equals(simpleType)) return "음식점";
        if ("음식점".equals(simpleType)) return "카페";
        return "카페"; // 기본 교차 방향
    }

}
