package likelion.jsondata;

import com.fasterxml.jackson.databind.ObjectMapper;
import likelion.domain.entity.Restaurant;
import likelion.jsondata.mapper.RestaurantMapper;
import likelion.jsondata.record.RestaurantJson;
import likelion.repository.RestaurantRepository;
import lombok.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@RequiredArgsConstructor
public class SeedRunner implements CommandLineRunner {
    private final ObjectMapper om;
    private final RestaurantMapper mapper;
    private final RestaurantRepository repository;

    @Override
    public void run(String... args) throws Exception{
        if (args.length < 2 || !"restaurants".equalsIgnoreCase(args[0])) return;
        Path path = Paths.get(args[1]);
        RestaurantJson[] arr = om.readValue(Files.readAllBytes(path), RestaurantJson[].class);
        //중복 기준은 PK 그 url 짤라서 만든 거
        for(RestaurantJson j : arr){
            Restaurant r = mapper.map(j);
            repository.save(r);
        }
    }
}
