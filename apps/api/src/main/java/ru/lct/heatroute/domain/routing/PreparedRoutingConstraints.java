package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryComponentFilter;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Подготавливает геометрию препятствий в пределах одного запуска, не сохраняет готовые маршруты.
 * Экземпляр принадлежит одному окружению расчёта и не предназначен для параллельного доступа.
 * Возвращаемые списки независимы; геометрия ограничений принадлежит компоненту и доступна
 * потребителям только для чтения, как и геометрия входных данных ConstraintIndex.
 * Исходные features и их атрибуты не удерживаются; неиспользуемый JTS userData удаляется из копии.
 * Лимиты ограничивают число удерживаемых записей и резерв явных координат: исходная геометрия,
 * буфер и, если индекс возможен, его собственная копия буфера вместе с концами рёбер.
 * Резерв вычисляется без создания индекса и может завышаться для невалидных полигонов.
 * Это не лимит байтов (узлы/границы JTS и временные массивы не считаются), не лимит полного
 * возвращаемого списка и не ограничение объектов, ещё используемых потребителями после eviction.
 */
final class PreparedRoutingConstraints {
    private static final int DEFAULT_MAX_ENTRIES = 512;
    private static final long DEFAULT_MAX_COORDINATES = 100_000;

    private final OfficialRouteGeometryRules rules;
    private final int maxEntries;
    private final long maxCoordinates;
    private final Map<Key, Entry> retained = new LinkedHashMap<>(16, 0.75f, true);
    private long retainedCoordinates;

    PreparedRoutingConstraints(OfficialRouteGeometryRules rules) {
        this(rules, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_COORDINATES);
    }

    PreparedRoutingConstraints(OfficialRouteGeometryRules rules, int maxEntries, long maxCoordinates) {
        if (maxEntries < 0 || maxCoordinates < 0) {
            throw new IllegalArgumentException("Preparation limits must be nonnegative");
        }
        this.rules = Objects.requireNonNull(rules, "rules");
        this.maxEntries = maxEntries;
        this.maxCoordinates = maxCoordinates;
    }

    List<Constraint> prepare(List<ImportedOfficialFeature> features, int diameter) {
        List<Constraint> result = new ArrayList<>();
        for (ImportedOfficialFeature feature : features) {
            String type = rules.constraintType(feature);
            if (type == null) {
                continue;
            }
            Geometry source = feature.getMetricGeometry();
            if (source == null || source.isEmpty()) {
                continue;
            }
            // Проверяем ДУ и при повторном использовании: осевой отступ всех forbidden-типов
            // включает половину ширины пары. Special-типы не зависят от ДУ в этой подготовке.
            Key key = new Key(feature.getFeatureId(), type, rules.preparationClearanceM(type, diameter));
            Entry entry = retained.get(key);
            if (entry != null && sameGeometry(entry.constraint.source(), source)) {
                result.add(entry.constraint);
                continue;
            }
            if (entry != null) {
                retained.remove(key);
                retainedCoordinates -= entry.coordinates;
            }
            Constraint compiled = compile(feature, diameter);
            if (compiled != null) {
                result.add(compiled);
                retain(key, compiled);
            }
        }
        // Stable sort keeps every duplicate occurrence and the caller's order for equal keys.
        rules.sortConstraints(result);
        return result;
    }

    private Constraint compile(ImportedOfficialFeature feature, int diameter) {
        Geometry owned = feature.getMetricGeometry().copy();
        // JTS copy() otherwise retains arbitrary, unused application metadata by reference.
        owned.apply((GeometryComponentFilter) geometry -> geometry.setUserData(null));
        ImportedOfficialFeature snapshot = new ImportedOfficialFeature(
                feature.getFeatureId(), feature.getObjectType(), feature.getAttributes(), owned);
        List<Constraint> compiled = rules.baseConstraints(List.of(snapshot), diameter);
        return compiled.isEmpty() ? null : compiled.get(0);
    }

    private void retain(Key key, Constraint constraint) {
        long coordinates = (long) constraint.source().getNumPoints()
                + (constraint.blocked() == null ? 0L : constraint.blocked().getNumPoints())
                + constraint.segmentIndexCoordinateReservation();
        if (maxEntries == 0 || coordinates > maxCoordinates) {
            return;
        }
        Iterator<Entry> oldest = retained.values().iterator();
        while (oldest.hasNext()
                && (retained.size() >= maxEntries || retainedCoordinates > maxCoordinates - coordinates)) {
            retainedCoordinates -= oldest.next().coordinates;
            oldest.remove();
        }
        retained.put(key, new Entry(constraint, coordinates));
        retainedCoordinates += coordinates;
    }

    int retainedEntryCount() {
        return retained.size();
    }

    long retainedCoordinateCount() {
        return retainedCoordinates;
    }

    static boolean sameGeometry(Geometry left, Geometry right) {
        if (left.getClass() != right.getClass()
                || left.getSRID() != right.getSRID()
                || left.getFactory().getSRID() != right.getFactory().getSRID()
                || left.getFactory().getClass() != right.getFactory().getClass()
                || left.getFactory().getCoordinateSequenceFactory()
                        != right.getFactory().getCoordinateSequenceFactory()
                || !left.getPrecisionModel().equals(right.getPrecisionModel())
                || !left.equalsExact(right)) {
            return false;
        }
        // JTS equalsExact is XY-only. Keep Z, M, signed zero and sequence layout exact too;
        // source geometries (not just their two-dimensional buffers) are part of the result.
        if (left instanceof Point) {
            return sameSequence(((Point) left).getCoordinateSequence(), ((Point) right).getCoordinateSequence());
        }
        if (left instanceof LineString) {
            return sameSequence(((LineString) left).getCoordinateSequence(),
                    ((LineString) right).getCoordinateSequence());
        }
        if (left instanceof Polygon) {
            Polygon leftPolygon = (Polygon) left;
            Polygon rightPolygon = (Polygon) right;
            if (!sameGeometry(leftPolygon.getExteriorRing(), rightPolygon.getExteriorRing())) {
                return false;
            }
            for (int index = 0; index < leftPolygon.getNumInteriorRing(); index++) {
                if (!sameGeometry(leftPolygon.getInteriorRingN(index), rightPolygon.getInteriorRingN(index))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof GeometryCollection) {
            for (int index = 0; index < left.getNumGeometries(); index++) {
                if (!sameGeometry(left.getGeometryN(index), right.getGeometryN(index))) {
                    return false;
                }
            }
            return true;
        }
        // A custom geometry implementation needs an explicit exact comparison before reuse.
        return false;
    }

    private static boolean sameSequence(CoordinateSequence left, CoordinateSequence right) {
        if (left.size() != right.size() || left.getDimension() != right.getDimension()
                || left.getMeasures() != right.getMeasures()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            for (int ordinate = 0; ordinate < left.getDimension(); ordinate++) {
                if (Double.doubleToRawLongBits(left.getOrdinate(index, ordinate))
                        != Double.doubleToRawLongBits(right.getOrdinate(index, ordinate))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final class Entry {
        private final Constraint constraint;
        private final long coordinates;

        private Entry(Constraint constraint, long coordinates) {
            this.constraint = constraint;
            this.coordinates = coordinates;
        }
    }

    private static final class Key {
        private final String id;
        private final String type;
        private final BigDecimal clearance;

        private Key(String id, String type, BigDecimal clearance) {
            this.id = id;
            this.type = type;
            this.clearance = clearance;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return Objects.equals(id, key.id) && Objects.equals(type, key.type)
                    && Objects.equals(clearance, key.clearance);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, type, clearance);
        }
    }
}
