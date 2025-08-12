package likelion.config;

import com.theokanning.openai.service.OpenAiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class OpenAiConfig {

    @Value("${openai.api-key}")
    private String openAiApiKey;

    @Bean
    public OpenAiService openAiService() {
        // AI 응답이 길어질 경우를 대비해 타임아웃을 60초로 넉넉하게 설정
        return new OpenAiService(openAiApiKey, Duration.ofSeconds(60));
    }
}