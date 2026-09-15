package ru.lct.heatroute.api.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collections;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "health")
public class HealthController {
    private final ReadinessService readinessService;

    public HealthController(ReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping("/live")
    @Operation(operationId = "live", summary = "Process liveness")
    public Map<String, String> live() {
        return Collections.singletonMap("status", "alive");
    }

    @GetMapping("/ready")
    @Operation(operationId = "ready", summary = "PostGIS readiness")
    public ResponseEntity<ReadinessResponse> ready() {
        ReadinessResponse response = readinessService.check();
        return response.isReady()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(503).body(response);
    }
}
