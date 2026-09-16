package ru.lct.heatroute.domain.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class OfficialImportServiceTest {
    private final OfficialGeoJsonInspector inspector = mock(OfficialGeoJsonInspector.class);
    private final OfficialFeatureLoader loader = mock(OfficialFeatureLoader.class);
    private final OfficialImportRepository repository = mock(OfficialImportRepository.class);
    private final OfficialImportService service = new OfficialImportService(inspector, loader, repository);

    @Test
    void returnsExistingImportForTheSameContractAndBytesWithoutReloadingFeatures() throws Exception {
        byte[] content = "{}".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = mock(MultipartFile.class);
        OfficialInputReport report = report("same-sha");
        OfficialImportView existing = new OfficialImportView(
                UUID.randomUUID(), "valid", "first.geojson", content.length, OffsetDateTime.now(), report);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
        when(inspector.inspect(any(InputStream.class))).thenReturn(report);
        when(repository.findByContractAndHash(OfficialGeoJsonInspector.CONTRACT_VERSION, "same-sha"))
                .thenReturn(Optional.of(existing));

        OfficialImportView result = service.create(file, "replay.geojson");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).insert(
                any(UUID.class), anyString(), anyString(), eq((long) content.length), any(OfficialInputReport.class));
        verify(loader, never()).load(any(UUID.class), any(InputStream.class));
    }

    @Test
    void resolvesConcurrentUniqueConflictToTheCommittedImport() throws Exception {
        byte[] content = "{}".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = mock(MultipartFile.class);
        OfficialInputReport report = report("race-sha");
        OfficialImportView winner = new OfficialImportView(
                UUID.randomUUID(), "validating", "winner.geojson", content.length, OffsetDateTime.now(), report);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
        when(file.getSize()).thenReturn((long) content.length);
        when(inspector.inspect(any(InputStream.class))).thenReturn(report);
        when(repository.findByContractAndHash(OfficialGeoJsonInspector.CONTRACT_VERSION, "race-sha"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.insert(
                        any(UUID.class),
                        eq("validating"),
                        eq("race.geojson"),
                        eq((long) content.length),
                        eq(report)))
                .thenReturn(false);

        OfficialImportView result = service.create(file, "race.geojson");

        assertThat(result).isSameAs(winner);
        verify(loader, never()).load(any(UUID.class), any(InputStream.class));
    }

    private OfficialInputReport report(String sha256) {
        return new OfficialInputReport(
                OfficialGeoJsonInspector.CONTRACT_VERSION,
                OfficialGeoJsonInspector.STRICT_PROFILE,
                sha256,
                1,
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList());
    }
}
