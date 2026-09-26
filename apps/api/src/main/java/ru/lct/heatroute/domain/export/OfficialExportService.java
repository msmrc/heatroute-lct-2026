package ru.lct.heatroute.domain.export;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.run.OfficialRunService;
import ru.lct.heatroute.domain.run.OfficialRunView;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;
import ru.lct.heatroute.domain.topology.OfficialFeatureRepository;

@Service
public class OfficialExportService {
    private final OfficialRunService runService;
    private final OfficialFeatureRepository featureRepository;
    private final OfficialGeoJsonExporter exporter;

    public OfficialExportService(
            OfficialRunService runService,
            OfficialFeatureRepository featureRepository,
            OfficialGeoJsonExporter exporter) {
        this.runService = runService;
        this.featureRepository = featureRepository;
        this.exporter = exporter;
    }

    @Transactional(readOnly = true)
    public OfficialExportPayload prepare(UUID runId, String variantId) {
        OfficialRunView run = runService.find(runId);
        if (run == null) {
            return null;
        }
        if (!"completed".equals(run.getState()) || run.getResult() == null) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: calculation run is not completed");
        }
        OfficialRunParameters parameters = run.getParameters();
        if (parameters == null) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: saved run parameters are missing");
        }
        try {
            parameters.validated();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: saved run parameters are invalid", invalid);
        }
        List<ImportedOfficialFeature> features = featureRepository.findByImport(run.getImportId());
        if (variantId == null) {
            exporter.validate(run.getResult(), features, parameters);
        } else {
            exporter.validateVariant(run.getResult(), features, variantId, parameters);
        }
        return new OfficialExportPayload(exporter, run.getResult(), features, variantId, parameters);
    }
}
