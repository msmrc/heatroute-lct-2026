package ru.lct.heatroute.api.official;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.lct.heatroute.domain.export.OfficialExportService;
import ru.lct.heatroute.domain.input.OfficialImportService;
import ru.lct.heatroute.domain.input.OfficialImportView;
import ru.lct.heatroute.domain.job.OfficialJobService;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.run.OfficialRunService;
import ru.lct.heatroute.domain.run.OfficialRunView;

@WebMvcTest(OfficialJobController.class)
class OfficialJobControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OfficialImportService importService;
    @MockBean
    private OfficialJobService jobService;
    @MockBean
    private OfficialRunService runService;
    @MockBean
    private OfficialExportService exportService;

    @Test
    void queuesTheValidatedDepthRangeInsideTheImmutableRun() throws Exception {
        UUID importId = UUID.randomUUID();
        OfficialImportView imported = validImport();
        when(importService.find(importId)).thenReturn(imported);
        when(runService.create(eq(imported), any(OfficialRunParameters.class))).thenAnswer(invocation ->
                queuedRun(importId, invocation.getArgument(1)));

        mockMvc.perform(post("/api/v1/official/imports/{importId}/runs", importId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minimum_depth_m\":1.0,\"maximum_depth_m\":7.5}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<OfficialRunParameters> parameters = ArgumentCaptor.forClass(OfficialRunParameters.class);
        verify(runService).create(eq(imported), parameters.capture());
        assertThat(parameters.getValue().getMinimumDepthM()).isEqualByComparingTo("1.0");
        assertThat(parameters.getValue().getMaximumDepthM()).isEqualByComparingTo("7.5");
    }

    @Test
    void returnsAStableClientErrorForAnUnsafeDepthRange() throws Exception {
        UUID importId = UUID.randomUUID();
        OfficialImportView imported = validImport();
        when(importService.find(importId)).thenReturn(imported);
        when(runService.create(eq(imported), any(OfficialRunParameters.class)))
                .thenThrow(new IllegalArgumentException("maximum_depth_m must not exceed 50.0"));

        mockMvc.perform(post("/api/v1/official/imports/{importId}/runs", importId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maximum_depth_m\":50.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_RUN_PARAMETERS"));
    }

    @Test
    void ignoresTheRemovedAlgorithmProfileFromOlderClients() throws Exception {
        UUID importId = UUID.randomUUID();
        OfficialImportView imported = validImport();
        when(importService.find(importId)).thenReturn(imported);
        when(runService.create(eq(imported), any(OfficialRunParameters.class))).thenAnswer(invocation ->
                queuedRun(importId, invocation.getArgument(1)));

        mockMvc.perform(post("/api/v1/official/imports/{importId}/runs", importId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"algorithm_profile\":\"expert_experimental\"}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<OfficialRunParameters> parameters = ArgumentCaptor.forClass(OfficialRunParameters.class);
        verify(runService).create(eq(imported), parameters.capture());
        assertThat(parameters.getValue().getMinimumDepthM()).isEqualByComparingTo("0.7");
        assertThat(parameters.getValue().getMaximumDepthM()).isEqualByComparingTo("10.0");
        assertThat(parameters.getValue().isDepthEnabled()).isFalse();
    }

    private OfficialImportView validImport() {
        OfficialImportView imported = mock(OfficialImportView.class);
        when(imported.getState()).thenReturn("valid");
        return imported;
    }

    private OfficialRunView queuedRun(UUID importId, OfficialRunParameters parameters) {
        return new OfficialRunView(
                UUID.randomUUID(),
                importId,
                null,
                "queued",
                "test",
                "sha256",
                parameters,
                null,
                null,
                null,
                OffsetDateTime.now(),
                null);
    }
}
