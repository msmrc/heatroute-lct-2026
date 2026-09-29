package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.domain.input.OfficialImportRepository;
import ru.lct.heatroute.domain.input.OfficialImportView;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ExistingNetworkTopologyAnalyzer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.OfficialFeatureRepository;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

@Service
public class OfficialCalculationService {
    private final OfficialImportRepository importRepository;
    private final OfficialFeatureRepository featureRepository;
    private final ExistingNetworkTopologyAnalyzer topologyAnalyzer;
    private final RoutingAlgorithm algorithm;

    public OfficialCalculationService(
            OfficialImportRepository importRepository,
            OfficialFeatureRepository featureRepository,
            ExistingNetworkTopologyAnalyzer topologyAnalyzer,
            RoutingAlgorithm algorithm) {
        this.importRepository = importRepository;
        this.featureRepository = featureRepository;
        this.topologyAnalyzer = topologyAnalyzer;
        this.algorithm = algorithm;
    }

    @Transactional(readOnly = true)
    public OfficialCalculationResult calculate(UUID importId) {
        return calculate(importId, OfficialRunParameters.defaults());
    }

    @Transactional(readOnly = true)
    public OfficialCalculationResult calculate(UUID importId, OfficialRunParameters parameters) {
        return calculate(importId, parameters, null);
    }

    /** Исполняет queued run только сохранённой версией алгоритма; null оставлен для синхронных вызовов. */
    public OfficialCalculationResult calculate(
            UUID importId, OfficialRunParameters parameters, String expectedAlgorithmVersion) {
        OfficialImportView imported = importRepository.find(importId).orElse(null);
        if (imported == null || !"valid".equals(imported.getState())) {
            return null;
        }
        if (expectedAlgorithmVersion != null
                && !expectedAlgorithmVersion.equals(algorithm.version())) {
            throw new RoutingEngineVersionUnavailableException(
                    expectedAlgorithmVersion, algorithm.version());
        }
        // Core network and demand types are materialized; bulky restrictions and existing OKS
        // geometries remain behind the PostGIS windowed routing source.
        List<ImportedOfficialFeature> features = new ArrayList<>();
        featureRepository.forEachCalculationCoreByImport(
                importId, OfficialFeatureRepository.DEFAULT_PAGE_SIZE, features::add);
        TopologyAnalysis topology = topologyAnalyzer.analyze(features);
        RoutingExecutionContext context = new RoutingExecutionContext(
                importId, imported.getReport().getSha256(),
                imported.getReport().getContractVersion(),
                imported.getReport().getInputProfile());
        return this.algorithm.plan(
                context,
                features,
                topology,
                parameters,
                featureRepository.routingFeatureSource(importId));
    }
}
