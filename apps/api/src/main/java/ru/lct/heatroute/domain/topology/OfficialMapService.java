package ru.lct.heatroute.domain.topology;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.api.error.ApiException;
import ru.lct.heatroute.domain.input.OfficialImportService;

@Service
public class OfficialMapService {
    private static final double MAX_VIEWPORT_SPAN_DEGREES = 2.0;

    private final OfficialImportService importService;
    private final OfficialFeatureRepository featureRepository;

    public OfficialMapService(
            OfficialImportService importService,
            OfficialFeatureRepository featureRepository) {
        this.importService = importService;
        this.featureRepository = featureRepository;
    }

    @Transactional(readOnly = true)
    public String findMapFeatures(
            UUID importId,
            double minLongitude,
            double minLatitude,
            double maxLongitude,
            double maxLatitude) {
        if (importService.find(importId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", "Official import was not found");
        }
        requireValidBounds(minLongitude, minLatitude, maxLongitude, maxLatitude);
        return featureRepository.findMapFeatures(
                importId, minLongitude, minLatitude, maxLongitude, maxLatitude);
    }

    private void requireValidBounds(
            double minLongitude,
            double minLatitude,
            double maxLongitude,
            double maxLatitude) {
        boolean finite = Double.isFinite(minLongitude)
                && Double.isFinite(minLatitude)
                && Double.isFinite(maxLongitude)
                && Double.isFinite(maxLatitude);
        boolean worldBounds = minLongitude >= -180
                && maxLongitude <= 180
                && minLatitude >= -90
                && maxLatitude <= 90;
        boolean ordered = minLongitude < maxLongitude && minLatitude < maxLatitude;
        boolean limited = maxLongitude - minLongitude <= MAX_VIEWPORT_SPAN_DEGREES
                && maxLatitude - minLatitude <= MAX_VIEWPORT_SPAN_DEGREES;
        if (!finite || !worldBounds || !ordered || !limited) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_MAP_BOUNDS",
                    "Map bounds must be an ordered WGS84 viewport no larger than 2 degrees");
        }
    }
}
