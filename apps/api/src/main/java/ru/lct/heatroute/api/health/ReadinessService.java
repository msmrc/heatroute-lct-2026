package ru.lct.heatroute.api.health;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReadinessService {
    private final JdbcTemplate jdbcTemplate;
    private final CpSatCapabilityService cpSatCapabilityService;

    public ReadinessService(JdbcTemplate jdbcTemplate, CpSatCapabilityService cpSatCapabilityService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cpSatCapabilityService = cpSatCapabilityService;
    }

    public ReadinessResponse check() {
        Map<String, DependencyStatus> checks = new LinkedHashMap<>();
        try {
            String version = jdbcTemplate.queryForObject("SELECT PostGIS_Version()", String.class);
            checks.put("postgis", DependencyStatus.ok(version));
        } catch (DataAccessException exception) {
            checks.put("postgis", DependencyStatus.error("unavailable"));
        }
        checks.put("cp_sat", cpSatCapabilityService.check());

        boolean ready = checks.values().stream().allMatch(value -> "ok".equals(value.getStatus()));
        return new ReadinessResponse(ready ? "ready" : "not_ready", checks);
    }
}
