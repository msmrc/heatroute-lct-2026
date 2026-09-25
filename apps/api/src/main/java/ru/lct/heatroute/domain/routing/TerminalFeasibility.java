package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Отделяет доказанно невозможные вводы от ограниченного поиска общей сети.
 * ТЗ §2.2 освобождает ввод только от собственного ОКС; остальные запреты таблицы 2 действуют.
 * Отсутствие найденного пути, нормали или порта не является доказательством невозможности.
 */
final class TerminalFeasibility {
    private final OfficialConstraintCatalog catalog = new OfficialConstraintCatalog();
    private final GeometryFactory geometryFactory = new GeometryFactory();

    <T> Partition<T> partition(List<T> terminals, Function<T, Coordinate> coordinates,
            OfficialRoutingEnvironment environment) {
        List<T> searchable = new ArrayList<>();
        Map<T, List<String>> blocked = new LinkedHashMap<>();
        for (T terminal : terminals) {
            ensureActive();
            List<String> blockers = blockers(coordinates.apply(terminal), environment);
            if (blockers.isEmpty()) searchable.add(terminal);
            else blocked.put(terminal, blockers);
        }
        return new Partition<>(searchable, blocked);
    }

    private List<String> blockers(Coordinate coordinate, OfficialRoutingEnvironment environment) {
        Point point = geometryFactory.createPoint(coordinate);
        TreeSet<String> result = new TreeSet<>();
        // В windowed-расчёте фоновые полигоны могут отсутствовать среди исходных core features.
        for (ImportedOfficialFeature feature : environment.featuresInWindow(coordinate, coordinate)) {
            ensureActive();
            if (!"restriction".equals(feature.getObjectType())) continue;
            String type = feature.getAttributes().path("restriction_type").asText();
            if ("oks".equals(type) || !catalog.find(type).map(rule -> rule.isForbidden()).orElse(false)) continue;
            Geometry polygon = feature.getMetricGeometry();
            if (polygon != null && polygon.getDimension() == 2 && !polygon.isEmpty()
                    && polygon.getEnvelopeInternal().contains(coordinate) && polygon.contains(point)) {
                result.add(type + ":" + feature.getFeatureId());
            }
        }
        return List.copyOf(result);
    }

    private void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Route calculation was cancelled");
    }

    static final class Partition<T> {
        private final List<T> searchable;
        private final Map<T, List<String>> blocked;

        private Partition(List<T> searchable, Map<T, List<String>> blocked) {
            this.searchable = List.copyOf(searchable);
            this.blocked = Collections.unmodifiableMap(new LinkedHashMap<>(blocked));
        }

        List<T> searchable() { return searchable; }
        Map<T, List<String>> blocked() { return blocked; }
    }
}
