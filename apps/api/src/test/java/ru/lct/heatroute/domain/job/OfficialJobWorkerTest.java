package ru.lct.heatroute.domain.job;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.routing.OfficialCalculationService;
import ru.lct.heatroute.domain.routing.OfficialCalculationResult;
import ru.lct.heatroute.domain.run.OfficialRunRepository;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;
import ru.lct.heatroute.domain.topology.TopologyAnalysisService;

class OfficialJobWorkerTest {
    private final OfficialJobRepository repository = mock(OfficialJobRepository.class);
    private final TopologyAnalysisService topologyService = mock(TopologyAnalysisService.class);
    private final OfficialCalculationService calculationService = mock(OfficialCalculationService.class);
    private final OfficialRunRepository runRepository = mock(OfficialRunRepository.class);
    private final OfficialJobWorker worker = new OfficialJobWorker(
            repository, topologyService, calculationService, runRepository, new ObjectMapper());

    @Test
    void completesClaimedTopologyJobWithPersistedResult() {
        OfficialJobView job = runningJob();
        when(repository.claimNext(isA(UUID.class))).thenReturn(Optional.of(job));
        when(repository.isCancellationRequested(job.getId())).thenReturn(false);
        when(topologyService.analyze(job.getImportId()))
                .thenReturn(new TopologyAnalysis(1, 2, 1, Collections.emptyList(), Collections.emptyList()));

        worker.poll();

        verify(repository).markCompleted(eq(job.getId()), isA(JsonNode.class));
        verify(repository, never()).markFailed(eq(job.getId()), isA(String.class), isA(String.class));
    }

    @Test
    void honoursCancellationBeforeExpensiveWork() {
        OfficialJobView job = runningJob();
        when(repository.claimNext(isA(UUID.class))).thenReturn(Optional.of(job));
        when(repository.isCancellationRequested(job.getId())).thenReturn(true);

        worker.poll();

        verify(repository).markCancelled(job.getId());
        verify(topologyService, never()).analyze(job.getImportId());
    }

    @Test
    void completesCalculationJobAndItsImmutableRun() {
        UUID runId = UUID.randomUUID();
        OfficialJobView job = runningJob("calculation", runId);
        OfficialCalculationResult result = new OfficialCalculationResult("r4-test", 0, List.of(), null);
        when(repository.claimNext(isA(UUID.class))).thenReturn(Optional.of(job));
        when(repository.isCancellationRequested(job.getId())).thenReturn(false);
        when(calculationService.calculate(job.getImportId())).thenReturn(result);

        worker.poll();

        verify(runRepository).markRunning(runId);
        verify(runRepository).markCompleted(eq(runId), isA(JsonNode.class));
        verify(repository).markCompleted(eq(job.getId()), isA(JsonNode.class));
    }

    private OfficialJobView runningJob() {
        return runningJob("topology_analysis", null);
    }

    private OfficialJobView runningJob(String jobType, UUID runId) {
        return new OfficialJobView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                runId,
                jobType,
                "running",
                jobType,
                0,
                1,
                1,
                false,
                null,
                null,
                null,
                OffsetDateTime.now(),
                null);
    }
}
