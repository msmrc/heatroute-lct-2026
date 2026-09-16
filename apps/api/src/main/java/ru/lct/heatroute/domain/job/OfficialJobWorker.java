package ru.lct.heatroute.domain.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.routing.OfficialCalculationResult;
import ru.lct.heatroute.domain.routing.OfficialCalculationService;
import ru.lct.heatroute.domain.run.OfficialRunRepository;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;
import ru.lct.heatroute.domain.topology.TopologyAnalysisService;

@Component
public class OfficialJobWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(OfficialJobWorker.class);
    private final UUID workerId = UUID.randomUUID();
    private final OfficialJobRepository repository;
    private final TopologyAnalysisService topologyAnalysisService;
    private final OfficialCalculationService calculationService;
    private final OfficialRunRepository runRepository;
    private final ObjectMapper objectMapper;

    public OfficialJobWorker(
            OfficialJobRepository repository,
            TopologyAnalysisService topologyAnalysisService,
            OfficialCalculationService calculationService,
            OfficialRunRepository runRepository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.topologyAnalysisService = topologyAnalysisService;
        this.calculationService = calculationService;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${heatroute.jobs.poll-delay-ms:500}")
    public void poll() {
        Optional<OfficialJobView> claimed = repository.claimNext(workerId);
        claimed.ifPresent(this::execute);
    }

    private void execute(OfficialJobView job) {
        try {
            if (repository.isCancellationRequested(job.getId())) {
                cancelRun(job);
                repository.markCancelled(job.getId());
                return;
            }
            JsonNode result = executeJob(job);
            if (result == null) {
                return;
            }
            if (repository.isCancellationRequested(job.getId())) {
                cancelRun(job);
                repository.markCancelled(job.getId());
                return;
            }
            if (job.getRunId() != null) {
                runRepository.markCompleted(job.getRunId(), result);
            }
            repository.markCompleted(job.getId(), result);
        } catch (Exception exception) {
            LOGGER.error("Official job failed job_id={}", job.getId(), exception);
            String message = exception.getMessage() == null ? "Unexpected worker failure" : exception.getMessage();
            if (job.getRunId() != null) {
                try {
                    runRepository.markFailed(job.getRunId(), "JOB_EXECUTION_FAILED", truncate(message));
                } catch (Exception runFailure) {
                    LOGGER.error("Official run failure state could not be persisted run_id={}", job.getRunId(), runFailure);
                }
            }
            repository.markFailed(job.getId(), "JOB_EXECUTION_FAILED", truncate(message));
        }
    }

    private JsonNode executeJob(OfficialJobView job) {
        if ("topology_analysis".equals(job.getJobType())) {
            TopologyAnalysis analysis = topologyAnalysisService.analyze(job.getImportId());
            if (analysis == null) {
                repository.markFailed(job.getId(), "IMPORT_NOT_VALID", "The official import is unavailable or invalid");
                return null;
            }
            return objectMapper.valueToTree(analysis);
        }
        if ("calculation".equals(job.getJobType()) && job.getRunId() != null) {
            runRepository.markRunning(job.getRunId());
            OfficialCalculationResult result = calculationService.calculate(job.getImportId());
            if (result == null) {
                runRepository.markFailed(
                        job.getRunId(), "IMPORT_NOT_VALID", "The official import is unavailable or invalid");
                repository.markFailed(job.getId(), "IMPORT_NOT_VALID", "The official import is unavailable or invalid");
                return null;
            }
            return objectMapper.valueToTree(result);
        }
        repository.markFailed(job.getId(), "UNSUPPORTED_JOB_TYPE", "Unsupported official job type");
        return null;
    }

    private void cancelRun(OfficialJobView job) {
        if (job.getRunId() != null) {
            runRepository.markCancelled(job.getRunId());
        }
    }

    private String truncate(String message) {
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
