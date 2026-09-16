package ru.lct.heatroute.domain.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;
import ru.lct.heatroute.domain.topology.TopologyAnalysisService;

@Component
public class OfficialJobWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(OfficialJobWorker.class);
    private final UUID workerId = UUID.randomUUID();
    private final OfficialJobRepository repository;
    private final TopologyAnalysisService topologyAnalysisService;
    private final ObjectMapper objectMapper;

    public OfficialJobWorker(
            OfficialJobRepository repository,
            TopologyAnalysisService topologyAnalysisService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.topologyAnalysisService = topologyAnalysisService;
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
                repository.markCancelled(job.getId());
                return;
            }
            if (!"topology_analysis".equals(job.getJobType())) {
                repository.markFailed(job.getId(), "UNSUPPORTED_JOB_TYPE", "Unsupported official job type");
                return;
            }
            TopologyAnalysis analysis = topologyAnalysisService.analyze(job.getImportId());
            if (analysis == null) {
                repository.markFailed(job.getId(), "IMPORT_NOT_VALID", "The official import is unavailable or invalid");
                return;
            }
            if (repository.isCancellationRequested(job.getId())) {
                repository.markCancelled(job.getId());
                return;
            }
            JsonNode result = objectMapper.valueToTree(analysis);
            repository.markCompleted(job.getId(), result);
        } catch (Exception exception) {
            LOGGER.error("Official job failed job_id={}", job.getId(), exception);
            String message = exception.getMessage() == null ? "Unexpected worker failure" : exception.getMessage();
            repository.markFailed(job.getId(), "JOB_EXECUTION_FAILED", truncate(message));
        }
    }

    private String truncate(String message) {
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
