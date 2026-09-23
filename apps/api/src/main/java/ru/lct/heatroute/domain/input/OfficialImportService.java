package ru.lct.heatroute.domain.input;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class OfficialImportService {
    private static final Duration CONCURRENT_IMPORT_WAIT = Duration.ofMinutes(2);
    private static final long CONCURRENT_IMPORT_POLL_MILLIS = 50L;
    private final OfficialGeoJsonInspector inspector;
    private final OfficialImportPersistenceService persistenceService;
    private final OfficialImportRepository repository;

    public OfficialImportService(
            OfficialGeoJsonInspector inspector,
            OfficialImportPersistenceService persistenceService,
            OfficialImportRepository repository) {
        this.inspector = inspector;
        this.persistenceService = persistenceService;
        this.repository = repository;
    }

    public OfficialImportView create(MultipartFile file, String safeFilename) throws IOException {
        return create(file, safeFilename, file.getSize());
    }

    /** Импортирует повторно читаемый upload или встроенный набор без отдельного пути загрузки. */
    public OfficialImportView create(InputStreamSource file, String safeFilename, long inputSizeBytes)
            throws IOException {
        OfficialInputReport report;
        try (InputStream input = file.getInputStream()) {
            report = inspector.inspect(input);
        }

        OfficialImportView existing = repository.findByContractAndHash(
                        report.getContractVersion(), report.getSha256())
                .orElse(null);
        if (existing != null) {
            return awaitConcurrentImport(report, existing);
        }

        UUID importId = UUID.randomUUID();
        String state = report.isValid() ? "validating" : "invalid";
        if (!repository.insert(importId, state, safeFilename, inputSizeBytes, report)) {
            return awaitConcurrentImport(report, null);
        }
        if (report.isValid()) {
            try {
                persistenceService.loadAndMarkValid(importId, file, report.getFeatureCount());
            } catch (IOException | RuntimeException exception) {
                repository.markFailed(importId);
                throw exception;
            }
        }
        return repository.find(importId)
                .orElseThrow(() -> new IllegalStateException("Inserted import cannot be read"));
    }

    private OfficialImportView awaitConcurrentImport(
            OfficialInputReport report,
            OfficialImportView initial) {
        OfficialImportView current = initial;
        long deadline = System.nanoTime() + CONCURRENT_IMPORT_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (current != null && !"validating".equals(current.getState())) {
                return current;
            }
            current = repository.findByContractAndHash(report.getContractVersion(), report.getSha256())
                    .orElse(null);
            if (current != null && !"validating".equals(current.getState())) {
                return current;
            }
            try {
                Thread.sleep(CONCURRENT_IMPORT_POLL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for a concurrent import", exception);
            }
        }
        throw new IllegalStateException("Concurrent import did not finish within two minutes");
    }

    @Transactional(readOnly = true)
    public OfficialImportView find(UUID importId) {
        return repository.find(importId).orElse(null);
    }
}
