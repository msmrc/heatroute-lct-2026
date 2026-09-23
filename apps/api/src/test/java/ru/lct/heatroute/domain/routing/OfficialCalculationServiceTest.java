package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.lct.heatroute.domain.input.OfficialImportRepository;
import ru.lct.heatroute.domain.input.OfficialImportView;
import ru.lct.heatroute.domain.input.OfficialInputReport;
import ru.lct.heatroute.domain.topology.ExistingNetworkTopologyAnalyzer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.OfficialFeatureRepository;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

class OfficialCalculationServiceTest {
    private final OfficialImportRepository importRepository = mock(OfficialImportRepository.class);
    private final OfficialFeatureRepository featureRepository = mock(OfficialFeatureRepository.class);
    private final ExistingNetworkTopologyAnalyzer topologyAnalyzer = mock(ExistingNetworkTopologyAnalyzer.class);
    private final RoutingAlgorithmRegistry algorithmRegistry = mock(RoutingAlgorithmRegistry.class);
    private final RoutingAlgorithm routingAlgorithm = mock(RoutingAlgorithm.class);
    private final OfficialCalculationService service = new OfficialCalculationService(
            importRepository, featureRepository, topologyAnalyzer, algorithmRegistry);

    @Test
    void loadsCalculationModelThroughBoundedRepositoryConsumer() {
        UUID importId = UUID.randomUUID();
        OfficialImportView imported = mock(OfficialImportView.class);
        OfficialInputReport report = mock(OfficialInputReport.class);
        ImportedOfficialFeature first = mock(ImportedOfficialFeature.class);
        ImportedOfficialFeature second = mock(ImportedOfficialFeature.class);
        TopologyAnalysis topology = mock(TopologyAnalysis.class);
        OfficialCalculationResult expected = mock(OfficialCalculationResult.class);
        when(imported.getState()).thenReturn("valid");
        when(imported.getReport()).thenReturn(report);
        when(report.getInputProfile()).thenReturn("baseline_input");
        when(importRepository.find(importId)).thenReturn(Optional.of(imported));
        doAnswer(invocation -> {
                    Consumer<ImportedOfficialFeature> consumer = invocation.getArgument(2);
                    consumer.accept(first);
                    consumer.accept(second);
                    return null;
                })
                .when(featureRepository)
                .forEachCalculationCoreByImport(eq(importId), eq(OfficialFeatureRepository.DEFAULT_PAGE_SIZE), any());
        when(topologyAnalyzer.analyze(any())).thenReturn(topology);
        when(algorithmRegistry.require(any())).thenReturn(routingAlgorithm);
        when(routingAlgorithm.plan(any(), eq(topology), any(), eq("baseline_input"), any()))
                .thenReturn(expected);

        OfficialCalculationResult actual = service.calculate(importId);

        ArgumentCaptor<List<ImportedOfficialFeature>> features = ArgumentCaptor.forClass(List.class);
        verify(topologyAnalyzer).analyze(features.capture());
        assertThat(features.getValue()).containsExactly(first, second);
        verify(featureRepository).forEachCalculationCoreByImport(
                eq(importId), eq(OfficialFeatureRepository.DEFAULT_PAGE_SIZE), any());
        verify(algorithmRegistry).require(ru.lct.heatroute.domain.run.RoutingAlgorithmProfile.STABLE);
        assertThat(actual).isSameAs(expected);
    }

    @Test
    void doesNotLoadFeaturesForInvalidImport() {
        UUID importId = UUID.randomUUID();
        OfficialImportView imported = mock(OfficialImportView.class);
        when(imported.getState()).thenReturn("invalid");
        when(importRepository.find(importId)).thenReturn(Optional.of(imported));

        assertThat(service.calculate(importId)).isNull();

        verifyNoInteractions(featureRepository, topologyAnalyzer, algorithmRegistry, routingAlgorithm);
    }
}
