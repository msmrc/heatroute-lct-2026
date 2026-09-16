package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A reproducible base or special-construction part of a logical route edge.
 * Sections are ordered along the edge from its upstream node to its downstream node.
 */
public class RouteSection {
    private final String kind;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String restrictionType;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String restrictionId;
    private final List<RouteCoordinate> coordinates;
    private final BigDecimal lengthM;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final BigDecimal crossingAngleDegrees;

    public RouteSection(
            String kind,
            String restrictionType,
            String restrictionId,
            List<RouteCoordinate> coordinates,
            double lengthM,
            Double crossingAngleDegrees) {
        this.kind = kind;
        this.restrictionType = restrictionType;
        this.restrictionId = restrictionId;
        this.coordinates = Collections.unmodifiableList(new ArrayList<>(coordinates));
        this.lengthM = rounded(lengthM);
        this.crossingAngleDegrees = crossingAngleDegrees == null ? null : rounded(crossingAngleDegrees);
    }

    public String getKind() { return kind; }
    public String getRestrictionType() { return restrictionType; }
    public String getRestrictionId() { return restrictionId; }
    public List<RouteCoordinate> getCoordinates() { return coordinates; }
    public BigDecimal getLengthM() { return lengthM; }
    public BigDecimal getCrossingAngleDegrees() { return crossingAngleDegrees; }

    private static BigDecimal rounded(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }
}
