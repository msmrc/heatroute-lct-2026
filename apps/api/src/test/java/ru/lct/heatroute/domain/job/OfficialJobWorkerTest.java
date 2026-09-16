package ru.lct.heatroute.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import ru.lct.heatroute.domain.routing.OfficialCalculationService;
import ru.lct.heatroute.domain.routing.OfficialCalculationResult;
import ru.lct.heatroute.domain.routing.RouteConnection;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteVariant;
import ru.lct.heatroute.domain.run.OfficialRunRepository;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.run.OfficialRunView;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;
import ru.lct.heatroute.domain.topology.TopologyAnalysisService;

class OfficialJobWorkerTest {
    private final OfficialJobRepository repository = mock(OfficialJobRepository.class);
    private final TopologyAnalysisService topologyService = mock(TopologyAnalysisService.class);
    private final OfficialCalculationService calculationService = mock(OfficialCalculationService.class);
    private final OfficialRunRepository runRepository = mock(OfficialRunRepository.class);
    private final OfficialJobWorker worker = new OfficialJobWorker(
            repository,
            topologyService,
            calculationService,
            runRepository,
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE),
            new SyncTaskExecutor(),
            1);

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
        OfficialRunParameters parameters = prepareRun(runId);
        when(repository.claimNext(isA(UUID.class))).thenReturn(Optional.of(job));
        when(repository.isCancellationRequested(job.getId())).thenReturn(false);
        when(calculationService.calculate(job.getImportId(), parameters)).thenReturn(result);

        worker.poll();

        verify(runRepository).markRunning(runId);
        verify(runRepository).markCompleted(eq(runId), isA(JsonNode.class));
        verify(repository).markCompleted(eq(job.getId()), isA(JsonNode.class));
    }

    @Test
    void persistsTheSelectedTieInTargetInsideTheImmutableRunResult() {
        UUID runId = UUID.randomUUID();
        OfficialJobView job = runningJob("calculation", runId);
        RouteNode tieIn = new RouteNode(
                "tie-in-1",
                "new_tie_in_chamber",
                new RouteCoordinate(413_000, 6_181_000),
                true,
                true,
                2,
                "heat-network-42");
        RouteVariant variant = new RouteVariant(
                "shared",
                "shared_trunk",
                List.of(tieIn),
                List.of(),
                List.of(new RouteConnection(
                        "oks-1", "connection-1", BigDecimal.ONE, "connected", null)),
                BigDecimal.ZERO,
                List.of(),
                List.of(),
                null,
                null,
                1);
        OfficialCalculationResult result = new OfficialCalculationResult(
                "r4-test", 1, List.of(variant), "shared");
        OfficialRunParameters parameters = prepareRun(runId);
        when(repository.claimNext(isA(UUID.class))).thenReturn(Optional.of(job));
        when(repository.isCancellationRequested(job.getId())).thenReturn(false);
        when(calculationService.calculate(job.getImportId(), parameters)).thenReturn(result);

        worker.poll();

        ArgumentCaptor<JsonNode> persisted = ArgumentCaptor.forClass(JsonNode.class);
        verify(runRepository).markCompleted(eq(runId), persisted.capture());
        JsonNode stored = persisted.getValue();
        assertThat(stored.path("preferred_variant_id").asText()).isEqualTo("shared");
        assertThat(stored.path("variants").path(0).path("nodes").path(0).path("target_id").asText())
                .isEqualTo("heat-network-42");
    }

    @Test
    void renewsTheLeaseOfAnActiveLongRunningJob() throws Exception {
        OfficialJobView job = runningJob();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        OfficialJobWorker concurrentWorker = new OfficialJobWorker(
                repository,
                topologyService,
                calculationService,
                runRepository,
                new ObjectMapper(),
                command -> new Thread(command).start(),
                1);
        when(repository.claimNext(isA(UUID.class))).thenReturn(Optional.of(job));
        when(repository.isCancellationRequested(job.getId())).thenReturn(false);
        when(topologyService.analyze(job.getImportId())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return new TopologyAnalysis(1, 1, 0, Collections.emptyList(), Collections.emptyList());
        });
        when(repository.renewLease(eq(job.getId()), isA(UUID.class))).thenReturn(true);

        concurrentWorker.poll();
        entered.await();
        concurrentWorker.heartbeat();
        release.countDown();

        verify(repository).renewLease(eq(job.getId()), isA(UUID.class));
        verify(repository, timeout(2_000)).markCompleted(eq(job.getId()), isA(JsonNode.class));
    }

    @Test
    void neverClaimsMoreJobsThanTheConfiguredWorkerBound() throws Exception {
        OfficialJobView first = runningJob();
        OfficialJobView second = runningJob();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        OfficialJobWorker concurrentWorker = new OfficialJobWorker(
                repository,
                topologyService,
                calculationService,
                runRepository,
                new ObjectMapper(),
                command -> new Thread(command).start(),
                2);
        when(repository.claimNext(isA(UUID.class)))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second));
        when(repository.isCancellationRequested(isA(UUID.class))).thenReturn(false);
        when(topologyService.analyze(isA(UUID.class))).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return new TopologyAnalysis(1, 1, 0, Collections.emptyList(), Collections.emptyList());
        });

        concurrentWorker.poll();
        concurrentWorker.poll();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        concurrentWorker.poll();

        verify(repository, times(2)).claimNext(isA(UUID.class));
        release.countDown();
        verify(repository, timeout(2_000)).markCompleted(eq(first.getId()), isA(JsonNode.class));
        verify(repository, timeout(2_000)).markCompleted(eq(second.getId()), isA(JsonNode.class));
    }

    private OfficialJobView runningJob() {
        return runningJob("topology_analysis", null);
    }

    private OfficialRunParameters prepareRun(UUID runId) {
        OfficialRunParameters parameters = OfficialRunParameters.defaults();
        OfficialRunView run = mock(OfficialRunView.class);
        when(run.getParameters()).thenReturn(parameters);
        when(runRepository.find(runId)).thenReturn(Optional.of(run));
        return parameters;
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
