package likelion.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "real_estate")
@Getter
@Setter
@NoArgsConstructor
public class RealEstate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer priceManwon; // 매매가격(만원)
    private Double areaPyeng;    // 평수
    private Integer floor;       // 층
    private String address;      // 주소 (예: "안산시 상록구 사동")
}