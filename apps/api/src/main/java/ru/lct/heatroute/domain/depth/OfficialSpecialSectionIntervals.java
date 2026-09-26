package ru.lct.heatroute.domain.depth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatroute.domain.constraints.OfficialAxisClearance;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.constraints.SpatialConstraintRule;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ExistingSourceContact;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Выводит денежные интервалы из исходной геометрии вдоль однозначных физических цепочек одного ДУ.
 */
public final class OfficialSpecialSectionIntervals {
    // Существующая привязка врезки допускает 5 см; исключаем только первое/последнее событие
    // точечного контакта; протяжённое наложение исключением не является.
    private static final double CONTACT_EPSILON_M = .05;
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final OfficialConstraintCatalog catalog = new OfficialConstraintCatalog();
    private final OfficialAxisClearance clearance;
    private final RoadCrossingClearance roads = new RoadCrossingClearance();

    public OfficialSpecialSectionIntervals(OfficialPipeCatalog pipes) {
        clearance = new OfficialAxisClearance(pipes, catalog);
    }

    public Map<String, List<Interval>> extract(
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features,
            Map<String, Set<String>> nodeTieIns) {
        return extract(edges, features, nodeTieIns, Map.of());
    }

    /**
     * Повторно выводит денежные интервалы по выдаваемой оси; road/tram сохраняют свой допуск
     * округления.
     */
    public Map<String, List<Interval>> extractEmitted(
            List<RouteEdge> original,
            List<RouteEdge> emitted,
            List<ImportedOfficialFeature> features,
            Map<String, Set<String>> nodeTieIns) {
        Map<String, RouteEdge> byId = new HashMap<>();
        for (RouteEdge edge : original) byId.put(edge.getId(), edge);
        return extract(emitted, features, nodeTieIns, byId);
    }

    private Map<String, List<Interval>> extract(
            List<RouteEdge> edges,
            List<ImportedOfficialFeature> features,
            Map<String, Set<String>> nodeTieIns,
            Map<String, RouteEdge> originalEdges) {
        Map<String, List<Interval>> result = new LinkedHashMap<>();
        for (DepthPhysicalChains.Chain chain :
                DepthPhysicalChains.build(edges, nodeTieIns.keySet())) {
            active();
            LineString line = line(chain.edge);
            LineString original =
                    originalEdges.isEmpty() ? null : originalLine(chain, originalEdges);
            List<RouteCoordinate> points = chain.edge.getCoordinates();
            Set<String> firstHeat =
                    DepthSourceTieIns.heatIds(
                            nodeTieIns.getOrDefault(chain.edge.getUpstreamNodeId(), Set.of()),
                            points.get(0),
                            features);
            Set<String> lastHeat =
                    DepthSourceTieIns.heatIds(
                            nodeTieIns.getOrDefault(chain.edge.getDownstreamNodeId(), Set.of()),
                            points.get(points.size() - 1),
                            features);
            List<Interval> global = new ArrayList<>();
            for (ImportedOfficialFeature feature : features) {
                active();
                String type =
                        "heat_network".equals(feature.getObjectType())
                                ? "heat_network"
                                : "restriction".equals(feature.getObjectType())
                                        ? feature.getAttributes().path("restriction_type").asText()
                                        : null;
                SpatialConstraintRule rule = type == null ? null : catalog.find(type).orElse(null);
                if (rule == null || rule.isForbidden()) continue;
                Geometry source = feature.getMetricGeometry();
                if (source == null || source.isEmpty())
                    throw new IllegalArgumentException("SPECIAL_SECTION_SOURCE_GEOMETRY");
                if (!line.getEnvelopeInternal().intersects(source.getEnvelopeInternal())) continue;
                if (RoadCrossingClearance.supports(type)) {
                    boolean forward = chain.members.get(0).forward;
                    LineString physical = forward ? line : (LineString) line.reverse();
                    double axisClearance =
                            clearance
                                    .axisClearanceM(type, chain.edge.getDiameter(), null)
                                    .doubleValue();
                    double minimumAngle = rule.getMinimumCrossingAngleDegrees().doubleValue();
                    double extension = rule.getSpecialExtensionM().doubleValue();
                    LineString originalPhysical =
                            original == null
                                    ? null
                                    : forward ? original : (LineString) original.reverse();
                    RoadCrossingClearance.Assessment assessment =
                            originalPhysical == null
                                    ? roads.assess(
                                            physical,
                                            source,
                                            axisClearance,
                                            minimumAngle,
                                            extension)
                                    : roads.assessRoundedSections(
                                            originalPhysical,
                                            physical,
                                            source,
                                            axisClearance,
                                            minimumAngle,
                                            extension);
                    if (!assessment.isAllowed())
                        throw new IllegalArgumentException(
                                "SPECIAL_SECTION_SOURCE_GEOMETRY: " + feature.getFeatureId());
                    for (RoadCrossingClearance.Interval span : assessment.getIntervals()) {
                        double a = forward ? span.getStartM() : line.getLength() - span.getEndM();
                        double b = forward ? span.getEndM() : line.getLength() - span.getStartM();
                        global.add(new Interval(a, b, type, feature.getFeatureId(), a, b));
                    }
                } else {
                    List<double[]> hits = intersections(line, source);
                    for (int hitIndex = 0; hitIndex < hits.size(); hitIndex++) {
                        double[] hit = hits.get(hitIndex);
                        if ("heat_network".equals(type)
                                && hit[0] == hit[1]
                                && (hitIndex == 0
                                                && hit[0] <= CONTACT_EPSILON_M
                                                && firstHeat.contains(feature.getFeatureId())
                                                && ExistingSourceContact.liesOnNearestSegment(
                                                        source,
                                                        line.getCoordinateN(0),
                                                        new LengthIndexedLine(line)
                                                                .extractPoint(hit[0]))
                                        || hitIndex == hits.size() - 1
                                                && line.getLength() - hit[1] <= CONTACT_EPSILON_M
                                                && lastHeat.contains(feature.getFeatureId())
                                                && ExistingSourceContact.liesOnNearestSegment(
                                                        source,
                                                        line.getCoordinateN(
                                                                line.getNumPoints() - 1),
                                                        new LengthIndexedLine(line)
                                                                .extractPoint(hit[0])))) continue;
                        double extension = rule.getSpecialExtensionM().doubleValue();
                        double a = Math.max(0, hit[0] - extension),
                                b = Math.min(line.getLength(), hit[1] + extension);
                        global.add(new Interval(a, b, type, feature.getFeatureId(), a, b));
                    }
                }
            }
            double offset = 0;
            for (DepthPhysicalChains.Member member : chain.members) {
                active();
                double length = line(member.edge).getLength();
                List<Interval> local = new ArrayList<>();
                for (Interval span : global) {
                    double a = Math.max(offset, span.startM),
                            b = Math.min(offset + length, span.endM);
                    if (b <= a) continue;
                    local.add(
                            new Interval(
                                    member.forward ? a - offset : offset + length - b,
                                    member.forward ? b - offset : offset + length - a,
                                    span.type,
                                    span.sourceId,
                                    member.forward
                                            ? span.startM - offset
                                            : offset + length - span.endM,
                                    member.forward
                                            ? span.endM - offset
                                            : offset + length - span.startM));
                }
                result.put(member.edge.getId(), List.copyOf(local));
                offset += length;
            }
        }
        return result;
    }

    private LineString originalLine(
            DepthPhysicalChains.Chain chain, Map<String, RouteEdge> originals) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (DepthPhysicalChains.Member member : chain.members) {
            List<RouteCoordinate> points =
                    new ArrayList<>(originals.get(member.edge.getId()).getCoordinates());
            if (!member.forward) Collections.reverse(points);
            for (int i = coordinates.isEmpty() ? 0 : 1; i < points.size(); i++)
                coordinates.add(points.get(i).toCoordinate());
        }
        return geometryFactory.createLineString(coordinates.toArray(Coordinate[]::new));
    }

    private List<double[]> intersections(LineString line, Geometry source) {
        List<double[]> ranges = new ArrayList<>();
        double offset = 0;
        for (int i = 1; i < line.getNumPoints(); i++) {
            active();
            LineString segment =
                    geometryFactory.createLineString(
                            new Coordinate[] {line.getCoordinateN(i - 1), line.getCoordinateN(i)});
            if (segment.getEnvelopeInternal().intersects(source.getEnvelopeInternal())) {
                ranges(
                        segment.intersection(source),
                        new LengthIndexedLine(segment),
                        offset,
                        ranges);
            }
            offset += segment.getLength();
        }
        ranges.sort(Comparator.comparingDouble(span -> span[0]));
        List<double[]> merged = new ArrayList<>();
        for (double[] span : ranges) {
            if (!merged.isEmpty() && span[0] <= merged.get(merged.size() - 1)[1] + 1e-9) {
                merged.get(merged.size() - 1)[1] =
                        Math.max(merged.get(merged.size() - 1)[1], span[1]);
            } else merged.add(span.clone());
        }
        return merged;
    }

    private void ranges(
            Geometry intersection,
            LengthIndexedLine segment,
            double offset,
            List<double[]> result) {
        if (intersection.isEmpty()) return;
        if (intersection instanceof GeometryCollection) {
            for (int i = 0; i < intersection.getNumGeometries(); i++)
                ranges(intersection.getGeometryN(i), segment, offset, result);
            return;
        }
        double a = Double.POSITIVE_INFINITY, b = Double.NEGATIVE_INFINITY;
        for (Coordinate coordinate : intersection.getCoordinates()) {
            double station = offset + segment.project(coordinate);
            a = Math.min(a, station);
            b = Math.max(b, station);
        }
        if (Double.isFinite(a)) result.add(new double[] {a, b});
    }

    private LineString line(RouteEdge edge) {
        return geometryFactory.createLineString(
                edge.getCoordinates().stream()
                        .map(RouteCoordinate::toCoordinate)
                        .toArray(Coordinate[]::new));
    }

    private static void active() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    public static final class Interval {
        private final double startM, endM, sourceStartM, sourceEndM;
        private final String type, sourceId;

        private Interval(
                double startM,
                double endM,
                String type,
                String sourceId,
                double sourceStartM,
                double sourceEndM) {
            this.startM = startM;
            this.endM = endM;
            this.type = type;
            this.sourceId = sourceId;
            this.sourceStartM = sourceStartM;
            this.sourceEndM = sourceEndM;
        }

        public double getStartM() {
            return startM;
        }

        public double getEndM() {
            return endM;
        }

        public double getSourceStartM() {
            return sourceStartM;
        }

        public double getSourceEndM() {
            return sourceEndM;
        }

        public String getType() {
            return type;
        }

        public String getSourceId() {
            return sourceId;
        }
    }
}
