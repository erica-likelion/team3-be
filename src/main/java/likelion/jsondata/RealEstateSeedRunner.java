package likelion.jsondata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import likelion.domain.entity.RealEstate;
import likelion.jsondata.mapper.RealEstateMapper;
import likelion.jsondata.record.RealEstateJson;
import likelion.repository.RealEstateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RealEstateSeedRunner implements CommandLineRunner {

    private final ObjectMapper objectMapper;
    private final RealEstateMapper realEstateMapper;
    private final RealEstateRepository realEstateRepository;

    @Override
    public void run(String... args) throws Exception {
        // "realestate" 라는 암구호가 없으면 실행되지 않습니다.
        if (args.length < 2 || !"realestate".equalsIgnoreCase(args[0])) {
            return;
        }

        Path path = Paths.get(args[1]);
        if (!Files.exists(path)) {
            System.err.println("[realestate] 파일을 찾을 수 없습니다: " + path.toAbsolutePath());
            return;
        }

        List<RealEstateJson> jsonList = objectMapper.readValue(Files.readAllBytes(path), new TypeReference<>() {});

        System.out.println("[realestate] " + jsonList.size() + "건의 부동산 데이터를 DB에 저장합니다...");

        for (RealEstateJson json : jsonList) {
            RealEstate entity = realEstateMapper.map(json);
            realEstateRepository.save(entity);
        }

        System.out.println("[realestate] 부동산 데이터 저장 완료!");
    }
}