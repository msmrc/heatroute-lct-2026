package ru.lct.heatroute.domain.routing;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.domain.input.OfficialImportRepository;
import ru.lct.heatroute.domain.input.OfficialImportView;
import ru.lct.heatroute.domain.topology.ExistingNetworkTopologyAnalyzer;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.OfficialFeatureRepository;
import ru.lct.heatroute.domain.topology.TopologyAnalysis;

@Service
public class OfficialCalculationService {
    private final OfficialImportRepository importRepository;
    private final OfficialFeatureRepository featureRepository;
    private final ExistingNetworkTopologyAnalyzer topologyAnalyzer;
    private final OfficialRoutePlanner routePlanner;

    public OfficialCalculationService(
            OfficialImportRepository importRepository,
            OfficialFeatureRepository featureRepository,
            ExistingNetworkTopologyAnalyzer topologyAnalyzer,
            OfficialRoutePlanner routePlanner) {
        this.importRepository = importRepository;
        this.featureRepository = featureRepository;
        this.topologyAnalyzer = topologyAnalyzer;
        this.routePlanner = routePlanner;
    }

    @Transactional(readOnly = true)
    public OfficialCalculationResult calculate(UUID importId) {
        OfficialImportView imported = importRepository.find(importId).orElse(null);
        if (imported == null || !"valid".equals(imported.getState())) {
            return null;
        }
        List<ImportedOfficialFeature> features = featureRepository.findByImport(importId);
        TopologyAnalysis topology = topologyAnalyzer.analyze(features);
        return routePlanner.plan(features, topology);
    }
}
