package ru.lct.heatroute.domain.run;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.domain.input.OfficialImportView;
import ru.lct.heatroute.domain.job.OfficialJobRepository;
import ru.lct.heatroute.domain.job.OfficialJobView;
import ru.lct.heatroute.domain.routing.RoutingAlgorithm;
import ru.lct.heatroute.domain.routing.RoutingAlgorithmRegistry;

@Service
public class OfficialRunService {
    private final OfficialRunRepository runRepository;
    private final OfficialJobRepository jobRepository;
    private final RoutingAlgorithmRegistry algorithmRegistry;

    public OfficialRunService(
            OfficialRunRepository runRepository,
            OfficialJobRepository jobRepository,
            RoutingAlgorithmRegistry algorithmRegistry) {
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
        this.algorithmRegistry = algorithmRegistry;
    }

    @Transactional
    public OfficialRunView create(OfficialImportView imported) {
        return create(imported, OfficialRunParameters.defaults());
    }

    @Transactional
    public OfficialRunView create(OfficialImportView imported, OfficialRunParameters parameters) {
        OfficialRunParameters validated = parameters == null
                ? OfficialRunParameters.defaults()
                : parameters.validated();
        RoutingAlgorithm algorithm = algorithmRegistry.require(validated.getAlgorithmProfile());
        OfficialRunView run = runRepository.create(
                imported.getId(),
                imported.getReport().getSha256(),
                algorithm.version(),
                validated);
        OfficialJobView job = jobRepository.createCalculationJob(imported.getId(), run.getId());
        runRepository.attachJob(run.getId(), job.getId());
        return runRepository.find(run.getId())
                .orElseThrow(() -> new IllegalStateException("Created run cannot be read"));
    }

    @Transactional(readOnly = true)
    public OfficialRunView find(UUID runId) {
        return runRepository.find(runId).orElse(null);
    }

    @Transactional(readOnly = true)
    public OfficialRunView findLatestCompleted() {
        return runRepository.findLatestCompleted().orElse(null);
    }
}
