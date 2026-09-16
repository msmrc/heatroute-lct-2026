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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;
import ru.lct.heatroute.domain.topology.TopologyAnalysisService;

class OfficialJobWorkerTest {
    private final OfficialJobRepository repository = mock(OfficialJobRepository.class);
    private final TopologyAnalysisService topologyService = mock(TopologyAnalysisService.class);
    private final OfficialJobWorker worker = new OfficialJobWorker(repository, topologyService, new ObjectMapper());

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

    private OfficialJobView runningJob() {
        return new OfficialJobView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "topology_analysis",
                "running",
                "topology_analysis",
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
