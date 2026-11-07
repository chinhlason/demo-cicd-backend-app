package vn.com.sonnvt.democicd;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class VaultSecretCheck {
    private final Path secretFile;

    public VaultSecretCheck(
            @Value("${app.vault-secret-file:/vault/secrets/message}") String secretFile) {
        this.secretFile = Path.of(secretFile);
    }

    @GetMapping("/secret")
    public ResponseEntity<Map<String, String>> secret() {
        try {
            String message = Files.readString(secretFile).trim();
            if (message.isBlank()) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "vault secret unavailable"));
            }
            return ResponseEntity.ok(Map.of("message", message));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "vault secret unavailable"));
        }
    }
}
