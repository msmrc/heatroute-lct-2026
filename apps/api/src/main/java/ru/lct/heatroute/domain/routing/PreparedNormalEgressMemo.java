package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryComponentFilter;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.NormalEgress;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Сохраняет полную target-независимую подготовку нормалей только для одного точного окна.
 * Снимок окна проверяет изменяемые Geometry и JsonNode перед каждым reuse, а LRU ограничивает
 * точки, ДУ и направление внутри этого окна.
 */
final class PreparedNormalEgressMemo {
    private static final int DEFAULT_MAX_WINDOW_FEATURES = 512;
    private static final long DEFAULT_MAX_WINDOW_COORDINATES = 100_000;
    private static final int DEFAULT_MAX_ENTRIES = 256;
    private static final long DEFAULT_MAX_RETURNED_COORDINATES = 8_192;

    private final OfficialRouteGeometryRules rules;
    private final int maxWindowFeatures;
    private final long maxWindowCoordinates;
    private final int maxEntries;
    private final long maxReturnedCoordinates;
    private final Map<Key, Entry> retained = new LinkedHashMap<>(16, 0.75f, true);
    private WindowSnapshot snapshot;
    private long retainedCoordinates;

    PreparedNormalEgressMemo(OfficialRouteGeometryRules rules) {
        this(rules, DEFAULT_MAX_WINDOW_FEATURES, DEFAULT_MAX_WINDOW_COORDINATES,
                DEFAULT_MAX_ENTRIES, DEFAULT_MAX_RETURNED_COORDINATES);
    }

    PreparedNormalEgressMemo(OfficialRouteGeometryRules rules, int maxWindowFeatures,
            long maxWindowCoordinates, int maxEntries, long maxReturnedCoordinates) {
        if (maxWindowFeatures < 0 || maxWindowCoordinates < 0
                || maxEntries < 0 || maxReturnedCoordinates < 0) {
            throw new IllegalArgumentException("Normal egress memo limits must be nonnegative");
        }
        this.rules = Objects.requireNonNull(rules, "rules");
        this.maxWindowFeatures = maxWindowFeatures;
        this.maxWindowCoordinates = maxWindowCoordinates;
        this.maxEntries = maxEntries;
        this.maxReturnedCoordinates = maxReturnedCoordinates;
    }

    List<NormalEgress> prepare(List<ImportedOfficialFeature> features, int diameter,
            Coordinate point, RouteTraversal traversal) {
        return prepare(features, diameter, point, traversal, false);
    }

    /** Mandatory validation использует исходную длину нормали, без navigation margin. */
    List<NormalEgress> prepareRawNearest(List<ImportedOfficialFeature> features, int diameter,
            Coordinate point, RouteTraversal traversal) {
        return prepare(features, diameter, point, traversal, true);
    }

    private List<NormalEgress> prepare(List<ImportedOfficialFeature> features, int diameter,
            Coordinate point, RouteTraversal traversal, boolean rawNearest) {
        Objects.requireNonNull(features, "features");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(traversal, "Route traversal is required");
        ensureActive();
        if (!ensureSnapshot(features)) {
            return compute(features, diameter, point, traversal, rawNearest);
        }
        boolean sharedStage = rules.hasStandardPreparationRules();
        Key key = new Key(point, diameter, traversal, !sharedStage && rawNearest);
        Entry cached = retained.get(key);
        if (sharedStage) {
            return prepareSharedStage(features, diameter, point, traversal, rawNearest, key, cached);
        }
        if (cached != null) {
            ensureActive();
            return new ArrayList<>(cached.egresses);
        }
        List<NormalEgress> prepared = compute(features, diameter, point, traversal, rawNearest);
        retain(key, prepared);
        return new ArrayList<>(prepared);
    }

    /** RAW и PADDED имеют общий дорогой этап, но никогда не подменяют результаты друг друга. */
    private List<NormalEgress> prepareSharedStage(List<ImportedOfficialFeature> features, int diameter,
            Coordinate point, RouteTraversal traversal, boolean rawNearest, Key key, Entry cached) {
        List<NormalEgress> raw = cached == null
                ? rules.prepareNearestLegalNormalEgresses(features, diameter, point, traversal)
                : cached.rawEgresses;
        if (cached == null) {
            // Даже если пара RAW+PADDED не влезет в бюджет, допустимая RAW-запись остаётся полезной.
            retain(key, null, raw);
        }
        if (rawNearest) {
            ensureActive();
            return new ArrayList<>(raw);
        }
        if (cached != null && cached.egresses != null) {
            ensureActive();
            return new ArrayList<>(cached.egresses);
        }
        List<NormalEgress> padded = rules.padPreparedNormalEgresses(features, diameter, traversal, raw);
        retain(key, padded, raw);
        ensureActive();
        return new ArrayList<>(padded);
    }

    private List<NormalEgress> compute(List<ImportedOfficialFeature> features, int diameter,
            Coordinate point, RouteTraversal traversal, boolean rawNearest) {
        return rawNearest ? rules.prepareNearestLegalNormalEgresses(features, diameter, point, traversal)
                : rules.prepareNormalEgresses(features, diameter, point, traversal);
    }

    private boolean ensureSnapshot(List<ImportedOfficialFeature> features) {
        if (snapshot != null && snapshot.matches(features)) return true;
        clear();
        snapshot = WindowSnapshot.capture(features, maxWindowFeatures, maxWindowCoordinates);
        return snapshot != null;
    }

    private void retain(Key key, List<NormalEgress> egresses) {
        retain(key, egresses, null);
    }

    private void retain(Key key, List<NormalEgress> egresses, List<NormalEgress> rawEgresses) {
        long coordinates = (egresses == null ? 0 : 2L * egresses.size())
                + (rawEgresses == null ? 0 : 2L * rawEgresses.size());
        if (maxEntries == 0 || coordinates > maxReturnedCoordinates) return;
        Entry previous = retained.remove(key);
        if (previous != null) retainedCoordinates -= previous.coordinates;
        Iterator<Map.Entry<Key, Entry>> oldest = retained.entrySet().iterator();
        while (oldest.hasNext()
                && (retained.size() >= maxEntries || retainedCoordinates > maxReturnedCoordinates - coordinates)) {
            retainedCoordinates -= oldest.next().getValue().coordinates;
            oldest.remove();
        }
        retained.put(key, new Entry(egresses == null ? null : List.copyOf(egresses),
                rawEgresses == null ? null : List.copyOf(rawEgresses), coordinates));
        retainedCoordinates += coordinates;
    }

    private void clear() {
        retained.clear();
        retainedCoordinates = 0;
        snapshot = null;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Normal egress preparation cancelled");
        }
    }

    private static final class WindowSnapshot {
        private final FeatureSnapshot[] features;

        private WindowSnapshot(FeatureSnapshot[] features) {
            this.features = features;
        }

        private static WindowSnapshot capture(List<ImportedOfficialFeature> features,
                int maxFeatures, long maxCoordinates) {
            if (features.size() > maxFeatures) return null;
            long coordinates = 0;
            for (ImportedOfficialFeature feature : features) {
                if (feature == null) return null;
                Geometry geometry = feature.getMetricGeometry();
                long count = geometry == null ? 0 : geometry.getNumPoints();
                if (count > maxCoordinates - coordinates) return null;
                coordinates += count;
            }
            FeatureSnapshot[] snapshots = new FeatureSnapshot[features.size()];
            for (int index = 0; index < features.size(); index++) {
                snapshots[index] = new FeatureSnapshot(features.get(index));
            }
            return new WindowSnapshot(snapshots);
        }

        private boolean matches(List<ImportedOfficialFeature> current) {
            if (features.length != current.size()) return false;
            for (int index = 0; index < features.length; index++) {
                if (!features[index].matches(current.get(index))) return false;
            }
            return true;
        }
    }

    private static final class FeatureSnapshot {
        private final String id;
        private final String type;
        private final JsonNode attributes;
        private final Geometry geometry;

        private FeatureSnapshot(ImportedOfficialFeature feature) {
            id = feature.getFeatureId();
            type = feature.getObjectType();
            JsonNode sourceAttributes = feature.getAttributes();
            attributes = sourceAttributes == null ? null : sourceAttributes.deepCopy();
            Geometry sourceGeometry = feature.getMetricGeometry();
            geometry = sourceGeometry == null ? null : sourceGeometry.copy();
            if (geometry != null) {
                geometry.apply((GeometryComponentFilter) component -> component.setUserData(null));
            }
        }

        private boolean matches(ImportedOfficialFeature current) {
            if (current == null || !Objects.equals(id, current.getFeatureId())
                    || !Objects.equals(type, current.getObjectType())
                    || !Objects.equals(attributes, current.getAttributes())) {
                return false;
            }
            Geometry currentGeometry = current.getMetricGeometry();
            return geometry == null ? currentGeometry == null
                    : currentGeometry != null && PreparedRoutingConstraints.sameGeometry(geometry, currentGeometry);
        }
    }

    private static final class Key {
        private final long x;
        private final long y;
        private final long z;
        private final int diameter;
        private final RouteTraversal traversal;
        private final boolean rawNearest;

        private Key(Coordinate point, int diameter, RouteTraversal traversal, boolean rawNearest) {
            x = Double.doubleToRawLongBits(point.x);
            y = Double.doubleToRawLongBits(point.y);
            z = Double.doubleToRawLongBits(point.getZ());
            this.diameter = diameter;
            this.traversal = traversal;
            this.rawNearest = rawNearest;
        }

        @Override
        public boolean equals(Object candidate) {
            if (this == candidate) return true;
            if (!(candidate instanceof Key)) return false;
            Key other = (Key) candidate;
            return x == other.x && y == other.y && z == other.z
                    && diameter == other.diameter && traversal == other.traversal && rawNearest == other.rawNearest;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z, diameter, traversal, rawNearest);
        }
    }

    private static final class Entry {
        private final List<NormalEgress> egresses;
        private final List<NormalEgress> rawEgresses;
        private final long coordinates;

        private Entry(List<NormalEgress> egresses, List<NormalEgress> rawEgresses, long coordinates) {
            this.egresses = egresses;
            this.rawEgresses = rawEgresses;
            this.coordinates = coordinates;
        }
    }
}
