package ru.lct.heatroute.api.official;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.heatroute.api.error.ApiException;
import ru.lct.heatroute.domain.input.OfficialImportService;
import ru.lct.heatroute.domain.input.OfficialImportView;
import ru.lct.heatroute.domain.job.OfficialJobService;
import ru.lct.heatroute.domain.job.OfficialJobView;

@RestController
@RequestMapping("/api/v1/official")
@Tag(name = "official-jobs")
public class OfficialJobController {
    private final OfficialImportService importService;
    private final OfficialJobService jobService;

    public OfficialJobController(OfficialImportService importService, OfficialJobService jobService) {
        this.importService = importService;
        this.jobService = jobService;
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
}
