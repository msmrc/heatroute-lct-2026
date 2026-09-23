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
    private final RoutingAlgorithmRegistry algorithmRegistry;

    public OfficialCalculationService(
            OfficialImportRepository importRepository,
            OfficialFeatureRepository featureRepository,
            ExistingNetworkTopologyAnalyzer topologyAnalyzer,
            RoutingAlgorithmRegistry algorithmRegistry) {
        this.importRepository = importRepository;
        this.featureRepository = featureRepository;
        this.topologyAnalyzer = topologyAnalyzer;
        this.algorithmRegistry = algorithmRegistry;
    }

    @Transactional(readOnly = true)
    public OfficialCalculationResult calculate(UUID importId) {
        return calculate(importId, OfficialRunParameters.defaults());
    }

    @Transactional(readOnly = true)
    public OfficialCalculationResult calculate(UUID importId, OfficialRunParameters parameters) {
        OfficialImportView imported = importRepository.find(importId).orElse(null);
        if (imported == null || !"valid".equals(imported.getState())) {
            return null;
        }
        // Core network and demand types are materialized; bulky restrictions and existing OKS
        // geometries remain behind the PostGIS windowed routing source.
        List<ImportedOfficialFeature> features = new ArrayList<>();
        featureRepository.forEachCalculationCoreByImport(
                importId, OfficialFeatureRepository.DEFAULT_PAGE_SIZE, features::add);
        TopologyAnalysis topology = topologyAnalyzer.analyze(features);
        RoutingAlgorithm algorithm = algorithmRegistry.require(parameters.getAlgorithmProfile());
        return algorithm.plan(
                features,
                topology,
                parameters,
                imported.getReport().getInputProfile(),
                featureRepository.routingFeatureSource(importId));
    }
}
