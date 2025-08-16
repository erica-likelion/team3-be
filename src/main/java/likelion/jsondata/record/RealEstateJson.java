package likelion.jsondata.record;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// JSON에 없는 필드가 있어도 무시하고 진행하도록 허용(floor같은게 없는 데이터도 있어서 그렇습니다)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RealEstateJson(
        Integer priceManwon,
        Double areaPyeng,
        Integer floor,
        String address
) {
}