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
                # Role: 창의적인 대학가 상권끼리의 제휴 이벤트에 대한 아이디어를 던지는 기계
                # Goal: 주어진 타겟 매장과 파트너 매장 정보를 바탕으로, 학생들에게 매력적인 제휴 이벤트 안내 문구를 1개 생성합니다.
                - 내용은 매번 다른 아이디어로, 템플릿처럼 보이면 안 됩니다.
                - 아이디어를 홍보하는 것이 아닙니다.
                - \"~이런 이벤트를 진행하면 매장에 도움이 될 것 같습니다\"와 같은 느낌의 멘트를 줘야합니다.
                - 매장 사장님에게 다른 매장과의 협업 이벤트 아이디어를 제안해야합니다.
                - 이벤트를 소개하는 것이 아닌 아이디어를 제안하는 것입니다.
                
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
                - **내용**: 타겟 매장(%s)과 파트너 매장(%s), 양쪽 모두에게 이득이 되는 시나리오를 구상하세요. 특히, 각 매장의 대표 메뉴(%s, %s)를 활용하여 구체적인 이벤트를 제안하세요.
                - **스타일**: 고객에게 제안하며, 친근하고 매력적인 **~해요체**를 사용하세요. 문장 끝에는 이벤트의 매력을 요약하는 문장을 추가하고, 어울리는 이모지(1~2개)를 사용해도 좋습니다.
                - **필수 요소**: 누가, 어디서, 무엇을 하면, 어떤 혜택을 받는지, 그리고 구체적인 조건(기간, 시간, 증빙 방법 등)을 명확하게 포함해야 합니다.
                - **스타일 참고 예시 (내용은 반드시 다르게 구성할 것!):**예시! \"묵커피바에서 커피를 구매한 손님에게 탐나는바지락손칼국수를 함께 주문하면 커피 20%% 할인 혜택을 제공해요. 비피크 시간대(12:00-15:00)에만 적용되며, 상호 영수증을 제시해주세요. 일일 1회만 사용 가능하며, 2주간의 파이롯 기간 동안 진행돼요. 점심 시간대에 커피와 손칼국수를 함께 즐길 수 있는 할인 혜택이 매력적일 것 같아요! 🍜\"
                - **필수 조건**: 두 제휴업체의 추천 eventTitle 이 같으면 안됩니다. 
                - **필수 스타일**: **이벤트를 소개하는 것이 아니라**, 매장 사장님에게 다른 매장과의 협업 이벤트 **아이디어를 제안**해야합니다.
                - **필수 조건2**: eventTitle은 주어진 것 중 하나를 골라야합니다.
                
                # Output (JSON only)
                {
                  \"events\": [
                    {
                      \"eventTitle\": \"(쿠폰|연계할인|세트혜택|스탬프|타임세일|영수증교차혜택|사이드서비스|첫방문혜택) 중 1개를 골라야합니다.\",
                      \"description\": \"위 Instructions에 따라 작성된, 창의적이고 친근한 이벤트 안내 문구. 280-320자.\",
                      \"reason\": \"해당 매장을 제휴 파트너 매장으로 선택한 이유를 설명하세요. **distance를 언급**하며 \"거리가 가까운 점에서 추천하게 되었습니다\"와 같은 멘트를 주세요\"
                    }
                  ]
                }
                
                # Hard Bans
                - **홍보성 멘트 금지**: \"이런 이벤트가 있어요~\", \"이벤트 참여해보세요~\" 등 사용 금지.
                - **템플릿 사용 금지**: 예시와 똑같은 구조나 내용을 사용하지 마세요.
                - **논리적 오류 금지**: 한 매장이 다른 매장의 메뉴를 판매하는 등의 오류.
                - **check**: 당신은 이벤트를 소개하는 것이 아닌 제휴 이벤트 아이디어 제안가입니다. 꼭 지켜주세요.
                
                # Return
                - 위 스키마의 **순수 JSON**만 반환.
                """.formatted(
                targetName, targetType, targetMenu,
                partner.name(), partner.category(), partner.distanceMeters(), partnerMenu,
                targetName, partner.name(), targetMenu, partnerMenu
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
