package ru.lct.heatroute.domain.input;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class OfficialImportPersistenceService {
    private final OfficialFeatureLoader featureLoader;
    private final OfficialImportRepository repository;

    public OfficialImportPersistenceService(
            OfficialFeatureLoader featureLoader,
            OfficialImportRepository repository) {
        this.featureLoader = featureLoader;
        this.repository = repository;
    }

    @Transactional(rollbackFor = Exception.class)
    public void loadAndMarkValid(UUID importId, MultipartFile file, long expectedFeatureCount)
            throws IOException {
        try (InputStream input = file.getInputStream()) {
            long loaded = featureLoader.load(importId, input);
            if (loaded != expectedFeatureCount) {
                throw new IllegalStateException("Validated and loaded feature counts differ");
            }
        }
        repository.markValid(importId);
    }
}
