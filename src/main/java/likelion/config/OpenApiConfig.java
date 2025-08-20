package likelion.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Value("${SWAGGER_SERVER_URL:http://localhost:8080}")
    private String serverUrl;

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().addServersItem(new Server().url(serverUrl)).info(new Info().title("Team3 BE API").version("v1").description("창업 분석 API 문서 (대학가 상권 분석)"));
    }
}
