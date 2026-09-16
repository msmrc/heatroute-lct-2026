package ru.lct.heatroute.domain.job;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfficialJobService {
    private final OfficialJobRepository repository;

    public OfficialJobService(OfficialJobRepository repository) {
        this.repository = repository;
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
        return repository.requestCancellation(jobId).orElse(null);
    }
}
