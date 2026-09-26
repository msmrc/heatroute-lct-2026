package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatroute.domain.depth.OfficialSpecialSectionIntervals;
import ru.lct.heatroute.domain.depth.OfficialSpecialSectionIntervals.Interval;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Сопоставляет денежные секции с физическими интервалами источников до первого байта экспорта. */
final class SavedSpecialSectionAssessment {
    // До 2 мм только на внешние границы совпадающих смысловых интервалов: округление XY/разбиения.
    // Этот допуск не объединяет внутренние пробелы и не уменьшает физические ±2/3 м.
    private static final double BOUNDARY_ROUNDING_M = .002;
    private static final Set<String> TYPES =
            Set.of("road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network");

    private SavedSpecialSectionAssessment() {}

    static Map<JsonNode, Set<String>> verify(
            JsonNode variant, List<ImportedOfficialFeature> features, OfficialPipeCatalog pipes) {
        try {
            List<RouteEdge> edges = new ArrayList<>();
            Map<String, JsonNode> savedById = new HashMap<>();
            for (JsonNode saved : variant.path("edges")) {
                active();
                String id = text(saved, "id");
                if (savedById.put(id, saved) != null) fail("DUPLICATE_EDGE");
                List<RouteCoordinate> coordinates = coordinates(saved.path("coordinates"));
                JsonNode diameter = saved.path("diameter");
                if (!diameter.isIntegralNumber() || pipes.byDiameter(diameter.intValue()).isEmpty())
                    fail("DIAMETER");
                if (!saved.path("length_m").isNumber()
                        || saved.path("length_m").decimalValue().signum() <= 0) fail("LENGTH");
                edges.add(
                        new RouteEdge(
                                id,
                                text(saved, "upstream_node_id"),
                                text(saved, "downstream_node_id"),
                                saved.path("length_m").doubleValue(),
                                coordinates,
                                List.of(),
                                null,
                                diameter.intValue()));
            }
            Map<String, Set<String>> ties = new HashMap<>();
            for (JsonNode node : variant.path("nodes")) {
                if (node.path("root").asBoolean())
                    ties.put(
                            text(node, "id"),
                            node.hasNonNull("target_id")
                                    ? Set.of(text(node, "target_id"))
                                    : Set.of());
            }
            OfficialSpecialSectionIntervals intervals = new OfficialSpecialSectionIntervals(pipes);
            Map<String, List<Interval>> source = intervals.extract(edges, features, ties);
            Map<JsonNode, Set<String>> resolved = new IdentityHashMap<>();
            for (RouteEdge edge : edges)
                check(
                        edge,
                        savedById.get(edge.getId()),
                        source.getOrDefault(edge.getId(), List.of()),
                        resolved);
            List<RouteEdge> emitted = emittedEdges(edges, savedById);
            Map<String, List<Interval>> emittedSource =
                    intervals.extractEmitted(edges, emitted, features, ties);
            for (RouteEdge edge : emitted)
                check(
                        edge,
                        savedById.get(edge.getId()),
                        emittedSource.getOrDefault(edge.getId(), List.of()),
                        resolved);
            return resolved;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "OFFICIAL_EXPORT_INCOMPLETE: recalculate variant "
                            + variant.path("id").asText()
                            + "; "
                            + invalid.getMessage(),
                    invalid);
        }
    }

    private static List<RouteEdge> emittedEdges(
            List<RouteEdge> original, Map<String, JsonNode> savedById) {
        List<RouteEdge> result = new ArrayList<>();
        for (RouteEdge edge : original) {
            active();
            JsonNode sections = savedById.get(edge.getId()).path("sections");
            if (sections.isMissingNode() || sections.isEmpty()) {
                result.add(edge);
                continue;
            }
            List<RouteCoordinate> points = new ArrayList<>();
            for (JsonNode section : sections) {
                for (RouteCoordinate point : coordinates(section.path("coordinates"))) {
                    if (points.isEmpty()
                            || !points.get(points.size() - 1)
                                    .toCoordinate()
                                    .equals2D(point.toCoordinate())) points.add(point);
                }
            }
            result.add(
                    new RouteEdge(
                            edge.getId(),
                            edge.getUpstreamNodeId(),
                            edge.getDownstreamNodeId(),
                            edge.getLengthM().doubleValue(),
                            points,
                            List.of(),
                            edge.getFlowTph(),
                            edge.getDiameter()));
        }
        return result;
    }

    private static void check(
            RouteEdge edge,
            JsonNode saved,
            List<Interval> intervals,
            Map<JsonNode, Set<String>> resolved) {
        active();
        LineString line =
                new GeometryFactory()
                        .createLineString(
                                edge.getCoordinates().stream()
                                        .map(RouteCoordinate::toCoordinate)
                                        .toArray(Coordinate[]::new));
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        TreeSet<Double> cuts = new TreeSet<>(List.of(0.0, line.getLength()));
        for (Interval span : intervals) {
            cuts.add(span.getStartM());
            cuts.add(span.getEndM());
        }
        List<Double> ordered = new ArrayList<>(cuts);
        List<Run> expected = new ArrayList<>();
        for (int i = 1; i < ordered.size(); i++) {
            active();
            double a = ordered.get(i - 1), b = ordered.get(i);
            if (b <= a) continue;
            double middle = (a + b) / 2;
            List<Interval> current =
                    intervals.stream()
                            .filter(s -> s.getStartM() <= middle && middle <= s.getEndM())
                            .sorted(
                                    Comparator.comparingDouble(Interval::getSourceStartM)
                                            .thenComparing(Interval::getType)
                                            .thenComparing(Interval::getSourceId))
                            .collect(Collectors.toList());
            Set<String> types =
                    current.stream()
                            .map(Interval::getType)
                            .collect(Collectors.toCollection(TreeSet::new));
            Set<String> ids =
                    current.stream()
                            .map(Interval::getSourceId)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            List<Interval> reverse = new ArrayList<>(current);
            reverse.sort(
                    Comparator.comparingDouble(Interval::getSourceEndM)
                            .reversed()
                            .thenComparing(Interval::getType)
                            .thenComparing(Interval::getSourceId));
            Set<String> reversedIds =
                    reverse.stream()
                            .map(Interval::getSourceId)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            // Форматы внутреннего DTO: текущий builder, его RoutePath.reversed() и сохранённый
            // sorted-порядок.
            // ID остаётся непрозрачной строкой: знак '+' внутри ID не является разделителем для
            // этой проверки.
            Set<String> formats =
                    new HashSet<>(
                            List.of(
                                    String.join("+", ids),
                                    String.join("+", new TreeSet<>(ids)),
                                    String.join("+", reversedIds)));
            append(expected, new Run(a, b, types, formats, ids, List.of()));
        }
        List<Run> actual = new ArrayList<>();
        JsonNode sections = saved.path("sections");
        if (sections.isMissingNode() || sections.isEmpty()) {
            actual.add(new Run(0, line.getLength(), Set.of(), Set.of(""), Set.of(), List.of()));
        } else {
            if (!sections.isArray()) fail("SECTIONS_FORMAT");
            for (JsonNode section : sections) {
                active();
                List<RouteCoordinate> points = coordinates(section.path("coordinates"));
                double a = indexed.project(points.get(0).toCoordinate()),
                        b = indexed.project(points.get(points.size() - 1).toCoordinate());
                if (b <= a) fail("GEOMETRY_ORDER");
                Set<String> types = new TreeSet<>();
                String kind = text(section, "kind");
                String ids =
                        section.hasNonNull("restriction_id")
                                ? section.path("restriction_id").asText()
                                : "";
                if ("special".equals(kind)) {
                    String raw = text(section, "restriction_type");
                    types.addAll(Arrays.asList(raw.split("\\+")));
                    if (types.isEmpty() || !TYPES.containsAll(types) || ids.isEmpty())
                        fail("TYPE_OR_SOURCE");
                } else if (!"base".equals(kind)
                        || section.hasNonNull("restriction_type")
                        || !ids.isEmpty()) fail("BASE_METADATA");
                Run meaning = expectedAt(expected, (a + b) / 2);
                if (!meaning.types.equals(types) || !meaning.formats.contains(ids))
                    fail("SOURCE_OR_TYPE: " + edge.getId());
                append(
                        actual,
                        new Run(a, b, types, Set.of(ids), meaning.sourceIds, List.of(section)));
            }
        }
        if (expected.size() != actual.size()) fail("COVERAGE: " + edge.getId());
        for (int i = 0; i < expected.size(); i++) {
            Run a = expected.get(i), b = actual.get(i);
            if (!a.types.equals(b.types) || !a.sourceIds.equals(b.sourceIds))
                fail("SOURCE_OR_TYPE: " + edge.getId());
            if (Math.abs(a.start - b.start) > BOUNDARY_ROUNDING_M
                    || Math.abs(a.end - b.end) > BOUNDARY_ROUNDING_M)
                fail("COVERAGE: " + edge.getId());
            for (JsonNode section : b.sections) resolved.put(section, a.sourceIds);
        }
    }

    private static Run expectedAt(List<Run> runs, double station) {
        int low = 0, high = runs.size() - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (station < runs.get(middle).end) high = middle;
            else low = middle + 1;
        }
        return runs.get(low);
    }

    private static void append(List<Run> runs, Run next) {
        if (!runs.isEmpty()) {
            Run last = runs.get(runs.size() - 1);
            // Сливаются только соприкасающиеся секции одинаковых типов и целых source IDs; пробел
            // не закрывается.
            if (last.end == next.start
                    && last.types.equals(next.types)
                    && last.sourceIds.equals(next.sourceIds)) {
                List<JsonNode> sections = new ArrayList<>(last.sections);
                sections.addAll(next.sections);
                Set<String> formats = new HashSet<>(last.formats);
                formats.addAll(next.formats);
                runs.set(
                        runs.size() - 1,
                        new Run(
                                last.start,
                                next.end,
                                last.types,
                                formats,
                                last.sourceIds,
                                sections));
                return;
            }
        }
        runs.add(next);
    }

    private static List<RouteCoordinate> coordinates(JsonNode array) {
        if (!array.isArray() || array.size() < 2) {
            fail("GEOMETRY");
        }
        List<RouteCoordinate> result = new ArrayList<>();
        for (JsonNode point : array) result.add(SavedRouteGeometry.coordinate(point));
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) fail("MISSING_" + field);
        return value.asText();
    }

    private static void active() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static void fail(String message) {
        throw new IllegalArgumentException("SPECIAL_SECTION_" + message);
    }

    private static final class Run {
        final double start, end;
        final Set<String> types;
        final Set<String> formats;
        final Set<String> sourceIds;
        final List<JsonNode> sections;

        Run(
                double start,
                double end,
                Set<String> types,
                Set<String> formats,
                Set<String> sourceIds,
                List<JsonNode> sections) {
            this.start = start;
            this.end = end;
            this.types = Set.copyOf(types);
            this.formats = Set.copyOf(formats);
            this.sourceIds = Set.copyOf(sourceIds);
            this.sections = List.copyOf(sections);
        }
    }
}
