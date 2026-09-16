package ru.lct.heatroute.api.official;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.lct.heatroute.api.error.ApiException;
import ru.lct.heatroute.domain.input.OfficialImportService;
import ru.lct.heatroute.domain.input.OfficialImportView;
import ru.lct.heatroute.domain.export.OfficialExportPayload;
import ru.lct.heatroute.domain.export.OfficialExportService;
import ru.lct.heatroute.domain.job.OfficialJobService;
import ru.lct.heatroute.domain.job.OfficialJobView;
import ru.lct.heatroute.domain.run.OfficialRunService;
import ru.lct.heatroute.domain.run.OfficialRunView;

@RestController
@RequestMapping("/api/v1/official")
@Tag(name = "official-jobs")
public class OfficialJobController {
    private final OfficialImportService importService;
    private final OfficialJobService jobService;
    private final OfficialRunService runService;
    private final OfficialExportService exportService;

    public OfficialJobController(
            OfficialImportService importService,
            OfficialJobService jobService,
            OfficialRunService runService,
            OfficialExportService exportService) {
        this.importService = importService;
        this.jobService = jobService;
        this.runService = runService;
        this.exportService = exportService;
    }

    @PostMapping("/imports/{importId}/jobs/topology")
    @Operation(operationId = "createTopologyJob", summary = "Queue a durable topology-analysis job")
    public ResponseEntity<OfficialJobView> createTopologyJob(@PathVariable UUID importId) {
        OfficialImportView imported = importService.find(importId);
        if (imported == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", "Official import was not found");
        }
        if (!"valid".equals(imported.getState())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IMPORT_NOT_VALID",
                    "A topology job requires a valid official import");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobService.createTopologyJob(importId));
    }

    @PostMapping("/imports/{importId}/runs")
    @Operation(operationId = "createOfficialRun", summary = "Queue an immutable all-demand route calculation")
    public ResponseEntity<OfficialRunView> createRun(@PathVariable UUID importId) {
        OfficialImportView imported = requireValidImport(importId, "A calculation run requires a valid official import");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(runService.create(imported));
    }

    @GetMapping("/runs/latest")
    @Operation(operationId = "getLatestCompletedOfficialRun", summary = "Read the latest completed calculation for the visual demo")
    public OfficialRunView getLatestCompletedRun() {
        OfficialRunView run = runService.findLatestCompleted();
        if (run == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "No completed official run was found");
        }
        return run;
    }

    @GetMapping("/runs/{runId}")
    @Operation(operationId = "getOfficialRun", summary = "Read immutable calculation run state and result")
    public OfficialRunView getRun(@PathVariable UUID runId) {
        OfficialRunView run = runService.find(runId);
        if (run == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Official run was not found");
        }
        return run;
    }

    @GetMapping(value = "/runs/{runId}/export", produces = "application/geo+json")
    @Operation(operationId = "downloadOfficialRun", summary = "Stream the strict seven-type official GeoJSON")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable UUID runId,
            @RequestParam(name = "variant_id", required = false) String variantId) {
        final OfficialExportPayload payload;
        try {
            payload = exportService.prepare(runId, variantId);
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith("OFFICIAL_EXPORT_INCOMPLETE")) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "OFFICIAL_EXPORT_INCOMPLETE",
                        "Official export requires complete reconstruction inputs and a ranked result");
            }
            throw exception;
        }
        if (payload == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Official run was not found");
        }
        StreamingResponseBody body = payload::writeTo;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=heatroute-" + runId + ".geojson")
                .body(body);
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(operationId = "getOfficialJob", summary = "Read durable official job state and result")
    public OfficialJobView get(@PathVariable UUID jobId) {
        OfficialJobView job = jobService.find(jobId);
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Official job was not found");
        }
        return job;
    }

    @DeleteMapping("/jobs/{jobId}")
    @Operation(operationId = "cancelOfficialJob", summary = "Request cooperative cancellation")
    public OfficialJobView cancel(@PathVariable UUID jobId) {
        OfficialJobView job = jobService.cancel(jobId);
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Official job was not found");
        }
        return job;
    }

    private OfficialImportView requireValidImport(UUID importId, String invalidMessage) {
        OfficialImportView imported = importService.find(importId);
        if (imported == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", "Official import was not found");
        }
        if (!"valid".equals(imported.getState())) {
            throw new ApiException(HttpStatus.CONFLICT, "IMPORT_NOT_VALID", invalidMessage);
        }
        return imported;
    }
}
