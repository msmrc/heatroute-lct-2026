package ru.lct.heatroute.api.official;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.InputStreamSource;
import org.springframework.test.web.servlet.MockMvc;
import ru.lct.heatroute.domain.input.OfficialGeoJsonInspector;
import ru.lct.heatroute.domain.input.OfficialImportService;
import ru.lct.heatroute.domain.input.OfficialImportView;
import ru.lct.heatroute.domain.input.OfficialInputReport;
import ru.lct.heatroute.domain.topology.OfficialMapService;
import ru.lct.heatroute.domain.topology.TopologyAnalysisService;

@WebMvcTest(OfficialImportController.class)
class OfficialImportControllerTest {
    private static final long DEMO_SIZE_BYTES = 633_402L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OfficialGeoJsonInspector inspector;
    @MockBean
    private OfficialImportService importService;
    @MockBean
    private TopologyAnalysisService topologyAnalysisService;
    @MockBean
    private OfficialMapService mapService;

    @Test
    void opensTheExactBundledOfficialDatasetAsAnImport() throws Exception {
        UUID importId = UUID.randomUUID();
        OfficialInputReport report = new OfficialInputReport(
                "2",
                "baseline_input",
                "cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130",
                144L,
                Collections.singletonMap("heat_network", 29L),
                Collections.emptyList(),
                Collections.emptyList());
        OfficialImportView imported = new OfficialImportView(
                importId,
                "valid",
                "lct-2026.geojson",
                DEMO_SIZE_BYTES,
                OffsetDateTime.parse("2026-09-23T00:00:00Z"),
                report);
        when(importService.create(any(InputStreamSource.class), eq("lct-2026.geojson"), eq(DEMO_SIZE_BYTES)))
                .thenReturn(imported);

        mockMvc.perform(post("/api/v1/official/imports/demo"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(importId.toString()))
                .andExpect(jsonPath("$.state").value("valid"))
                .andExpect(jsonPath("$.report.feature_count").value(144));

        ArgumentCaptor<InputStreamSource> source = ArgumentCaptor.forClass(InputStreamSource.class);
        verify(importService).create(source.capture(), eq("lct-2026.geojson"), eq(DEMO_SIZE_BYTES));
        try (InputStream input = source.getValue().getInputStream()) {
            assertThat(input.read()).isEqualTo('{');
        }
    }
}
