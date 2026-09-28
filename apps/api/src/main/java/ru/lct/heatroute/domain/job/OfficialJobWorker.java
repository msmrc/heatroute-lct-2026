package ru.lct.heatroute.domain.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.routing.OfficialCalculationResult;
import ru.lct.heatroute.domain.routing.OfficialCalculationService;
import ru.lct.heatroute.domain.routing.RoutingEngineVersionUnavailableException;
import ru.lct.heatroute.domain.run.OfficialRunRepository;
import ru.lct.heatroute.domain.run.OfficialRunView;
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
    private final TaskExecutor taskExecutor;
    private final Semaphore availableSlots;
    private final Set<UUID> activeJobs = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Thread> activeThreads = new ConcurrentHashMap<>();

    public OfficialJobWorker(
            OfficialJobRepository repository,
            TopologyAnalysisService topologyAnalysisService,
            OfficialCalculationService calculationService,
            OfficialRunRepository runRepository,
            ObjectMapper objectMapper,
            @Qualifier("officialJobTaskExecutor") TaskExecutor taskExecutor,
            @Value("${heatroute.jobs.concurrency:2}") int concurrency) {
        this.repository = repository;
        this.topologyAnalysisService = topologyAnalysisService;
        this.calculationService = calculationService;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.availableSlots = new Semaphore(Math.max(1, Math.min(concurrency, 16)));
    }

    @Scheduled(fixedDelayString = "${heatroute.jobs.poll-delay-ms:500}")
    public void poll() {
        repository.finalizeRequestedCancellations();
        if (!availableSlots.tryAcquire()) {
            return;
        }
        Optional<OfficialJobView> claimed = repository.claimNext(workerId);
        if (claimed.isEmpty()) {
            availableSlots.release();
            return;
        }
        OfficialJobView job = claimed.get();
        activeJobs.add(job.getId());
        try {
            taskExecutor.execute(() -> executeClaimed(job));
        } catch (RuntimeException exception) {
            activeJobs.remove(job.getId());
            availableSlots.release();
            repository.markFailed(job.getId(), "WORKER_SATURATED", "Worker could not start the claimed job");
            throw exception;
        }
    }

    @Scheduled(fixedDelayString = "${heatroute.jobs.heartbeat-delay-ms:60000}")
    public void heartbeat() {
        for (UUID jobId : activeJobs) {
            if (!repository.renewLease(jobId, workerId)) {
                LOGGER.warn("Official job lease could not be renewed job_id={}", jobId);
            }
        }
    }

    @Scheduled(fixedDelayString = "${heatroute.jobs.cancel-delay-ms:500}")
    public void interruptCancelledJobs() {
        activeThreads.forEach((jobId, thread) -> {
            if (repository.isCancellationRequested(jobId)) {
                thread.interrupt();
            }
        });
    }

    private void executeClaimed(OfficialJobView job) {
        activeThreads.put(job.getId(), Thread.currentThread());
        try {
            execute(job);
        } finally {
            activeThreads.remove(job.getId());
            activeJobs.remove(job.getId());
            availableSlots.release();
        }
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
        } catch (CancellationException exception) {
            Thread.interrupted();
            cancelRun(job);
            repository.markCancelled(job.getId());
        } catch (RoutingEngineVersionUnavailableException exception) {
            LOGGER.warn("Queued routing engine is unavailable job_id={} requested={} available={}",
                    job.getId(), exception.getRequestedVersion(), exception.getAvailableVersion());
            if (job.getRunId() != null) {
                runRepository.markFailed(job.getRunId(), "ENGINE_VERSION_UNAVAILABLE",
                        "The queued routing engine version is not available in this release");
            }
            repository.markFailed(job.getId(), "ENGINE_VERSION_UNAVAILABLE",
                    "The queued routing engine version is not available in this release");
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
            OfficialRunView run = runRepository.find(job.getRunId())
                    .orElseThrow(() -> new IllegalStateException("Calculation run was not found"));
            OfficialCalculationResult result = calculationService.calculate(
                    job.getImportId(), run.getParameters(), run.getAlgorithmVersion());
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
