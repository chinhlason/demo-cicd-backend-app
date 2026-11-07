package vn.com.sonnvt.democicd;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthCheck {
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of("healthy", "pong-v2"));
    }
}
