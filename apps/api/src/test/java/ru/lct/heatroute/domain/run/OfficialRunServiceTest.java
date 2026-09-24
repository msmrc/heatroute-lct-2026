package ru.lct.heatroute.domain.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.input.OfficialImportView;
import ru.lct.heatroute.domain.job.OfficialJobRepository;
import ru.lct.heatroute.domain.job.OfficialJobView;
import ru.lct.heatroute.domain.routing.RoutingAlgorithm;
import ru.lct.heatroute.domain.routing.RoutingAlgorithmRegistry;
import ru.lct.heatroute.domain.routing.RoutePlannerTuning;

class OfficialRunServiceTest {
    private final OfficialRunRepository runRepository = mock(OfficialRunRepository.class);
    private final OfficialJobRepository jobRepository = mock(OfficialJobRepository.class);
    private final RoutingAlgorithmRegistry algorithmRegistry = mock(RoutingAlgorithmRegistry.class);
    private final OfficialRunService service = new OfficialRunService(
            runRepository, jobRepository, algorithmRegistry);

    @Test
    void returnsLatestCompletedRunForVisualDemo() {
        OfficialRunView expected = mock(OfficialRunView.class);
        when(runRepository.findLatestCompleted()).thenReturn(Optional.of(expected));

        assertThat(service.findLatestCompleted()).isSameAs(expected);
    }

    @Test
    void returnsNullWhenNoCompletedRunExists() {
        when(runRepository.findLatestCompleted()).thenReturn(Optional.empty());

        assertThat(service.findLatestCompleted()).isNull();
    }

    @Test
    void storesActualPrimaryVersionForLegacyProfileWithoutChangingItsParameters() {
        OfficialImportView imported = mock(OfficialImportView.class);
        ru.lct.heatroute.domain.input.OfficialInputReport report =
                mock(ru.lct.heatroute.domain.input.OfficialInputReport.class);
        RoutingAlgorithm algorithm = mock(RoutingAlgorithm.class);
        OfficialRunView created = mock(OfficialRunView.class);
        OfficialJobView job = mock(OfficialJobView.class);
        UUID importId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        OfficialRunParameters parameters = new OfficialRunParameters(
                null, null, false, RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL);
        when(imported.getId()).thenReturn(importId);
        when(imported.getReport()).thenReturn(report);
        when(report.getSha256()).thenReturn("input-sha");
        when(algorithm.profile()).thenReturn(RoutingAlgorithmProfile.STABLE);
        when(algorithm.version()).thenReturn(RoutePlannerTuning.STABLE_ALGORITHM_VERSION);
        OfficialRunService compatibilityService = new OfficialRunService(
                runRepository, jobRepository, new RoutingAlgorithmRegistry(List.of(algorithm)));
        when(created.getId()).thenReturn(runId);
        when(job.getId()).thenReturn(jobId);
        when(runRepository.create(importId, "input-sha", RoutePlannerTuning.STABLE_ALGORITHM_VERSION, parameters))
                .thenReturn(created);
        when(jobRepository.createCalculationJob(importId, runId)).thenReturn(job);
        when(runRepository.find(runId)).thenReturn(Optional.of(created));

        assertThat(compatibilityService.create(imported, parameters)).isSameAs(created);

        verify(runRepository).create(importId, "input-sha", RoutePlannerTuning.STABLE_ALGORITHM_VERSION, parameters);
        assertThat(parameters.getAlgorithmProfile()).isEqualTo(RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL);
        verify(runRepository).attachJob(eq(runId), eq(jobId));
    }
}
