package ru.lct.heatroute.domain.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import ru.lct.heatroute.api.error.ApiException;
import ru.lct.heatroute.domain.input.OfficialImportService;
import ru.lct.heatroute.domain.input.OfficialImportView;

class OfficialMapServiceTest {
    private final OfficialImportService importService = mock(OfficialImportService.class);
    private final OfficialFeatureRepository featureRepository = mock(OfficialFeatureRepository.class);
    private final OfficialMapService service = new OfficialMapService(importService, featureRepository);

    @Test
    void returnsBoundedGeoJsonForExistingImport() {
        UUID importId = UUID.randomUUID();
        when(importService.find(importId)).thenReturn(mock(OfficialImportView.class));
        when(featureRepository.findMapFeatures(importId, 37.6, 55.6, 37.7, 55.8))
                .thenReturn("{\"type\":\"FeatureCollection\",\"features\":[]}");

        String result = service.findMapFeatures(importId, 37.6, 55.6, 37.7, 55.8);

        assertThat(result).contains("FeatureCollection");
        verify(featureRepository).findMapFeatures(importId, 37.6, 55.6, 37.7, 55.8);
    }

    @Test
    void rejectsUnboundedViewport() {
        UUID importId = UUID.randomUUID();
        when(importService.find(importId)).thenReturn(mock(OfficialImportView.class));

        assertThatThrownBy(() -> service.findMapFeatures(importId, 30, 50, 40, 60))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.getCode()).isEqualTo("INVALID_MAP_BOUNDS");
                });
    }

    @Test
    void returnsNotFoundForUnknownImport() {
        UUID importId = UUID.randomUUID();

        assertThatThrownBy(() -> service.findMapFeatures(importId, 37.6, 55.6, 37.7, 55.8))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
