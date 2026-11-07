package vn.com.sonnvt.democicd;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class VaultSecretCheck {
    private final String message;

    public VaultSecretCheck(
            @Value("${app.secret-message:}") String message) {
        this.message = message;
    }

    @GetMapping("/secret")
    public ResponseEntity<Map<String, String>> secret() {
        if (message.isBlank()) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "vault secret unavailable"));
        }
        return ResponseEntity.ok(Map.of("message", message));
    }
}
