package likelion.jsondata.mapper;

import likelion.domain.entity.RealEstate;
import likelion.jsondata.record.RealEstateJson;
import org.springframework.stereotype.Component;

@Component
public class RealEstateMapper {

    public RealEstate map(RealEstateJson json) {
        RealEstate entity = new RealEstate();
        entity.setPriceManwon(json.priceManwon());
        entity.setAreaPyeng(json.areaPyeng());
        entity.setFloor(json.floor());
        entity.setAddress(json.address());
        return entity;
    }
}