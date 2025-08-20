package likelion.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;


@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Team3 BE API",
                version = "v1",
                description = "창업 분석 API 문서 (대학가 상권 분석)"
        ),
        servers = {
                @Server(url = "http://3.34.244.253", description = "배포 서버"),
        }
)
public class OpenApiConfig {

}
