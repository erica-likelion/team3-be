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
    private final AiChatService aiChatService;
    private final ObjectMapper objectMapper;

    public PartnershipResponseDto recommend(PartnershipRequestDto dto) {
        if (dto == null || dto.getStoreName() == null || dto.getStoreName().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "가게명을 입력해 주세요.");
        }

        Restaurant target = restaurantRepository.findAll().stream()
                .filter(r -> safe(r.getRestaurantName()).contains(safe(dto.getStoreName())))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "해당 매장을 찾을 수 없어요."));

        if (target.getLatitude() == null || target.getLongitude() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "해당 매장의 좌표 정보가 없어요.");
        }

        boolean isTargetCafe = isCafeCategory(target.getCategory());
        String partnerTypeWanted = isTargetCafe ? "음식점" : "카페";

        String targetMenu = guessMenu(target);

        List<Restaurant> partners = findPartners(target, isTargetCafe);

        List<PartnershipResponseDto.PartnerInfo> partnerDtos = partners.stream()
                .map(r -> new PartnershipResponseDto.PartnerInfo(
                        nvl(r.getRestaurantName(), "(이름없음)"),
                        nvl(r.getCategory(), partnerTypeWanted),
                        (int) Math.round(DistanceCalc.calculateDistance(target.getLatitude(), target.getLongitude(), r.getLatitude(), r.getLongitude())),
                        nvl(r.getKakaoUrl(), ""),
                        nvl(r.getRoadAddress(), nvl(r.getNumberAddress(), "주소 정보 없음"))
                ))
                .toList();

        List<PartnershipResponseDto.EventSuggestion> events = buildEventSuggestions(
                isTargetCafe ? "카페" : "음식점",
                partnerTypeWanted,
                partnerDtos,
                targetMenu,
                target
        );

        return new PartnershipResponseDto(
                nvl(target.getRestaurantName(), ""),
                isTargetCafe ? "카페" : "음식점",
                partnerDtos,
                events
        );
    }

    private String guessMenu(Restaurant restaurant) {
        try {
            String prompt = buildMenuGuessPrompt(restaurant);
            String rawResponse = aiChatService.getAnalysisResponseFromAI(prompt)
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();
            record MenuWrapper(String menu) {}
            MenuWrapper wrapper = objectMapper.readValue(rawResponse, MenuWrapper.class);
            return wrapper.menu();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildMenuGuessPrompt(Restaurant restaurant) {
        return """
                # Role: 가게 이름과 카테고리만 보고 대표 메뉴 1~2개를 추측하는 기계
                # Goal: 주어진 가게 정보를 바탕으로, 가장 가능성 높은 대표 메뉴를 쉼표로 구분하여 간결하게 반환합니다.
                
                [가게 정보]
                - 이름: %s
                - 카테고리: %s
                
                # Instructions
                - 가게 이름과 카테고리를 조합하여 가장 핵심적인 메뉴를 추측하세요.
                - 일반적인 메뉴 이름으로 답해주세요. (예: '매콤한 국물' 대신 '짬뽕')
                - 1~2개의 메뉴를 쉼표(,)로 구분하여 반환하세요.
                
                # Examples
                - 이름: \"예산 감자탕\", 카테고리: \"한식\" -> \"감자탕\"
                - 이름: \"시저 커피\", 카테고리: \"카페\" -> \"아메리카노, 카페라떼\"
                - 이름: \"원할머니 보쌈족발\", 카테고리: \"한식\" -> \"보쌈, 족발\"
                
                # Output (JSON only)
                {
                  \"menu\": \"추측한 메뉴 (쉼표로 구분)\" 
                }
                """.formatted(restaurant.getRestaurantName(), restaurant.getCategory());
    }

    private List<Restaurant> findPartners(Restaurant target, boolean isTargetCafe) {
        double lat = target.getLatitude();
        double lon = target.getLongitude();
        double distance = 50.0;
        final double MAX_DISTANCE = 500.0;

        List<Restaurant> potentialPartners = new ArrayList<>();

        while (potentialPartners.size() < 2 && distance <= MAX_DISTANCE) {
            final double currentDistance = distance;
            List<Restaurant> all = restaurantRepository.findAll();
            potentialPartners = all.stream()
                    .filter(r -> r.getLatitude() != null && r.getLongitude() != null)
                    .filter(r -> DistanceCalc.calculateDistance(lat, lon, r.getLatitude(), r.getLongitude()) <= currentDistance)
                    .filter(r -> !Objects.equals(r.getKakaoPlaceId(), target.getKakaoPlaceId()))
                    .filter(r -> isTargetCafe ? isFoodCategory(r.getCategory()) : isCafeCategory(r.getCategory()))
                    .collect(Collectors.toList());

            if (potentialPartners.size() < 2) {
                distance += 30;
            }
        }

        if (potentialPartners.size() < 2) {
            throw new ResponseStatusException(NOT_FOUND, "주변에 적합한 제휴 후보가 없어요.");
        }

        if (potentialPartners.size() > 2) {
            Collections.shuffle(potentialPartners);
            return potentialPartners.subList(0, 2);
        }

        return potentialPartners;
    }

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
            List<PartnershipResponseDto.PartnerInfo> partnerDtos,
            String targetMenu,
            Restaurant target
    ) {
        List<PartnershipResponseDto.EventSuggestion> suggestions = new ArrayList<>();
        for (PartnershipResponseDto.PartnerInfo partner : partnerDtos) {
            try {
                Restaurant partnerRestaurant = restaurantRepository.findByRestaurantName(partner.name()).orElse(target);
                String partnerMenu = (partnerRestaurant != null) ? guessMenu(partnerRestaurant) : "";

                String prompt = buildEventSuggestionPromptForPartner(target.getRestaurantName(), targetType, partner, targetMenu, partnerMenu);

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
        while (suggestions.size() < 2) {
            suggestions.add(createGenericFallbackEvent(null));
        }
        return suggestions.stream().limit(2).collect(Collectors.toList());
    }

    private String buildEventSuggestionPromptForPartner(
            String targetName,
            String targetType,
            PartnershipResponseDto.PartnerInfo partner,
            String targetMenu,
            String partnerMenu
    ) {
        return """
            # Role: 대학가 상권 제휴 컨설턴트
            # Goal: 타겟 매장 **사장님께** 특정 파트너와 진행할 **제휴 아이디어**를 제안합니다.
            -"~입니다"같은 단정짓는 말투 금지
            - 절대 고객 홍보 톤 금지(예: "~할인해드려요", "이벤트 참여하세요").
            - 문체: 사장님께 조언하는 제안체(예: "~하는 건 어떨까요?", "~하면 좋을 것 같습니다", "~로 이어가면 효과적일 것으로 예상됩니다").
            - "저희 매장" 같은 1인칭 표현 금지. 반드시 "사장님의 매장" 또는 매장명을 직접 언급.
            - 여러 파트너가 생성될 경우, **각 eventTitle은 서로 달라야 합니다(절대 중복 금지)**.
            - 이미 사용된 eventTitle이 있을 경우, (쿠폰|세트혜택|스탬프|타임세일|영수증교차혜택|사이드서비스|첫방문혜택) 중 **다른 것**을 선택하세요.
            - "연계할인"은 한 번만 사용할 수 있습니다.
            - 이모지도 모든 응답에 꼭 넣어줘. 좀 더 친근하게.(description, reason 항목 모두)
            
            [타겟 매장 정보]
            - 이름: %s
            - 업종: %s
            - 대표 메뉴: %s
            
            [파트너 매장 정보]
            - 이름: %s
            - 업종: %s
            - 거리: %dm
            - 대표 메뉴: %s
            
            # Instructions for 'description'
            - **수신자: 사장님**. 반드시 소비자가 아닌 사장님을 대상으로 제안하세요. (소비자에게 말하지 마세요.)
            - "사장님의 매장" 또는 매장명을 직접 언급하세요.
            - 제휴 방식, 조건(기간/시간/증빙/제한), 기대효과(매출/회전/비피크 보완 등)를 **구체적**으로.
            - 누가, 어디서, 무엇을 하면, 어떤 혜택을 받는지, 그리고 구체적인 조건(기간, 시간, 증빙 방법 등)을 명확하게 포함해야 합니다.
            - 거리(%dm)와 메뉴 조합 이유를 녹여 **실행 가능한 제안**으로 작성.
            - 길이: 280~320자.
            - 문체: 부드럽게 제안하는 톤 ("~하는 건 어떨까요?", "~하면 좋을 것 같습니다", "~예상됩니다")
            - "~입니다, ~제안드립니다" 같은 단정적 보고체는 피하세요.
            - 예: "카페마루와 연계할인을 진행해보시는 건 어떨까요? 고객 만족도가 높아지고 두 매장 모두 매출에 긍정적인 영향을 줄 수 있을 것 같습니다.
            # Instructions for 'reason'
            - 이모지 꼭 넣어줘
            
            
            # Output (JSON only)
            {
              "events": [
                {
                  "eventTitle": "(쿠폰|연계할인|세트혜택|스탬프|타임세일|영수증교차혜택|사이드서비스|첫방문혜택) 중 1개, 중복은 가능하면 금지",
                  "description": "사장님 대상 제휴 **제안문**(보고체/제안체, 280~320자, 이모지/홍보체 금지).",
                  "reason": "선정 사유를 한 줄로. 거리 %dm 근접성 및 메뉴 보완성 언급. '거리가 가까운 점에서 제안드립니다' 포함."
                }
              ]
            }
        
            # Hard Bans
            - 소비자 호칭/안내(예: 고객님, ~혜택 드려요) 금지.
            - "저희 매장" 같은 1인칭 표현 금지.
            - 템플릿 반복 금지(문장 구조 다양화).
            - 논리 오류 금지(상호 메뉴 혼동 금지).
            - "세트혜택"이라도 무언가를 공짜로 주는 것은 금지.
            
            # Return
            - 위 스키마의 **순수 JSON**만 반환.
            """.formatted(
                        targetName, targetType, targetMenu,
                        partner.name(), partner.category(), partner.distanceMeters(), partnerMenu,
                        partner.distanceMeters(),
                        partner.distanceMeters()
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

    private record Candidate(Restaurant r, String type, int distanceMeters) {}

    private boolean sameRestaurant(Restaurant a, Restaurant b) {
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
        String num = r.getNumberAddress();
        if (road != null && !road.isBlank()) return road;
        if (num != null && !num.isBlank()) return num;
        return "";
    }

    private String toSimpleType(String raw) {
        String c = Optional.ofNullable(raw).orElse("").toLowerCase();
        String[] cafeKeys = {"카페", "커피", "디저트", "베이커리", "제과", "아이스크림", "빙수", "도넛", "브런치"};
        for (String k : cafeKeys) if (c.contains(k)) return "카페";
        String[] foodKeys = {"한식", "중식", "일식", "양식", "아시안", "분식", "피자", "치킨", "탕", "국", "면", "우동", "라멘", "스시", "초밥", "돈가스", "덮밥", "파스타", "스테이크", "마라"};
        for (String k : foodKeys) if (c.contains(k)) return "음식점";
        return "음식점";
    }

    private String oppositeType(String simpleType) {
        if ("카페".equals(simpleType)) return "음식점";
        if ("음식점".equals(simpleType)) return "카페";
        return "카페";
    }
}
