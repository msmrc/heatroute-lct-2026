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
    // Imported and calculated coordinates are rounded independently. Keep a five-centimetre
    // topology snap window so their common endpoint cannot turn into a fictitious crossing.
    private static final double ENDPOINT_EPSILON_M = 0.05;
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
        return extract(edge, features, java.util.Collections.emptySet());
    }

    public DepthCrossingExtraction extract(
            RouteEdge edge,
            List<ImportedOfficialFeature> features,
            Set<String> endpointFeatureIds) {
        List<DepthCrossing> crossings = new ArrayList<>();
        List<DepthProfileIssue> issues = new ArrayList<>();
        if (edge.getCoordinates().size() < 2) return new DepthCrossingExtraction(crossings, issues);
        LineString route = geometryFactory.createLineString(edge.getCoordinates().stream()
                .map(RouteCoordinate::toCoordinate)
                .toArray(Coordinate[]::new));
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        double maximumStation = edge.getLengthM().doubleValue();
        for (ImportedOfficialFeature feature : features) {
            if (endpointFeatureIds.contains(feature.getFeatureId())) continue;
            String type = utilityType(feature);
            if (type == null) continue;
            Geometry source = feature.getMetricGeometry();
            if (source == null || source.isEmpty()
                    || !route.getEnvelopeInternal().intersects(source.getEnvelopeInternal())) continue;
            Geometry intersection = route.intersection(source);
            if (intersection.isEmpty()) continue;
            List<Double> stations = crossingStations(indexed, intersection, maximumStation);
            if (stations.isEmpty()) continue;
            ExistingUtility utility = existingUtility(feature, type, issues);
            if (utility == null) continue;
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

    /** Full physical path extraction. Only explicitly identified heat-network tie-ins at real end nodes are exempt. */
    public DepthCrossingExtraction extractPhysical(RouteEdge edge, List<ImportedOfficialFeature> features,
            Set<String> upstreamHeatIds, Set<String> downstreamHeatIds) {
        double total = edge.getCoordinates().size() < 2 ? 0 : geometryFactory.createLineString(edge.getCoordinates().stream()
                .map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new)).getLength();
        return extractPhysical(edge, features, upstreamHeatIds, downstreamHeatIds,
                station -> total == 0 ? 0 : station * edge.getLengthM().doubleValue() / total);
    }

    DepthCrossingExtraction extractPhysical(RouteEdge edge, List<ImportedOfficialFeature> features,
            Set<String> upstreamHeatIds, Set<String> downstreamHeatIds, java.util.function.DoubleUnaryOperator storedStation) {
        List<DepthCrossing> crossings = new ArrayList<>();
        List<DepthProfileIssue> issues = new ArrayList<>();
        if (edge.getCoordinates().size() < 2) return new DepthCrossingExtraction(crossings, issues);
        Coordinate[] points = edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate).toArray(Coordinate[]::new);
        double total = geometryFactory.createLineString(points).getLength();
        if (total == 0) return new DepthCrossingExtraction(crossings, issues);
        for (ImportedOfficialFeature feature : features) {
            String type = utilityType(feature);
            if (type == null || feature.getMetricGeometry() == null || feature.getMetricGeometry().isEmpty()) continue;
            List<double[]> ranges = new ArrayList<>();
            double station = 0;
            for (int i = 1; i < points.length; i++) {
                LineString segment = geometryFactory.createLineString(new Coordinate[]{points[i - 1], points[i]});
                double length = segment.getLength();
                if (length == 0) continue;
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                if (!segment.getEnvelopeInternal().intersects(feature.getMetricGeometry().getEnvelopeInternal())) {
                    station += length;
                    continue;
                }
                Geometry intersection = segment.intersection(feature.getMetricGeometry());
                physicalRanges(intersection, new LengthIndexedLine(segment), station, ranges);
                station += length;
            }
            ranges.sort(Comparator.comparingDouble(range -> range[0]));
            List<double[]> merged = new ArrayList<>();
            for (double[] range : ranges) {
                if (!merged.isEmpty() && range[0] <= merged.get(merged.size() - 1)[1] + 1e-8)
                    merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], range[1]);
                else merged.add(range.clone());
            }
            List<BigDecimal> actualStations = new ArrayList<>();
            List<BigDecimal[]> plateaus = new ArrayList<>();
            for (double[] range : merged) {
                boolean sourceStart = "heat_network".equals(type) && upstreamHeatIds.contains(feature.getFeatureId())
                        && range[0] <= ENDPOINT_EPSILON_M;
                boolean sourceEnd = "heat_network".equals(type) && downstreamHeatIds.contains(feature.getFeatureId())
                        && total - range[1] <= ENDPOINT_EPSILON_M;
                if (sourceStart || sourceEnd) continue;
                double physicalCrossing = (range[0] + range[1]) * 0.5;
                actualStations.add(BigDecimal.valueOf(storedStation.applyAsDouble(physicalCrossing)).setScale(3, RoundingMode.HALF_UP));
                // Normalize numeric projection noise before directed bounds; never shorten the physical plateau.
                plateaus.add(new BigDecimal[]{
                        BigDecimal.valueOf(storedStation.applyAsDouble(physicalCrossing - 2)).setScale(9, RoundingMode.HALF_UP).setScale(3, RoundingMode.FLOOR),
                        BigDecimal.valueOf(storedStation.applyAsDouble(physicalCrossing + 2)).setScale(9, RoundingMode.HALF_UP).setScale(3, RoundingMode.CEILING)});
            }
            if (actualStations.isEmpty()) continue;
            ExistingUtility utility = existingUtility(feature, type, issues);
            if (utility == null) continue;
            for (int i = 0; i < actualStations.size(); i++) crossings.add(new DepthCrossing(
                    actualStations.size() == 1 ? feature.getFeatureId() : feature.getFeatureId() + "#" + (i + 1),
                    type, actualStations.get(i), utility.topDepthM, utility.heightM,
                    utility.rule.getVerticalClearanceM(), utility.rule.getCostMultiplier())
                    .withPlateauInterval(plateaus.get(i)[0], plateaus.get(i)[1]));
        }
        crossings.sort(Comparator.comparing(DepthCrossing::getStationM).thenComparing(DepthCrossing::getId));
        return new DepthCrossingExtraction(crossings, issues);
    }

    private void physicalRanges(Geometry geometry, LengthIndexedLine segment, double offset, List<double[]> ranges) {
        if (geometry.isEmpty()) return;
        if (geometry instanceof org.locationtech.jts.geom.GeometryCollection) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) physicalRanges(geometry.getGeometryN(i), segment, offset, ranges);
            return;
        }
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (Coordinate coordinate : geometry.getCoordinates()) {
            double value = segment.project(coordinate);
            low = Math.min(low, value); high = Math.max(high, value);
        }
        if (low <= high) ranges.add(new double[]{offset + low, offset + high});
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
            double start = Math.min(first, last);
            double end = Math.max(first, last);
            // A linear overlap that begins or ends with the route is the physical tie-in to the
            // existing utility, not an independent crossing. Treating its midpoint as a crossing
            // creates a fictitious vertical pass a few metres after the connection chamber.
            if (start <= ENDPOINT_EPSILON_M || maximumStation - end <= ENDPOINT_EPSILON_M) {
                return List.of();
            }
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
