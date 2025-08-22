package likelion.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.*;

@Service
@RequiredArgsConstructor
public class PartnershipService {

    private static final double[] RADII = {50.0, 75.0, 100.0, 150.0, 200.0};

    private final RestaurantRepository restaurantRepository;
    private final AiChatService aiChatService;
    private final ObjectMapper objectMapper;

    /**
     * 가게명만 받아 제휴 후보(최대 2곳) + 이벤트(파트너별 1개) 추천
     */
    public PartnershipResponseDto recommend(PartnershipRequestDto req) {
        if (req == null || req.getStoreName() == null || req.getStoreName().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "가게명이 비어 있습니다.");
        }

        // 1) 대상 매장 조회
        List<Restaurant> all = restaurantRepository.findAll();
        Restaurant target = all.stream()
                .filter(r -> normalize(r.getRestaurantName()).equals(normalize(req.getStoreName())))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "해당 가게를 찾을 수 없습니다."));

        if (target.getLatitude() == null || target.getLongitude() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "대상 매장 좌표가 없습니다.");
        }

        // 2) 제휴 타입 결정 (음식점이면 카페 추천, 카페면 음식점 추천)
        String targetType = toSimpleType(target.getCategory());   // "카페" | "음식점"
        String partnerType = oppositeType(targetType);            // 음식점↔카페 교차

        // 3) 반경 확장 탐색으로 파트너 선정(최대 2곳)
        List<PartnershipResponseDto.PartnerInfo> partners =
                pickPartnersWithRadiusExpansion(all, target, partnerType, 2);

        if (partners.isEmpty()) {
            // 반경 200m까지도 없으면 진짜 없음
            throw new ResponseStatusException(NO_CONTENT, "반경 200m 내 적합한 제휴 후보가 없습니다.");
        }

        // 4) 파트너별 이벤트 1개 + reason 1개 생성 (AI 실패 시 파트너별 소프트 폴백)
        List<PartnershipResponseDto.EventSuggestion> events =
                generatePerPartnerEvents(target, partners, targetType, partnerType);

        return new PartnershipResponseDto(
                safeName(target),
                targetType,
                partners,
                events
        );
    }

    // ===================== 반경 확장 파트너 선택 =====================

    private List<PartnershipResponseDto.PartnerInfo> pickPartnersWithRadiusExpansion(
            List<Restaurant> all,
            Restaurant target,
            String partnerType,
            int limit
    ) {
        for (double radius : RADII) {
            List<PartnershipResponseDto.PartnerInfo> found = findPartners(all, target, partnerType, radius, limit);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    private List<PartnershipResponseDto.PartnerInfo> findPartners(
            List<Restaurant> all,
            Restaurant target,
            String partnerType,
            double radiusM,
            int limit
    ) {
        double tLat = target.getLatitude();
        double tLon = target.getLongitude();

        return all.stream()
                .filter(r -> r.getLatitude() != null && r.getLongitude() != null)
                .filter(r -> !sameRestaurant(r, target))
                .map(r -> new AbstractMap.SimpleEntry<>(r,
                        DistanceCalc.calculateDistance(tLat, tLon, r.getLatitude(), r.getLongitude())))
                .filter(e -> e.getValue() <= radiusM)
                .map(e -> {
                    Restaurant r = e.getKey();
                    String type = toSimpleType(r.getCategory());
                    return new Candidate(r, type, (int) Math.round(e.getValue()));
                })
                .filter(c -> c.type().equals(partnerType)) // 교차 타입만
                .sorted(Comparator
                        .comparingInt(Candidate::distanceMeters)             // 거리 오름차순
                        .thenComparing((Candidate c) -> safeRating(c.r())).reversed())
                .sorted(Comparator.comparingInt(Candidate::distanceMeters))  // 최종 거리는 다시 오름차순으로 안정화
                .limit(limit)
                .map(c -> new PartnershipResponseDto.PartnerInfo(
                        safeName(c.r()),
                        c.type(),
                        c.distanceMeters(),
                        c.r().getKakaoUrl(),
                        bestAddress(c.r())
                ))
                .collect(Collectors.toList());
    }

    // ===================== 파트너별 AI 이벤트(1개) 생성 =====================

    private static final Set<String> ALLOWED_TITLES = Set.of(
            "쿠폰", "연계할인", "세트혜택", "스탬프", "타임세일", "영수증교차혜택", "사이드서비스", "첫방문혜택"
    );

    private List<PartnershipResponseDto.EventSuggestion> generatePerPartnerEvents(
            Restaurant target,
            List<PartnershipResponseDto.PartnerInfo> partners,
            String targetType,
            String partnerType
    ) {
        List<PartnershipResponseDto.EventSuggestion> result = new ArrayList<>();
        Set<String> usedTitles = new HashSet<>();

        for (int idx = 0; idx < partners.size(); idx++) {
            PartnershipResponseDto.PartnerInfo p = partners.get(idx);
            String raw = "";
            try {
                String seed = Integer.toHexString(
                        Objects.hash(safeName(target), p.name(), p.distanceMeters(), idx)
                );

                String prompt = buildSinglePartnerPrompt(
                        safeName(target),
                        targetType,
                        p,
                        seed
                );

                raw = aiChatService.getAnalysisResponseFromAI(prompt)
                        .replace("```json", "")
                        .replace("```", "")
                        .trim();

                // {"eventTitle":"...","description":"...","reason":"..."}
                record PerOut(String eventTitle, String description, String reason) {
                }
                PerOut out = objectMapper.readValue(raw, PerOut.class);

                String title = normalizeTitle(out.eventTitle(), usedTitles);
                String desc = sanitize(out.description());
                String rsn = sanitizeReason(out.reason(), p);

                // 중복 타이틀 피하기
                if (usedTitles.contains(title)) {
                    title = pickAlternateTitle(title, usedTitles);
                }
                usedTitles.add(title);

                // 비어있으면 폴백
                if (desc.isBlank()) {
                    PartnershipResponseDto.EventSuggestion fb = perPartnerFallback(p, partnerType, idx, usedTitles);
                    result.add(fb);
                } else {
                    result.add(new PartnershipResponseDto.EventSuggestion(title, desc, rsn));
                }

            } catch (Exception e) {
                // 파싱 실패 → 파트너별 폴백
                PartnershipResponseDto.EventSuggestion fb = perPartnerFallback(p, partnerType, idx, usedTitles);
                result.add(fb);
            }
        }
        return result;
    }

    private String normalizeTitle(String t, Set<String> used) {
        String title = (t == null) ? "" : t.trim();
        if (!ALLOWED_TITLES.contains(title)) title = "연계할인";
        return title;
    }

    private String pickAlternateTitle(String current, Set<String> used) {
        // 현재와 다른 걸 하나 골라준다
        for (String cand : ALLOWED_TITLES) {
            if (!used.contains(cand)) return cand;
        }
        return current; // 전부 사용 중이면 그대로
    }

    /**
     * 파트너별 폴백(소프트) – 파트너명/거리 반영 + 아이디어 제안형 톤
     */
    private PartnershipResponseDto.EventSuggestion perPartnerFallback(
            PartnershipResponseDto.PartnerInfo p,
            String partnerType,
            int index,
            Set<String> usedTitles
    ) {
        String title = "연계할인";
        if (!usedTitles.contains("세트혜택")) title = "세트혜택";
        usedTitles.add(title);

        String desc;
        if ("카페".equals(partnerType)) {
            // target=음식점 → partner=카페
            desc = String.format(
                    "%s(%dm)와 교차 방문을 유도해보는 건 어떠세요? ☕️ 점심 이후(14~16시) ‘대표메뉴+아메리카노’ 라이트 세트나 음료 10%% 내외 연계할인을 제안드려요. 상호 영수증 확인, 1인 1회, 혼잡 시간 제외로 운영해보세요. 계산대 앞 미니 안내로 고객 선택을 돕는 것도 좋아요. 선택 이유: 식사 뒤 따뜻한 음료 동선이 자연스러워서 부담 없이 시도하기 좋아요.",
                    p.name(), p.distanceMeters()
            );
        } else {
            // target=카페 → partner=음식점
            desc = String.format(
                    "%s(%dm)와 가벼운 세트를 도입해보는 건 어떠세요? 🍜 공강(15~17시)에 ‘라이트 식사+아메리카노’ 구성을 10%% 내외 혜택으로 제안드려요. 학생증/스탬프 증빙, 1인 1세트 권장. 메뉴판에 세트 스티커를 붙여 선택 장벽을 낮춰보세요. 선택 이유: 짧은 시간에 간단히 해결하려는 수요와 잘 맞아요.",
                    p.name(), p.distanceMeters()
            );
        }
        String rsn = String.format("%s(%dm)와 가까워서 동선이 자연스러워요.", p.name(), p.distanceMeters());
        return new PartnershipResponseDto.EventSuggestion(title, sanitize(desc), sanitize(rsn));
    }

    // ===================== 프롬프트(파트너 1건 전용) =====================

    private String buildSinglePartnerPrompt(
            String targetName,
            String targetType,
            PartnershipResponseDto.PartnerInfo partner,
            String seed
    ) {
        return """
                # Role: 대학가 상권 제휴 **아이디어** 컨설턴트(**제시하는 것이지 홍보하는 것이 아니다**)
                # Goal
                - 아래 타깃 매장과 파트너 후보(1곳)를 보고 **정확히 1개**의 제휴 이벤트 **아이디어**를 만드세요.
                - **당신은 이벤트를 홍보하는 것이 아닙니다**
                - **당신은 이벤트를 제안하는 역할입니다**
                - 전 문장은 **아이디어 제안형(~해보세요/~제안드려요/~권장드려요)** 으로, **이미 운영/진행/제공 중**처럼 들리면 안 됩니다.
                
                [Seed]: %s
                [Target] name: %s | type: %s
                [Partner] name: %s | type: %s | dist: %dm | url: %s | addr: %s
                
                # Style (필수)
                - 한국어 **~요체** + **제안/권유 어투**.
                - **느낌표 금지**, 물음표는 **최대 1회**.
                - **라벨/설명조 서두 금지**: “혜택 제공처: …”, “제휴 카페(카페) — …” 금지.
                - **조사 괄호 표기 금지**: “와(과) / 을(를) / 이(가) / 은(는)” 같은 괄호 사용 금지.
                - 광고성/감성 멘트 금지: “행복한 시간”, “기대해주세요”, “오늘도 공부…?” 등 **홍보/응원 문장 금지**.
                - **당신은 이벤트를 홍보하는 것이 아닙니다**
                - **당신은 이벤트를 제안하는 역할입니다**
                
                # Output (JSON only)
                {
                  "eventTitle": "쿠폰|연계할인|세트혜택|스탬프|타임세일|영수증교차혜택|사이드서비스|첫방문혜택 중 1개",
                  "description": "반드시 **제안형 문구**로 시작(예: ‘타임세일 도입을 제안드려요.’ / ‘라이트 세트를 도입해보세요.’). 170~230자. 이모지 0~1개. 포함 요소: ①대상/범위, ②할인율·금액·상한 중 택1, ③시간대(피크/비피크/시험기간 중 하나), ④증빙(상호 영수증/스탬프/학생증 중 하나), ⑤제한(1인 1회 등), ⑥간단 운영 팁 1개, ⑦마지막에 ‘선택 이유: …’(파트너 특성·거리 기반 근거) 1문장.",
                  "reason": "한 문장. **파트너 이름과 거리(distanceMeters)(m)**를 포함해 왜 이 조합을 제안하는지 **제안형**으로 설명."
                }
                
                # Hard bans
                - 완료/진행/약속 표현: “제공됩니다/진행 중입니다/적용됩니다/받을 수 있습니다/이용 가능합니다/기대해주세요/서둘러주세요/행복한 시간…”
                - 라벨 서두: “혜택 제공처: …”, “제휴 카페(카페) — …”
                - 조사 괄호: “와(과)/을(를)/이(가)/은(는)”
                
                # Return
                - 위 스키마의 **순수 JSON**만 반환.
                """.formatted(
                seed,
                targetName, targetType,
                partner.name(), partner.category(), partner.distanceMeters(), partner.kakaomapUrl(), partner.address()
        );
    }

    // ===================== 텍스트 후처리/정규화 =====================

    private static final Pattern MULTI_QUESTION = Pattern.compile("(\\?\\s*){2,}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private String sanitize(String text) {
        if (text == null) return "";

        String s = text.trim();

        // 어색한 라벨 제거
        s = s.replace("혜택 제공처:", "").replace("혜택 제공처 :", "");

        // 조사 괄호 표기 제거: 와(과)/을(를)/이(가)/은(는)
        s = s.replace("(와)", "").replace("(과)", "")
                .replace("(을)", "").replace("(를)", "")
                .replace("(이)", "").replace("(가)", "")
                .replace("(은)", "").replace("(는)", "");

        // 완료형 → 제안형 톤 보정
        s = enforceSuggestionTone(s);

        // “시도해보시는 건 어떠세요?” 남발 방지: 최대 1회
        String key = "시도해보시는 건 어떠세요?";
        int first = s.indexOf(key);
        if (first >= 0) {
            int second = s.indexOf(key, first + key.length());
            if (second >= 0) {
                s = s.substring(0, second) + s.substring(second + key.length());
            }
        }

        // 물음표 연속 → 1개
        s = MULTI_QUESTION.matcher(s).replaceAll("? ");

        // 공백 정리
        s = SPACES.matcher(s).replaceAll(" ").trim();

        return s;
    }

    private String sanitizeReason(String reason, PartnershipResponseDto.PartnerInfo p) {
        String r = sanitize(reason);
        if (r.isBlank()) {
            r = String.format("%s(%dm)이 가까워서 동선이 자연스러워요.", p.name(), p.distanceMeters());
        }
        return r;
    }

    /**
     * 완료형/보고체 표현을 제안형으로 보정
     */
    private String enforceSuggestionTone(String s) {
        if (s == null) return "";

        // 딱딱/완료형 → 제안형
        s = s.replaceAll("제공(됩니다|해요|합니다)", "제공을 제안드려요");
        s = s.replaceAll("진행 중입니다", "도입을 검토해보세요");
        s = s.replaceAll("진행됩니다", "진행을 제안드려요");
        s = s.replaceAll("적용됩니다", "적용을 권장드려요");
        s = s.replaceAll("받을 수 있습니다", "받도록 제안드려요");
        s = s.replaceAll("이용 가능(합니다|해요)", "이용하도록 제안드려요");
        s = s.replaceAll("운영됩니다", "운영해보세요");

        // “체류” → 쉬운 말
        s = s.replace("체류", "머무는 시간");

        // 마무리 어미 과도/중복 정리
        s = s.replaceAll("요\\?\\?$", "요?");
        return s.trim();
    }

    // ===================== 헬퍼들 =====================

    private record Candidate(Restaurant r, String type, int distanceMeters) {
    }

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

    /**
     * 카테고리 문자열을 간단히 두 축으로 정규화
     * - 카페/디저트/베이커리/커피/아이스크림/빙수 → "카페"
     * - 그 외 식사류 → "음식점"
     */
    private String toSimpleType(String raw) {
        String c = Optional.ofNullable(raw).orElse("").toLowerCase();
        String[] cafeKeys = {"카페", "커피", "디저트", "베이커리", "제과", "아이스크림", "빙수", "도넛", "브런치"};
        for (String k : cafeKeys) if (c.contains(k)) return "카페";
        String[] foodKeys = {"한식", "중식", "일식", "양식", "아시안", "분식", "피자", "치킨", "탕", "국", "면",
                "우동", "라멘", "스시", "초밥", "돈가스", "덮밥", "파스타", "스테이크", "마라"};
        for (String k : foodKeys) if (c.contains(k)) return "음식점";
        return "음식점";
    }

    private String oppositeType(String simpleType) {
        if ("카페".equals(simpleType)) return "음식점";
        if ("음식점".equals(simpleType)) return "카페";
        return "카페";
    }
}
