package ru.lct.heatroute.domain.job;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.domain.run.OfficialRunRepository;

@Service
public class OfficialJobService {
    private final OfficialJobRepository repository;
    private final OfficialRunRepository runRepository;

    public OfficialJobService(OfficialJobRepository repository, OfficialRunRepository runRepository) {
        this.repository = repository;
        this.runRepository = runRepository;
    }

    @Transactional
    public OfficialJobView createTopologyJob(UUID importId) {
        return repository.createTopologyJob(importId);
    }

    @Transactional(readOnly = true)
    public OfficialJobView find(UUID jobId) {
        return repository.find(jobId).orElse(null);
    }

    @Transactional
    public OfficialJobView cancel(UUID jobId) {
        OfficialJobView job = repository.requestCancellation(jobId).orElse(null);
        if (job != null && "cancelled".equals(job.getState()) && job.getRunId() != null) {
            runRepository.markCancelled(job.getRunId());
        }
        return job;
    }
}
