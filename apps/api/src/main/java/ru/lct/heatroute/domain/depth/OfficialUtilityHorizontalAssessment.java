package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialAxisClearance;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.UtilityHorizontalClearance;
import ru.lct.heatroute.domain.constraints.UtilityHorizontalClearance.AllowedInterval;
import ru.lct.heatroute.domain.constraints.UtilityHorizontalClearance.MinimumLocation;
import ru.lct.heatroute.domain.constraints.UtilityHorizontalClearance.UtilitySource;
import ru.lct.heatroute.domain.constraints.UtilityHorizontalClearance.UtilityType;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Связывает source-backed специальные интервалы с точной проверкой обычного горизонтального
 * отступа. Недостаточный минимум у самой границы разрешённого интервала остаётся отдельной
 * диагностикой до решения о нормативной длине подхода.
 */
public final class OfficialUtilityHorizontalAssessment {
    private static final double TIE_IN_CONTACT_M = .05;
    private static final Set<String> UTILITY_TYPES =
            Set.of("gas_pipeline", "power_cable", "heat_network");

    private final OfficialPipeCatalog pipes;
    private final OfficialAxisClearance axisClearance;
    private final UtilityHorizontalClearance clearance;
    private final OfficialSpecialSectionIntervals specialIntervals;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public OfficialUtilityHorizontalAssessment(OfficialPipeCatalog pipes) {
        this.pipes = pipes;
        OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
        this.axisClearance = new OfficialAxisClearance(pipes, constraints);
        this.clearance = new UtilityHorizontalClearance(pipes, constraints);
        this.specialIntervals = new OfficialSpecialSectionIntervals(pipes);
    }

    public Result assess(
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features,
            Map<String, Set<String>> nodeTieIns) {
        List<Source> sources = sources(features);
        if (sources.isEmpty()) return new Result(List.of(), List.of());
        return assess(
                edges,
                features,
                nodeTieIns,
                specialIntervals.extractUtilities(edges, features, nodeTieIns),
                sources);
    }

    public Result assessEmitted(
            List<RouteEdge> original,
            List<RouteEdge> emitted,
            List<ImportedOfficialFeature> features,
            Map<String, Set<String>> nodeTieIns) {
        List<Source> sources = sources(features);
        if (sources.isEmpty()) return new Result(List.of(), List.of());
        return assess(
                emitted,
                features,
                nodeTieIns,
                specialIntervals.extractEmittedUtilities(
                        original, emitted, features, nodeTieIns),
                sources);
    }

    private Result assess(
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features,
            Map<String, Set<String>> nodeTieIns,
            Map<String, List<OfficialSpecialSectionIntervals.Interval>> intervalsByEdge,
            List<Source> sources) {
        List<Finding> ordinary = new ArrayList<>();
        List<Finding> boundary = new ArrayList<>();
        for (DepthPhysicalChains.Chain chain :
                DepthPhysicalChains.build(edges, nodeTieIns.keySet())) {
            active();
            RouteEdge physical = chain.edge;
            if (physical.getDiameter() == null
                    || pipes.byDiameter(physical.getDiameter()).isEmpty()) {
                throw new IllegalArgumentException(
                        "UTILITY_HORIZONTAL_EDGE_DIAMETER: " + physical.getId());
            }
            LineString line = line(physical);
            List<RouteCoordinate> points = physical.getCoordinates();
            Set<String> firstHeat =
                    DepthSourceTieIns.heatIds(
                            nodeTieIns.getOrDefault(physical.getUpstreamNodeId(), Set.of()),
                            points.get(0),
                            features);
            Set<String> lastHeat =
                    DepthSourceTieIns.heatIds(
                            nodeTieIns.getOrDefault(physical.getDownstreamNodeId(), Set.of()),
                            points.get(points.size() - 1),
                            features);
            for (Source source : sources) {
                active();
                BigDecimal required =
                        axisClearance.axisClearanceM(
                                source.type.getCode(),
                                physical.getDiameter(),
                                source.existingHeatDu);
                Envelope routeEnvelope = line.getEnvelopeInternal();
                if (routeEnvelope.distance(source.feature.getMetricGeometry().getEnvelopeInternal())
                        >= required.doubleValue()) continue;
                List<AllowedInterval> allowed =
                        allowedIntervals(chain, intervalsByEdge, source.feature.getFeatureId());
                double contact = Math.min(TIE_IN_CONTACT_M, line.getLength());
                if (firstHeat.contains(source.feature.getFeatureId())) {
                    allowed.add(new AllowedInterval(source.feature.getFeatureId(), 0, contact));
                }
                if (lastHeat.contains(source.feature.getFeatureId())) {
                    allowed.add(
                            new AllowedInterval(
                                    source.feature.getFeatureId(),
                                    line.getLength() - contact,
                                    line.getLength()));
                }
                UtilityHorizontalClearance.Assessment assessment =
                        clearance.assess(
                                line, physical.getDiameter(), source.utility, allowed);
                for (UtilityHorizontalClearance.Violation violation :
                        assessment.getViolations()) {
                    Finding finding =
                            new Finding(
                                    edgeAt(chain, violation.getWitnessStationM()),
                                    source.feature.getFeatureId(),
                                    source.type,
                                    physical.getDiameter(),
                                    source.existingHeatDu,
                                    assessment.getRequiredAxisDistanceM(),
                                    violation.getMinimumAxisDistanceM(),
                                    violation.getWitnessStationM(),
                                    violation.getMinimumLocation());
                    if (finding.minimumLocation == MinimumLocation.ORDINARY_MINIMUM) {
                        ordinary.add(finding);
                    } else {
                        boundary.add(finding);
                    }
                }
            }
        }
        Comparator<Finding> order =
                Comparator.comparing(Finding::getEdgeId)
                        .thenComparing(Finding::getSourceId)
                        .thenComparingDouble(Finding::getWitnessStationM);
        ordinary.sort(order);
        boundary.sort(order);
        return new Result(ordinary, boundary);
    }

    private List<AllowedInterval> allowedIntervals(
            DepthPhysicalChains.Chain chain,
            Map<String, List<OfficialSpecialSectionIntervals.Interval>> intervalsByEdge,
            String sourceId) {
        List<AllowedInterval> result = new ArrayList<>();
        double offset = 0;
        for (DepthPhysicalChains.Member member : chain.members) {
            active();
            double length = line(member.edge).getLength();
            for (OfficialSpecialSectionIntervals.Interval interval :
                    intervalsByEdge.getOrDefault(member.edge.getId(), List.of())) {
                if (!sourceId.equals(interval.getSourceId())) continue;
                double start =
                        member.forward
                                ? offset + interval.getStartM()
                                : offset + length - interval.getEndM();
                double end =
                        member.forward
                                ? offset + interval.getEndM()
                                : offset + length - interval.getStartM();
                result.add(new AllowedInterval(sourceId, start, end));
            }
            offset += length;
        }
        return result;
    }

    private String edgeAt(DepthPhysicalChains.Chain chain, double station) {
        double offset = 0;
        for (DepthPhysicalChains.Member member : chain.members) {
            double length = line(member.edge).getLength();
            if (station <= offset + length + 1e-9
                    || member == chain.members.get(chain.members.size() - 1)) {
                return member.edge.getId();
            }
            offset += length;
        }
        throw new IllegalStateException("Utility witness lies outside its physical chain");
    }

    private List<Source> sources(List<ImportedOfficialFeature> features) {
        List<Source> result = new ArrayList<>();
        for (ImportedOfficialFeature feature : features) {
            active();
            String code = type(feature);
            if (code == null || !UTILITY_TYPES.contains(code)) continue;
            UtilityType type = utilityType(code);
            Integer existingHeatDu = null;
            if (type == UtilityType.HEAT_NETWORK) {
                if (!feature.getAttributes().path("diameter").isIntegralNumber()) {
                    throw new IllegalArgumentException(
                            "UTILITY_HORIZONTAL_SOURCE_DIAMETER: " + feature.getFeatureId());
                }
                existingHeatDu = feature.getAttributes().path("diameter").intValue();
                if (pipes.byDiameter(existingHeatDu).isEmpty()) {
                    throw new IllegalArgumentException(
                            "UTILITY_HORIZONTAL_SOURCE_DIAMETER: " + feature.getFeatureId());
                }
            }
            result.add(
                    new Source(
                            feature,
                            type,
                            existingHeatDu,
                            new UtilitySource(
                                    feature.getFeatureId(),
                                    type,
                                    feature.getMetricGeometry(),
                                    existingHeatDu)));
        }
        result.sort(Comparator.comparing(source -> source.feature.getFeatureId()));
        return result;
    }

    private String type(ImportedOfficialFeature feature) {
        if ("heat_network".equals(feature.getObjectType())) return "heat_network";
        if (!"restriction".equals(feature.getObjectType())) return null;
        return feature.getAttributes().path("restriction_type").asText(null);
    }

    private UtilityType utilityType(String code) {
        switch (code) {
            case "gas_pipeline":
                return UtilityType.GAS_PIPELINE;
            case "power_cable":
                return UtilityType.POWER_CABLE;
            case "heat_network":
                return UtilityType.HEAT_NETWORK;
            default:
                throw new IllegalArgumentException("Unsupported utility type: " + code);
        }
    }

    private LineString line(RouteEdge edge) {
        if (edge.getCoordinates().size() < 2) {
            throw new IllegalArgumentException("UTILITY_HORIZONTAL_EDGE_GEOMETRY: " + edge.getId());
        }
        return geometryFactory.createLineString(
                edge.getCoordinates().stream()
                        .map(RouteCoordinate::toCoordinate)
                        .toArray(org.locationtech.jts.geom.Coordinate[]::new));
    }

    private static void active() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static final class Source {
        final ImportedOfficialFeature feature;
        final UtilityType type;
        final Integer existingHeatDu;
        final UtilitySource utility;

        Source(
                ImportedOfficialFeature feature,
                UtilityType type,
                Integer existingHeatDu,
                UtilitySource utility) {
            this.feature = feature;
            this.type = type;
            this.existingHeatDu = existingHeatDu;
            this.utility = utility;
        }
    }

    public static final class Finding {
        private final String edgeId;
        private final String sourceId;
        private final UtilityType type;
        private final int newDu;
        private final Integer existingHeatDu;
        private final BigDecimal requiredAxisDistanceM;
        private final double actualAxisDistanceM;
        private final double witnessStationM;
        private final MinimumLocation minimumLocation;

        Finding(
                String edgeId,
                String sourceId,
                UtilityType type,
                int newDu,
                Integer existingHeatDu,
                BigDecimal requiredAxisDistanceM,
                double actualAxisDistanceM,
                double witnessStationM,
                MinimumLocation minimumLocation) {
            this.edgeId = edgeId;
            this.sourceId = sourceId;
            this.type = type;
            this.newDu = newDu;
            this.existingHeatDu = existingHeatDu;
            this.requiredAxisDistanceM = requiredAxisDistanceM;
            this.actualAxisDistanceM = actualAxisDistanceM;
            this.witnessStationM = witnessStationM;
            this.minimumLocation = minimumLocation;
        }

        public String getEdgeId() {
            return edgeId;
        }

        public String getSourceId() {
            return sourceId;
        }

        public UtilityType getType() {
            return type;
        }

        public int getNewDu() {
            return newDu;
        }

        public Integer getExistingHeatDu() {
            return existingHeatDu;
        }

        public BigDecimal getRequiredAxisDistanceM() {
            return requiredAxisDistanceM;
        }

        public double getActualAxisDistanceM() {
            return actualAxisDistanceM;
        }

        public double getWitnessStationM() {
            return witnessStationM;
        }

        public MinimumLocation getMinimumLocation() {
            return minimumLocation;
        }
    }

    public static final class Result {
        private final List<Finding> ordinaryViolations;
        private final List<Finding> boundaryFindings;

        Result(List<Finding> ordinaryViolations, List<Finding> boundaryFindings) {
            this.ordinaryViolations = List.copyOf(ordinaryViolations);
            this.boundaryFindings = List.copyOf(boundaryFindings);
        }

        public List<Finding> getOrdinaryViolations() {
            return ordinaryViolations;
        }

        public List<Finding> getBoundaryFindings() {
            return boundaryFindings;
        }
    }
}
