package likelion.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthCheckController {

    @Value("${server.env}")
    private String env;

    @Value("${server.serverAddress}")
    private String serverAddr;

    @GetMapping("/health")
    public ResponseEntity<?> healthCheck(){
        Map<String, String> responseData = new HashMap<>();
        //해당 서버가 열려 있는지 확인할 때 사용
        responseData.put("env", env);
        responseData.put("serverAddress", serverAddr);
        return ResponseEntity.ok(responseData);
    }

    @GetMapping("/env")
    public ResponseEntity<?> getEnv(){
        Map<String, String> responseData = new HashMap<>();

        return ResponseEntity.ok(env);
    }
}
