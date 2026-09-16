package ru.lct.heatroute.domain.input;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

public enum OfficialRestrictionType {
    PARK("park", Set.of("Polygon", "MultiPolygon")),
    SOCIAL_AREA("social_area", Set.of("Polygon", "MultiPolygon")),
    PROHIBITED_SITE("prohibited_site", Set.of("Polygon", "MultiPolygon")),
    WATER("water", Set.of("Polygon", "MultiPolygon")),
    ROAD("road", Set.of("Polygon", "MultiPolygon")),
    TRAM_TRACKS("tram_tracks", Set.of("Polygon", "MultiPolygon")),
    GAS_PIPELINE("gas_pipeline", Set.of("LineString", "MultiLineString")),
    POWER_CABLE("power_cable", Set.of("LineString", "MultiLineString")),
    HEAT_NETWORK("heat_network", Set.of("LineString", "MultiLineString"));

    private final String wireName;
    private final Set<String> geometryTypes;

    OfficialRestrictionType(String wireName, Set<String> geometryTypes) {
        this.wireName = wireName;
        this.geometryTypes = geometryTypes;
    }

    public String getWireName() {
        return wireName;
    }

    public boolean acceptsGeometry(String geometryType) {
        return geometryTypes.contains(geometryType);
    }

    public static Optional<OfficialRestrictionType> fromWireName(String value) {
        return Arrays.stream(values()).filter(type -> type.wireName.equals(value)).findFirst();
    }
}
