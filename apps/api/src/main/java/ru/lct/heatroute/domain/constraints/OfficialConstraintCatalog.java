package ru.lct.heatroute.domain.constraints;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OfficialConstraintCatalog {
    private static final Map<String, SpatialConstraintRule> RULES = rules();
    private static final Set<Integer> OFFICIAL_DIAMETERS = Set.of(
            50, 65, 80, 100, 125, 150, 200, 250, 300,
            400, 500, 600, 700, 800, 900, 1000, 1200, 1400);

    public Optional<SpatialConstraintRule> find(String restrictionType) {
        return Optional.ofNullable(RULES.get(restrictionType));
    }

    public BigDecimal existingBuildingClearanceM(int newNetworkDiameter) {
        if (!OFFICIAL_DIAMETERS.contains(newNetworkDiameter)) {
            throw new IllegalArgumentException("new-network diameter must be an official DU");
        }
        if (newNetworkDiameter < 500) {
            return new BigDecimal("5.0");
        }
        if (newNetworkDiameter < 900) {
            return new BigDecimal("7.0");
        }
        return new BigDecimal("9.0");
    }

    private static Map<String, SpatialConstraintRule> rules() {
        Map<String, SpatialConstraintRule> result = new LinkedHashMap<>();
        result.put("park", forbidden("park", "1.0"));
        result.put("social_area", forbidden("social_area", "1.0"));
        result.put("prohibited_site", forbidden("prohibited_site", "1.0"));
        result.put("water", forbidden("water", "1.0"));
        result.put("railway", forbidden("railway", "1.0"));
        result.put("oks", forbidden("oks", "5.0"));
        result.put("road", special("road", "1.5", null, "90", "3.0", "1.0", "1.60"));
        result.put("tram_tracks", special("tram_tracks", "1.5", null, "45", "3.0", "1.2", "1.75"));
        // В ТЗ направленный угол 90–120°; для ненаправленных осей линий это острый угол 60–90°.
        result.put("gas_pipeline", special("gas_pipeline", "2.0", "0.2", "60", "2.0", null, "1.25"));
        result.put("power_cable", special("power_cable", "2.0", "0.5", "60", "2.0", null, "1.15"));
        result.put("heat_network", special("heat_network", "1.0", "0.5", "90", "2.0", null, "1.05"));
        return result;
    }

    private static SpatialConstraintRule forbidden(String type, String horizontalClearance) {
        return new SpatialConstraintRule(type, true, horizontalClearance, null, null, null, null, "1.00");
    }

    private static SpatialConstraintRule special(
            String type,
            String horizontalClearance,
            String verticalClearance,
            String angle,
            String extension,
            String minimumTopBelowSurface,
            String multiplier) {
        return new SpatialConstraintRule(
                type,
                false,
                horizontalClearance,
                verticalClearance,
                angle,
                extension,
                minimumTopBelowSurface,
                multiplier);
    }
}
