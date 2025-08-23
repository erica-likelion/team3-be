package likelion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Schema(description = "매장 이름 받기")
@Getter @Setter
@NoArgsConstructor
public class PartnershipRequestDto {

    private String storeName;
}
