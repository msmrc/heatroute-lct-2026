package ru.lct.heatroute.domain.input;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class OfficialImportService {
    private final OfficialGeoJsonInspector inspector;
    private final OfficialFeatureLoader featureLoader;
    private final OfficialImportRepository repository;

    public OfficialImportService(
            OfficialGeoJsonInspector inspector,
            OfficialFeatureLoader featureLoader,
            OfficialImportRepository repository) {
        this.inspector = inspector;
        this.featureLoader = featureLoader;
        this.repository = repository;
    }

    @Transactional
    public OfficialImportView create(MultipartFile file, String safeFilename) throws IOException {
        OfficialInputReport report;
        try (InputStream input = file.getInputStream()) {
            report = inspector.inspect(input);
        }

        OfficialImportView existing = repository.findByContractAndHash(
                        report.getContractVersion(), report.getSha256())
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        UUID importId = UUID.randomUUID();
        String state = report.isValid() ? "validating" : "invalid";
        if (!repository.insert(importId, state, safeFilename, file.getSize(), report)) {
            return repository.findByContractAndHash(report.getContractVersion(), report.getSha256())
                    .orElseThrow(() -> new IllegalStateException("Concurrent import cannot be read"));
        }
        if (report.isValid()) {
            try (InputStream input = file.getInputStream()) {
                long loaded = featureLoader.load(importId, input);
                if (loaded != report.getFeatureCount()) {
                    throw new IllegalStateException("Validated and loaded feature counts differ");
                }
            }
            repository.markValid(importId);
        }
        return repository.find(importId)
                .orElseThrow(() -> new IllegalStateException("Inserted import cannot be read"));
    }

    @Transactional(readOnly = true)
    public OfficialImportView find(UUID importId) {
        return repository.find(importId).orElse(null);
    }
}
