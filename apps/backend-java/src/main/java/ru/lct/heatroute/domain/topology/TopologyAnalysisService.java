package ru.lct.heatroute.domain.topology;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.domain.input.OfficialImportRepository;
import ru.lct.heatroute.domain.input.OfficialImportView;

@Service
public class TopologyAnalysisService {
    private final OfficialImportRepository importRepository;
    private final OfficialFeatureRepository featureRepository;
    private final ExistingNetworkTopologyAnalyzer analyzer;

    public TopologyAnalysisService(
            OfficialImportRepository importRepository,
            OfficialFeatureRepository featureRepository,
            ExistingNetworkTopologyAnalyzer analyzer) {
        this.importRepository = importRepository;
        this.featureRepository = featureRepository;
        this.analyzer = analyzer;
    }

    @Transactional(readOnly = true)
    public TopologyAnalysis analyze(UUID importId) {
        OfficialImportView imported = importRepository.find(importId).orElse(null);
        if (imported == null || !"valid".equals(imported.getState())) {
            return null;
        }
        return analyzer.analyze(featureRepository.findByImport(importId));
    }
}
