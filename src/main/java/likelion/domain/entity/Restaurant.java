package likelion.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "restaurant")
@RequiredArgsConstructor
public class Restaurant {
    //id를 자동생성 말고 url 뒤에 자른 걸로 쓰고.
    //리뷰 만들 때 외래 키로 사용.
    @Id @Column(name="kakao_place_id", nullable = false) Long kakaoPlaceId;

    @Column(name="resaurant_name")
    String restaurantName;

    @Column(name="category") String category;

    @Column(name="rating")
    BigDecimal rating;

    @Column(name="rating_count") Integer ratingCount;

    @Column(name="review_count") Integer reviewCount;

    @Column(name="road_address") String roadAddress;

    @Column(name="number_address") String numberAddress;

    @Column(name="business_time") String businessTime;

    @Column(name="kakao_url") String kakaoUrl;

    public Long getKakaoPlaceId() {
        return kakaoPlaceId;
    }

    public String getName() {
        return restaurantName;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public Integer getRatingCount() {
        return ratingCount;
    }

    public Integer getReviewCount() {
        return reviewCount;
    }

    public String getRoadAddress() {
        return roadAddress;
    }

    public String getNumberAddress() {
        return numberAddress;
    }

    public String getBusinessTime() {
        return businessTime;
    }

    public String getKakaoUrl() {
        return kakaoUrl;
    }

    public void setRestaurantName(String restaurantName) {
        this.restaurantName = restaurantName;
    }

    public void setKakaoPlaceId(Long kakaoPlaceId) {
        this.kakaoPlaceId = kakaoPlaceId;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setRating(BigDecimal rating) {
        this.rating = rating;
    }

    public void setRatingCount(Integer ratingCount) {
        this.ratingCount = ratingCount;
    }

    public void setReviewCount(Integer reviewCount) {
        this.reviewCount = reviewCount;
    }

    public void setRoadAddress(String roadAddress) {
        this.roadAddress = roadAddress;
    }

    public void setNumberAddress(String numberAddress) {
        this.numberAddress = numberAddress;
    }

    public void setBusinessTime(String businessTime) {
        this.businessTime = businessTime;
    }

    public void setKakaoUrl(String kakaoUrl) {
        this.kakaoUrl = kakaoUrl;
    }
}
