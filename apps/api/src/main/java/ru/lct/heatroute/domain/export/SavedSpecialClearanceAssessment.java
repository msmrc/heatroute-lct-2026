package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.TopologyException;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatroute.domain.constraints.OfficialAxisClearance;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.constraints.SpatialConstraintRule;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Повторно допускает road/tram по исходному импорту, конечному ДУ и выдаваемой геометрии.
 * Геометрический допуск принадлежит RoadCrossingClearance; здесь проверяется сохранённая разметка.
 * Координаты и интервалы подготавливаются только для одного ребра, исходные полигоны не копируются.
 */
final class SavedSpecialClearanceAssessment {
    // До 2 мм на станцию границы: округление XY до миллиметров и разбиение секций.
    // Это допуск разметки, он никогда не вычитается из обязательного осевого отступа.
    private static final double SECTION_BOUNDARY_TOLERANCE_M = 0.002;
    private static final double STATION_EPSILON_M = 1e-9;

    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final OfficialConstraintCatalog catalog = new OfficialConstraintCatalog();
    private final OfficialAxisClearance clearance;
    private final RoadCrossingClearance crossing = new RoadCrossingClearance();
    private final Map<String, Restriction> restrictions = new LinkedHashMap<>();
    private final STRtree index = new STRtree();

    private SavedSpecialClearanceAssessment(Collection<ImportedOfficialFeature> inputs, OfficialPipeCatalog pipes) {
        clearance = new OfficialAxisClearance(pipes, catalog);
        for (ImportedOfficialFeature feature : inputs) {
            ensureActive();
            String type = feature.getAttributes().path("restriction_type").asText();
            if (!"restriction".equals(feature.getObjectType()) || !RoadCrossingClearance.supports(type)) continue;
            Geometry source = feature.getMetricGeometry();
            if (source == null || source.isEmpty()) fail("Missing road/tram geometry: " + feature.getFeatureId());
            Restriction restriction = new Restriction(feature.getFeatureId(), type, source);
            if (restrictions.putIfAbsent(restriction.id, restriction) != null) {
                fail("Duplicate road/tram restriction ID: " + restriction.id);
            }
            index.insert(source.getEnvelopeInternal(), restriction);
        }
        index.build();
    }

    /** Вызывается после проверки связности сохранённой геометрии и до первой feature любого варианта. */
    static void verify(JsonNode variant, Collection<ImportedOfficialFeature> inputs, OfficialPipeCatalog pipes) {
        try {
            SavedSpecialClearanceAssessment assessment = new SavedSpecialClearanceAssessment(inputs, pipes);
            for (JsonNode edge : variant.path("edges")) {
                ensureActive();
                assessment.verifyEdge(edge);
            }
        } catch (IllegalArgumentException | TopologyException invalid) {
            throw new IllegalStateException("OFFICIAL_EXPORT_INCOMPLETE: recalculate variant "
                    + variant.path("id").asText() + "; " + invalid.getMessage(), invalid);
        }
    }

    private void verifyEdge(JsonNode edge) {
        JsonNode sections = edge.path("sections");
        Set<Restriction> candidates = referencedRestrictions(sections);
        if (restrictions.isEmpty()) return;
        int diameter = edge.path("diameter").intValue();
        LineString original = line(edge.path("coordinates"));
        SavedPath emitted = emittedPath(original, sections);
        Envelope bounds = new Envelope(original.getEnvelopeInternal());
        bounds.expandToInclude(emitted.line.getEnvelopeInternal());
        double maximumClearance = Math.max(clearance.axisClearanceM("road", diameter, null).doubleValue(),
                clearance.axisClearanceM("tram_tracks", diameter, null).doubleValue());
        bounds.expandBy(maximumClearance + SECTION_BOUNDARY_TOLERANCE_M);
        @SuppressWarnings("unchecked")
        List<Restriction> nearby = index.query(bounds);
        candidates.addAll(nearby);
        for (Restriction restriction : candidates) {
            ensureActive();
            RoadCrossingClearance.Assessment actual = assess(original, emitted.line, restriction, diameter, edge);
            verifyCoverage(emitted.sections, restriction, actual.getIntervals(), edge.path("id").asText());
        }
    }

    private RoadCrossingClearance.Assessment assess(LineString original, LineString emitted, Restriction restriction,
            int diameter, JsonNode edge) {
        SpatialConstraintRule rule = catalog.find(restriction.type).orElseThrow();
        double axisClearanceM = clearance.axisClearanceM(restriction.type, diameter, null).doubleValue();
        double minimumAngleDegrees = rule.getMinimumCrossingAngleDegrees().doubleValue();
        double extensionM = rule.getSpecialExtensionM().doubleValue();
        // Primitive строго проверяет original; допуск округления emitted не ослабляет отступы.
        RoadCrossingClearance.Assessment result = emitted == original
                ? crossing.assess(original, restriction.source, axisClearanceM, minimumAngleDegrees, extensionM)
                : crossing.assessRoundedSections(original, emitted, restriction.source,
                        axisClearanceM, minimumAngleDegrees, extensionM);
        if (!result.isAllowed()) fail(result.getFailureCode() + ": " + edge.path("id").asText() + "/" + restriction.id);
        return result;
    }

    /** Явные ссылки проверяются даже за пределами spatial window: метка special не создаёт исключение. */
    private Set<Restriction> referencedRestrictions(JsonNode sections) {
        Set<Restriction> referenced = new LinkedHashSet<>();
        for (JsonNode section : sections) {
            ensureActive();
            Set<String> types = tokens(section.path("restriction_type"));
            Set<String> ids = tokens(section.path("restriction_id"));
            boolean special = "special".equals(section.path("kind").asText());
            Set<String> resolvedTypes = new LinkedHashSet<>();
            for (String id : ids) {
                Restriction restriction = restrictions.get(id);
                if (restriction == null) continue;
                if (!special || !types.contains(restriction.type)) fail("SPECIAL_CROSSING_SECTION_MISMATCH: " + id);
                referenced.add(restriction);
                resolvedTypes.add(restriction.type);
            }
            for (String type : types) {
                if (RoadCrossingClearance.supports(type) && (!special || !resolvedTypes.contains(type))) {
                    fail("SPECIAL_CROSSING_SECTION_MISMATCH: unresolved " + type + " restriction");
                }
            }
        }
        return referenced;
    }

    private SavedPath emittedPath(LineString original, JsonNode sections) {
        if (sections.isEmpty()) return new SavedPath(original,
                List.of(new SectionSpan(0, original.getLength(), false, Set.of(), Set.of())));
        List<Coordinate> points = new ArrayList<>();
        List<SectionSpan> spans = new ArrayList<>();
        double station = 0;
        for (JsonNode section : sections) {
            ensureActive();
            if (!section.path("length_m").isNumber() || section.path("length_m").decimalValue().signum() <= 0) {
                fail("SPECIAL_CROSSING_SECTION_MISMATCH: non-positive section length");
            }
            double start = station;
            for (JsonNode point : section.path("coordinates")) {
                ensureActive();
                Coordinate coordinate = SavedRouteGeometry.coordinate(point).toCoordinate();
                if (!points.isEmpty()) {
                    Coordinate previous = points.get(points.size() - 1);
                    if (previous.equals2D(coordinate)) continue;
                    station += previous.distance(coordinate);
                }
                points.add(coordinate);
            }
            spans.add(new SectionSpan(start, station, "special".equals(section.path("kind").asText()),
                    tokens(section.path("restriction_type")), tokens(section.path("restriction_id"))));
        }
        return new SavedPath(geometryFactory.createLineString(points.toArray(new Coordinate[0])), spans);
    }

    private LineString line(JsonNode coordinates) {
        List<Coordinate> points = new ArrayList<>();
        for (JsonNode point : coordinates) {
            ensureActive();
            Coordinate coordinate = SavedRouteGeometry.coordinate(point).toCoordinate();
            if (points.isEmpty() || !points.get(points.size() - 1).equals2D(coordinate)) points.add(coordinate);
        }
        return geometryFactory.createLineString(points.toArray(new Coordinate[0]));
    }

    /** Сравнивает объединения локальных интервалов; разрывы между отдельными переходами сохраняются. */
    private void verifyCoverage(List<SectionSpan> sections, Restriction restriction,
            List<RoadCrossingClearance.Interval> intervals, String edgeId) {
        List<Span> expected = new ArrayList<>();
        for (RoadCrossingClearance.Interval interval : intervals) {
            appendSpan(expected, interval.getStartM(), interval.getEndM());
        }
        List<Span> saved = new ArrayList<>();
        for (SectionSpan section : sections) {
            ensureActive();
            if (section.special && section.types.contains(restriction.type) && section.ids.contains(restriction.id)) {
                appendSpan(saved, section.start, section.end);
            }
        }
        if (expected.size() != saved.size()) fail("SPECIAL_CROSSING_SECTION_MISMATCH: " + edgeId + "/" + restriction.id);
        for (int part = 0; part < expected.size(); part++) {
            if (Math.abs(expected.get(part).start - saved.get(part).start) > SECTION_BOUNDARY_TOLERANCE_M
                    || Math.abs(expected.get(part).end - saved.get(part).end) > SECTION_BOUNDARY_TOLERANCE_M) {
                fail("SPECIAL_CROSSING_SECTION_MISMATCH: " + edgeId + "/" + restriction.id);
            }
        }
    }

    private void appendSpan(List<Span> spans, double start, double end) {
        ensureActive();
        if (!spans.isEmpty() && start <= spans.get(spans.size() - 1).end + STATION_EPSILON_M) {
            Span previous = spans.remove(spans.size() - 1);
            spans.add(new Span(previous.start, Math.max(previous.end, end)));
        } else {
            spans.add(new Span(start, end));
        }
    }

    private Set<String> tokens(JsonNode value) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : value.asText("").split("\\+")) {
            if (!token.isBlank()) result.add(token.trim());
        }
        return result;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Saved road/tram validation cancelled");
    }

    private static void fail(String message) { throw new IllegalArgumentException(message); }

    private static final class Restriction {
        private final String id;
        private final String type;
        private final Geometry source;
        private Restriction(String id, String type, Geometry source) { this.id = id; this.type = type; this.source = source; }
    }

    private static final class Span {
        private final double start;
        private final double end;
        private Span(double start, double end) { this.start = start; this.end = end; }
    }

    private static final class SectionSpan {
        private final double start;
        private final double end;
        private final boolean special;
        private final Set<String> types;
        private final Set<String> ids;
        private SectionSpan(double start, double end, boolean special, Set<String> types, Set<String> ids) {
            this.start = start;
            this.end = end;
            this.special = special;
            this.types = types;
            this.ids = ids;
        }
    }

    private static final class SavedPath {
        private final LineString line;
        private final List<SectionSpan> sections;
        private SavedPath(LineString line, List<SectionSpan> sections) { this.line = line; this.sections = sections; }
    }
}
