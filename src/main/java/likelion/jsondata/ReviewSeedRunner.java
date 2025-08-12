package likelion.jsondata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import likelion.jsondata.record.ReviewJson;
import likelion.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ReviewSeedRunner implements CommandLineRunner {

    private final ObjectMapper om;
    private final ReviewService reviewIngestService;

    @Override
    public void run(String... args) throws Exception {
        if (args.length < 2 || !"reviews".equalsIgnoreCase(args[0])) {
            return; // 다른 러너용 실행이니 패스
        }
        Path path = Paths.get(args[1]);
        System.out.println("[reviews] input = " + path.toAbsolutePath());
        if (!Files.exists(path)) {
            System.err.println("[reviews] file not found: " + path.toAbsolutePath());
            return;
        }

        List<ReviewJson> rows = om.readValue(Files.readAllBytes(path), new TypeReference<>() {});
        System.out.println("[reviews] rows = " + rows.size());
        reviewIngestService.ingest(rows);
        System.out.println("[reviews] ingest done");
    }
}
