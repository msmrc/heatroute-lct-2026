package ru.lct.heatroute.api.official;

import io.swagger.v3.oas.annotations.Operation;
import java.time.Duration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/official/contracts")
public class OfficialContractController {
    private static final MediaType JSON_SCHEMA = MediaType.parseMediaType("application/schema+json");
    private static final CacheControl IMMUTABLE_CACHE = CacheControl.maxAge(Duration.ofDays(365))
            .cachePublic();

    @GetMapping(value = "/input.schema.json", produces = "application/schema+json")
    @Operation(summary = "Download the strict seven-type organizer input JSON Schema")
    public ResponseEntity<Resource> strictInputSchema() {
        return schema("lct-2026-input.schema.json");
    }

    @GetMapping(value = "/provided-dataset.schema.json", produces = "application/schema+json")
    @Operation(summary = "Download the explicit supplied-dataset compatibility JSON Schema")
    public ResponseEntity<Resource> providedDatasetSchema() {
        return schema("lct-2026-provided-dataset.schema.json");
    }

    @GetMapping(value = "/output.schema.json", produces = "application/schema+json")
    @Operation(summary = "Download the strict seven-type result JSON Schema")
    public ResponseEntity<Resource> outputSchema() {
        return schema("lct-2026-output.schema.json");
    }

    private ResponseEntity<Resource> schema(String filename) {
        Resource resource = new ClassPathResource("contracts/" + filename);
        if (!resource.exists()) {
            throw new IllegalStateException("Packaged contract schema is missing: " + filename);
        }
        return ResponseEntity.ok()
                .contentType(JSON_SCHEMA)
                .cacheControl(IMMUTABLE_CACHE)
                .header("Content-Disposition", "inline; filename=\"" + filename + "\"")
                .body(resource);
    }
}
