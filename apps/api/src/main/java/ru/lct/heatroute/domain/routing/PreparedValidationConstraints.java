package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryComponentFilter;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.impl.CoordinateArraySequenceFactory;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Отбирает препятствия по полной геометрии проверяемого участка, сохраняя границы успешных буферов.
 * Первая подготовка всегда выполняется; далёкий тяжёлый буфер можно не восстанавливать после eviction.
 * Общие snapshots, bounds и тяжёлые геометрии имеют единый координатный бюджет. Это не лимит байтов
 * JTS или возвращённых наружу объектов. Однопоточный контекст одного расчёта; маршрутов/ответов нет.
 */
final class PreparedValidationConstraints {
    private static final long ENVELOPE_COORDINATES = 2;
    private final OfficialRouteGeometryRules rules;
    private final int maxSources;
    private final int maxRecords;
    private final long maxCoordinates;
    private final Map<FeatureKey, Source> sources = new LinkedHashMap<>();
    private final Map<Key, Entry> records = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Key, Entry> buffers = new LinkedHashMap<>(16, 0.75f, true);
    private long coordinates;
    private long preparedForbidden;

    PreparedValidationConstraints(OfficialRouteGeometryRules rules) {
        this(rules, 512, 2048, 100_000);
    }

    PreparedValidationConstraints(OfficialRouteGeometryRules rules,
            int maxSources, int maxRecords, long maxCoordinates) {
        this.rules = Objects.requireNonNull(rules, "rules");
        if (maxSources < 0 || maxRecords < 0 || maxCoordinates < 0) {
            throw new IllegalArgumentException("Preparation limits must be nonnegative");
        }
        this.maxSources = maxSources;
        this.maxRecords = maxRecords;
        this.maxCoordinates = maxCoordinates;
    }

    /** Не предполагаем обычное поведение пользовательских rules, geometry и precision models. */
    boolean supports(List<ImportedOfficialFeature> features) {
        try {
            return supportsInput(features);
        } catch (RuntimeException unsupported) {
            // Предикат не переносит исключение раньше обычной проверки (например, при edges=[]).
            // Fallback выполняет прежний validator и сохраняет его порядок отказов.
            return false;
        }
    }

    private boolean supportsInput(List<ImportedOfficialFeature> features) {
        if (rules.getClass() != OfficialRouteGeometryRules.class
                || !rules.hasStandardPreparationRules() || features == null) return false;
        for (ImportedOfficialFeature feature : features) {
            if (feature == null) return false;
            String type = rules.constraintType(feature);
            if (type == null || !rules.hasConstraintRule(type)) continue;
            Geometry geometry = feature.getMetricGeometry();
            if (geometry == null) continue;
            if (!ordinaryGeometry(geometry)) return false;
            if (geometry.isEmpty()) continue;
            if (feature.getFeatureId() == null || feature.getFeatureId().isBlank()
                    || !finiteCoordinates(geometry)) return false;
        }
        return true;
    }

    List<Constraint> prepareIntersecting(List<ImportedOfficialFeature> features,
            int diameter, Envelope routeEnvelope) {
        if (!supports(features) || !finiteEnvelope(routeEnvelope)) {
            return rules.baseConstraints(features, diameter);
        }
        List<Constraint> result = new ArrayList<>();
        for (ImportedOfficialFeature feature : features) {
            String type = rules.constraintType(feature);
            if (type == null || !rules.hasConstraintRule(type)) continue;
            Geometry geometry = feature.getMetricGeometry();
            if (geometry == null || geometry.isEmpty()) continue;
            // Не пропускаем валидацию ДУ даже у далёкого объекта и при готовых bounds.
            BigDecimal clearance = rules.preparationClearanceM(type, diameter);
            FeatureKey featureKey = new FeatureKey(feature.getFeatureId(), type);
            Source source = sources.get(featureKey);
            if (source != null && !PreparedRoutingConstraints.sameGeometry(source.geometry, geometry)) {
                removeSource(source);
                source = null;
            }
            Key key = new Key(featureKey, clearance);
            Entry entry = records.get(key);
            if (entry != null && !entry.bounds.intersects(routeEnvelope)) continue;
            if (entry != null && entry.constraint != null) {
                buffers.get(key);
                result.add(entry.constraint);
                continue;
            }
            if (source == null) source = snapshot(featureKey, geometry);
            Constraint constraint = compile(feature, source, diameter);
            if (constraint == null) continue;
            Envelope bounds = bounds(constraint, clearance);
            if (entry == null) {
                entry = new Entry(key, source, bounds);
                retainRecord(entry);
            }
            // Bounds вычислены по настоящему buffer; повторы не предполагают его радиус/округление.
            if (entry.bounds.intersects(routeEnvelope)) {
                retainBuffer(entry, constraint);
                result.add(constraint);
            }
        }
        rules.sortConstraints(result);
        return result;
    }

    private Constraint compile(ImportedOfficialFeature feature, Source source, int diameter) {
        ImportedOfficialFeature owned = new ImportedOfficialFeature(
                feature.getFeatureId(), feature.getObjectType(), feature.getAttributes(), source.geometry);
        List<Constraint> compiled = rules.baseConstraints(List.of(owned), diameter);
        if (compiled.isEmpty()) return null;
        Constraint result = compiled.get(0);
        if (result.blocked() != null) preparedForbidden++;
        return result;
    }

    private Source snapshot(FeatureKey key, Geometry geometry) {
        Geometry owned = geometry.copy();
        owned.apply((GeometryComponentFilter) component -> component.setUserData(null));
        return new Source(key, owned);
    }

    /** Собственный prefix проверяет полный осевой отступ, а не уменьшенный на epsilon buffer. */
    private Envelope bounds(Constraint constraint, BigDecimal clearance) {
        Envelope result = new Envelope(constraint.source().getEnvelopeInternal());
        if (clearance != null) {
            double radius = clearance.doubleValue();
            result = new Envelope(Math.nextDown(result.getMinX() - radius),
                    Math.nextUp(result.getMaxX() + radius), Math.nextDown(result.getMinY() - radius),
                    Math.nextUp(result.getMaxY() + radius));
        }
        if (constraint.blocked() != null) result.expandToInclude(constraint.blocked().getEnvelopeInternal());
        return result;
    }

    private void retainRecord(Entry entry) {
        long minimum = (long) entry.source.geometry.getNumPoints() + ENVELOPE_COORDINATES;
        if (maxSources == 0 || maxRecords == 0 || minimum > maxCoordinates) return;
        if (!sources.containsKey(entry.source.key)) {
            sources.put(entry.source.key, entry.source);
            coordinates += entry.source.geometry.getNumPoints();
        }
        records.put(entry.key, entry);
        entry.source.keys.add(entry.key);
        coordinates += ENVELOPE_COORDINATES;
        evictBuffers();
        while (!records.isEmpty() && (coordinates > maxCoordinates
                || records.size() > maxRecords || sources.size() > maxSources)) {
            removeRecord(records.values().iterator().next());
        }
    }

    private void retainBuffer(Entry entry, Constraint constraint) {
        if (records.get(entry.key) != entry) return;
        long reservation = (constraint.blocked() == null ? 0L : constraint.blocked().getNumPoints())
                + constraint.segmentIndexCoordinateReservation();
        entry.constraint = constraint;
        entry.bufferCoordinates = reservation;
        buffers.put(entry.key, entry);
        coordinates += reservation;
        evictBuffers();
    }

    private void evictBuffers() {
        while (coordinates > maxCoordinates && !buffers.isEmpty()) {
            removeBuffer(buffers.values().iterator().next());
        }
    }

    private void removeBuffer(Entry entry) {
        if (buffers.remove(entry.key) == null) return;
        coordinates -= entry.bufferCoordinates;
        entry.bufferCoordinates = 0;
        entry.constraint = null;
    }

    private void removeRecord(Entry entry) {
        removeBuffer(entry);
        records.remove(entry.key);
        coordinates -= ENVELOPE_COORDINATES;
        entry.source.keys.remove(entry.key);
        if (entry.source.keys.isEmpty()) {
            sources.remove(entry.source.key);
            coordinates -= entry.source.geometry.getNumPoints();
        }
    }

    private void removeSource(Source source) {
        for (Key key : new ArrayList<>(source.keys)) removeRecord(records.get(key));
    }

    private static boolean ordinaryGeometry(Geometry geometry) {
        Class<?> type = geometry.getClass();
        if (type != Point.class && type != LineString.class && type != LinearRing.class
                && type != Polygon.class && type != MultiPoint.class && type != MultiLineString.class
                && type != MultiPolygon.class && type != GeometryCollection.class) return false;
        GeometryFactory factory = geometry.getFactory();
        if (factory.getClass() != GeometryFactory.class) return false;
        Class<?> sequences = factory.getCoordinateSequenceFactory().getClass();
        if ((sequences != CoordinateArraySequenceFactory.class && sequences != PackedCoordinateSequenceFactory.class)
                || factory.getPrecisionModel().getClass() != PrecisionModel.class
                || factory.getPrecisionModel().getType() != PrecisionModel.FLOATING) return false;
        if (geometry instanceof Polygon) {
            Polygon polygon = (Polygon) geometry;
            if (!ordinaryGeometry(polygon.getExteriorRing())) return false;
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                if (!ordinaryGeometry(polygon.getInteriorRingN(i))) return false;
            }
        } else if (geometry instanceof GeometryCollection) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                if (!ordinaryGeometry(geometry.getGeometryN(i))) return false;
            }
        }
        return true;
    }

    private static boolean finiteCoordinates(Geometry geometry) {
        boolean[] finite = {true};
        geometry.apply(new CoordinateSequenceFilter() {
            public void filter(CoordinateSequence sequence, int index) {
                finite[0] &= Double.isFinite(sequence.getX(index)) && Double.isFinite(sequence.getY(index));
            }
            public boolean isDone() { return !finite[0]; }
            public boolean isGeometryChanged() { return false; }
        });
        return finite[0];
    }

    private static boolean finiteEnvelope(Envelope envelope) {
        return envelope != null && !envelope.isNull() && Double.isFinite(envelope.getMinX())
                && Double.isFinite(envelope.getMaxX()) && Double.isFinite(envelope.getMinY())
                && Double.isFinite(envelope.getMaxY());
    }

    int retainedSourceCount() { return sources.size(); }
    int retainedMetadataCount() { return records.size(); }
    int retainedBufferCount() { return buffers.size(); }
    long retainedCoordinateCount() { return coordinates; }
    long preparedForbiddenCount() { return preparedForbidden; }

    private static final class Source {
        final FeatureKey key;
        final Geometry geometry;
        final Set<Key> keys = new LinkedHashSet<>();
        Source(FeatureKey key, Geometry geometry) { this.key = key; this.geometry = geometry; }
    }

    private static final class Entry {
        final Key key;
        final Source source;
        final Envelope bounds;
        Constraint constraint;
        long bufferCoordinates;
        Entry(Key key, Source source, Envelope bounds) { this.key = key; this.source = source; this.bounds = bounds; }
    }

    private static final class FeatureKey {
        final String id;
        final String type;
        FeatureKey(String id, String type) { this.id = id; this.type = type; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof FeatureKey)) return false;
            FeatureKey key = (FeatureKey) other;
            return id.equals(key.id) && type.equals(key.type);
        }
        @Override public int hashCode() { return Objects.hash(id, type); }
    }

    private static final class Key {
        final FeatureKey feature;
        final BigDecimal clearance;
        Key(FeatureKey feature, BigDecimal clearance) { this.feature = feature; this.clearance = clearance; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return feature.equals(key.feature) && Objects.equals(clearance, key.clearance);
        }
        @Override public int hashCode() { return Objects.hash(feature, clearance); }
    }
}
