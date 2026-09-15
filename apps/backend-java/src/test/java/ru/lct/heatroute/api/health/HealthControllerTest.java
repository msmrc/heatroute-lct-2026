package ru.lct.heatroute.api.health;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
class HealthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReadinessService readinessService;

    @Test
    void livenessMatchesExistingContractAndReturnsRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("alive"))
                .andExpect(header().string("X-Request-ID", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void readinessReturns503WhenPostgisIsUnavailable() throws Exception {
        when(readinessService.check()).thenReturn(new ReadinessResponse(
                "not_ready",
                Collections.singletonMap("postgis", DependencyStatus.error("unavailable"))));

        mockMvc.perform(get("/api/v1/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("not_ready"))
                .andExpect(jsonPath("$.checks.postgis.status").value("error"));
    }

    @Test
    void readinessReturns200WhenPostgisIsReady() throws Exception {
        when(readinessService.check()).thenReturn(new ReadinessResponse(
                "ready",
                Collections.singletonMap("postgis", DependencyStatus.ok("3.5"))));

        mockMvc.perform(get("/api/v1/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks.postgis.detail").value("3.5"));
    }
}
