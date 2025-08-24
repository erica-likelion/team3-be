package likelion.domain.entity;

public enum StoreCategory {
    CAFE_DESSERT("카페/디저트"),
    PIZZA_CHICKEN("피자/치킨"),
    BAR_ALCOHOL("주점/술집"),
    FAST_FOOD("패스트푸드"),
    KOREAN("한식"),
    ASIAN("아시안"),
    WESTERN("양식"),
    CHINESE("중식"),
    JAPANESE("일식");

    private final String displayName;

    StoreCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
