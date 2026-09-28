package ru.lct.heatroute.api.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class ReadinessServiceTest {
    @Test
    void readinessRequiresBothPostgisAndNativeSolver() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CpSatCapabilityService cpSat = mock(CpSatCapabilityService.class);
        when(jdbc.queryForObject("SELECT PostGIS_Version()", String.class)).thenReturn("3.5");
        when(cpSat.check()).thenReturn(DependencyStatus.ok("ortools-9.15.6755"));

        ReadinessResponse response = new ReadinessService(jdbc, cpSat).check();

        assertThat(response.isReady()).isTrue();
        assertThat(response.getChecks()).containsKeys("postgis", "cp_sat");
        assertThat(response.getChecks().get("cp_sat").getDetail()).isEqualTo("ortools-9.15.6755");
    }

    @Test
    void nativeSolverFailureKeepsApiOutOfReadiness() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CpSatCapabilityService cpSat = mock(CpSatCapabilityService.class);
        when(jdbc.queryForObject("SELECT PostGIS_Version()", String.class)).thenReturn("3.5");
        when(cpSat.check()).thenReturn(DependencyStatus.error("unavailable"));

        ReadinessResponse response = new ReadinessService(jdbc, cpSat).check();

        assertThat(response.isReady()).isFalse();
        assertThat(response.getChecks().get("cp_sat").getStatus()).isEqualTo("error");
    }

    @Test
    void postgisFailureStillReportsSolverCapability() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CpSatCapabilityService cpSat = mock(CpSatCapabilityService.class);
        when(jdbc.queryForObject("SELECT PostGIS_Version()", String.class))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        when(cpSat.check()).thenReturn(DependencyStatus.ok("ortools-9.15.6755"));

        ReadinessResponse response = new ReadinessService(jdbc, cpSat).check();

        assertThat(response.isReady()).isFalse();
        assertThat(response.getChecks().get("postgis").getStatus()).isEqualTo("error");
        assertThat(response.getChecks().get("cp_sat").getStatus()).isEqualTo("ok");
    }
}
