package ru.lct.heatroute.api.official;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatroute.api.error.ApiException;
import ru.lct.heatroute.domain.input.OfficialGeoJsonInspector;
import ru.lct.heatroute.domain.input.OfficialInputFormatException;
import ru.lct.heatroute.domain.input.OfficialInputReport;
import ru.lct.heatroute.domain.input.OfficialImportService;
import ru.lct.heatroute.domain.input.OfficialImportView;

@RestController
@RequestMapping("/api/v1/official/imports")
@Tag(name = "official-import")
public class OfficialImportController {
    private final OfficialGeoJsonInspector inspector;
    private final OfficialImportService importService;

    public OfficialImportController(
            OfficialGeoJsonInspector inspector,
            OfficialImportService importService) {
        this.inspector = inspector;
        this.importService = importService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            operationId = "createOfficialImport",
            summary = "Validate and persist an official LCT GeoJSON collection in PostGIS")
    public ResponseEntity<OfficialImportView> create(@RequestPart("file") MultipartFile file) {
        requireNonEmpty(file);
        try {
            OfficialImportView created = importService.create(file, safeFilename(file.getOriginalFilename()));
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (OfficialInputFormatException exception) {
            throw invalidGeoJson(file, exception);
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_READ_FAILED", "Cannot read uploaded GeoJSON");
        }
    }

    @GetMapping("/{importId}")
    @Operation(operationId = "getOfficialImport", summary = "Read a durable official import report")
    public OfficialImportView get(@PathVariable UUID importId) {
        OfficialImportView result = importService.find(importId);
        if (result == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", "Official import was not found");
        }
        return result;
    }

    @PostMapping(value = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            operationId = "inspectOfficialGeoJson",
            summary = "Stream and validate the official LCT GeoJSON input contract")
    public OfficialInputReport inspect(@RequestPart("file") MultipartFile file) {
        requireNonEmpty(file);
        try (InputStream input = file.getInputStream()) {
            return inspector.inspect(input);
        } catch (OfficialInputFormatException exception) {
            throw invalidGeoJson(file, exception);
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_READ_FAILED", "Cannot read uploaded GeoJSON");
        }
    }

    private void requireNonEmpty(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_UPLOAD", "GeoJSON file is empty");
        }
    }

    private ApiException invalidGeoJson(MultipartFile file, OfficialInputFormatException exception) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_GEOJSON",
                exception.getMessage(),
                Collections.singletonMap("filename", safeFilename(file.getOriginalFilename())));
    }

    private String safeFilename(String filename) {
        if (filename == null) {
            return "unknown";
        }
        String normalized = filename.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return normalized.substring(separator + 1);
    }
}
