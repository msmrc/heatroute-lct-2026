package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.SpatialConstraintRule;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Projects official linear utilities to chainage along a planned edge. */
@Component
public class OfficialDepthCrossingExtractor {
    private static final double ENDPOINT_EPSILON_M = 0.01;
    private final OfficialConstraintCatalog constraints;
    private final OfficialPipeCatalog pipes;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public OfficialDepthCrossingExtractor(
            OfficialConstraintCatalog constraints,
            OfficialPipeCatalog pipes) {
        this.constraints = constraints;
        this.pipes = pipes;
    }

    public DepthCrossingExtraction extract(RouteEdge edge, List<ImportedOfficialFeature> features) {
        List<DepthCrossing> crossings = new ArrayList<>();
        List<DepthProfileIssue> issues = new ArrayList<>();
        if (edge.getCoordinates().size() < 2) return new DepthCrossingExtraction(crossings, issues);
        LineString route = geometryFactory.createLineString(edge.getCoordinates().stream()
                .map(RouteCoordinate::toCoordinate)
                .toArray(Coordinate[]::new));
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        double maximumStation = edge.getLengthM().doubleValue();
        for (ImportedOfficialFeature feature : features) {
            String type = utilityType(feature);
            if (type == null) continue;
            Geometry source = feature.getMetricGeometry();
            if (source == null || source.isEmpty()
                    || !route.getEnvelopeInternal().intersects(source.getEnvelopeInternal())) continue;
            Geometry intersection = route.intersection(source);
            if (intersection.isEmpty()) continue;
            ExistingUtility utility = existingUtility(feature, type, issues);
            if (utility == null) continue;
            List<Double> stations = crossingStations(indexed, intersection, maximumStation);
            for (int index = 0; index < stations.size(); index++) {
                String id = stations.size() == 1
                        ? feature.getFeatureId()
                        : feature.getFeatureId() + "#" + (index + 1);
                crossings.add(new DepthCrossing(
                        id,
                        type,
                        BigDecimal.valueOf(stations.get(index)).setScale(3, RoundingMode.HALF_UP),
                        utility.topDepthM,
                        utility.heightM,
                        utility.rule.getVerticalClearanceM(),
                        utility.rule.getCostMultiplier()));
            }
        }
        crossings.sort(Comparator.comparing(DepthCrossing::getStationM)
                .thenComparing(DepthCrossing::getType)
                .thenComparing(DepthCrossing::getId));
        return new DepthCrossingExtraction(crossings, issues);
    }

    private List<Double> crossingStations(
            LengthIndexedLine indexed,
            Geometry intersection,
            double maximumStation) {
        Set<BigDecimal> unique = new LinkedHashSet<>();
        Coordinate[] coordinates = intersection.getCoordinates();
        if (intersection.getDimension() == 1 && coordinates.length > 1) {
            double first = indexed.project(coordinates[0]);
            double last = indexed.project(coordinates[coordinates.length - 1]);
            addStation(unique, (first + last) / 2.0, maximumStation);
        } else {
            for (Coordinate coordinate : coordinates) {
                addStation(unique, indexed.project(coordinate), maximumStation);
            }
        }
        return unique.stream().map(BigDecimal::doubleValue).sorted().collect(java.util.stream.Collectors.toList());
    }

    private void addStation(Set<BigDecimal> stations, double station, double maximumStation) {
        double clamped = Math.max(0.0, Math.min(maximumStation, station));
        if (clamped <= ENDPOINT_EPSILON_M || maximumStation - clamped <= ENDPOINT_EPSILON_M) return;
        stations.add(BigDecimal.valueOf(clamped).setScale(3, RoundingMode.HALF_UP));
    }

    private ExistingUtility existingUtility(
            ImportedOfficialFeature feature,
            String type,
            List<DepthProfileIssue> issues) {
        SpatialConstraintRule rule = constraints.find(type).orElse(null);
        if (rule == null || rule.getVerticalClearanceM() == null) return null;
        if ("gas_pipeline".equals(type)) {
            return new ExistingUtility(new BigDecimal("2.8"), new BigDecimal("0.4"), rule);
        }
        if ("power_cable".equals(type)) {
            return new ExistingUtility(new BigDecimal("2.7"), new BigDecimal("0.2"), rule);
        }
        int diameter = feature.getAttributes().path("diameter").asInt(0);
        PipeCatalogEntry pipe = pipes.byDiameter(diameter).orElse(null);
        if (pipe == null) {
            issues.add(new DepthProfileIssue(
                    "EXISTING_HEAT_DIAMETER_MISSING",
                    feature.getFeatureId(),
                    "Existing heat-network crossing has no official diameter"));
            return null;
        }
        return new ExistingUtility(new BigDecimal("3.0"), pipe.getEnvelopeHeightM(), rule);
    }

    private String utilityType(ImportedOfficialFeature feature) {
        if ("heat_network".equals(feature.getObjectType())) return "heat_network";
        if (!"restriction".equals(feature.getObjectType())) return null;
        String type = feature.getAttributes().path("restriction_type").asText();
        return "gas_pipeline".equals(type) || "power_cable".equals(type) ? type : null;
    }

    private static final class ExistingUtility {
        private final BigDecimal topDepthM;
        private final BigDecimal heightM;
        private final SpatialConstraintRule rule;

        private ExistingUtility(
                BigDecimal topDepthM,
                BigDecimal heightM,
                SpatialConstraintRule rule) {
            this.topDepthM = topDepthM;
            this.heightM = heightM;
            this.rule = rule;
        }
    }
}
