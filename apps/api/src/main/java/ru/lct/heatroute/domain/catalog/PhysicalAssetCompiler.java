package ru.lct.heatroute.domain.catalog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Formatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;

/**
 * Разбивает доказанно одинаковые коллинеарные интервалы на общие атомарные активы.
 * Точечные касания и обычные XY-пересечения намеренно не создают общий актив или камеру.
 */
public final class PhysicalAssetCompiler {
    private static final double INTEGER_TOLERANCE = 1.0e-6;
    public static final String JUNCTION_CONTEXT_PREFIX = "junction-network:";

    public Result compile(Collection<CandidatePath> suppliedPaths) {
        if (suppliedPaths == null || suppliedPaths.isEmpty()) {
            throw new IllegalArgumentException("At least one candidate path is required");
        }
        List<CandidatePath> paths = new ArrayList<>(suppliedPaths);
        paths.sort(Comparator.comparing(CandidatePath::getId));
        Set<String> pathIds = new LinkedHashSet<>();
        for (CandidatePath path : paths) {
            if (path == null || !pathIds.add(path.id)) {
                throw new IllegalArgumentException("Candidate paths must be non-null with unique IDs");
            }
        }

        List<Segment> segments = segments(paths);
        addOverlapBreakpoints(segments);
        Map<String, AssetAccumulator> assets = new LinkedHashMap<>();
        Map<String, List<AssetTraversal>> traversalsByPath = new LinkedHashMap<>();
        for (Segment segment : segments) {
            List<CatalogMetricPoint> points = segment.orderedBreakpoints();
            List<AssetTraversal> pathTraversals = traversalsByPath.computeIfAbsent(
                    segment.path.id, ignored -> new ArrayList<>());
            for (int index = 1; index < points.size(); index++) {
                CatalogMetricPoint from = points.get(index - 1);
                CatalogMetricPoint to = points.get(index);
                if (from.equals(to)) continue;
                CatalogMetricPoint first = CatalogPhysicalAsset.compare(from, to) < 0 ? from : to;
                CatalogMetricPoint second = first == from ? to : from;
                String identity = identity(segment.path.physicalContext,
                        segment.path.constructionMode, first, second);
                String assetId = "asset:" + sha256(identity);
                AssetAccumulator accumulator = assets.computeIfAbsent(identity,
                        ignored -> new AssetAccumulator(assetId, segment.path.physicalContext,
                                segment.path.constructionMode, first, second));
                accumulator.sourcePathIds.add(segment.path.id);
                pathTraversals.add(new AssetTraversal(assetId, first.equals(from)));
            }
        }

        List<CatalogPhysicalAsset> compiledAssets = new ArrayList<>(assets.size());
        for (AssetAccumulator accumulator : assets.values()) compiledAssets.add(accumulator.freeze());
        compiledAssets.sort(Comparator.comparing(CatalogPhysicalAsset::getId));
        Map<String, CompiledPath> compiledPaths = new LinkedHashMap<>();
        for (CandidatePath path : paths) {
            List<AssetTraversal> traversals = traversalsByPath.getOrDefault(path.id, List.of());
            Set<String> uniqueAssets = new LinkedHashSet<>();
            for (AssetTraversal traversal : traversals) {
                if (!uniqueAssets.add(traversal.assetId)) {
                    throw new IllegalArgumentException("Candidate path traverses a physical asset more than once: "
                            + path.id);
                }
            }
            compiledPaths.put(path.id, new CompiledPath(path.id, path.accountingChainId, traversals));
        }
        return new Result(compiledAssets, compiledPaths);
    }

    private static List<Segment> segments(List<CandidatePath> paths) {
        List<Segment> result = new ArrayList<>();
        int globalIndex = 0;
        for (CandidatePath path : paths) {
            for (int index = 1; index < path.coordinates.size(); index++) {
                result.add(new Segment(globalIndex++, path,
                        path.coordinates.get(index - 1), path.coordinates.get(index)));
            }
        }
        return result;
    }

    private static void addOverlapBreakpoints(List<Segment> segments) {
        Map<String, STRtree> indexes = new LinkedHashMap<>();
        for (Segment segment : segments) {
            indexes.computeIfAbsent(indexKey(segment.path), ignored -> new STRtree())
                    .insert(segment.envelope(), segment);
        }
        RobustLineIntersector intersector = new RobustLineIntersector();
        for (Segment left : segments) {
            STRtree index = indexes.get(indexKey(left.path));
            for (Object candidate : index.query(left.envelope())) {
                Segment right = (Segment) candidate;
                if (right.globalIndex <= left.globalIndex) continue;
                intersector.computeIntersection(left.startCoordinate(), left.endCoordinate(),
                        right.startCoordinate(), right.endCoordinate());
                if (intersector.getIntersectionNum() == 1
                        && isJunctionContext(left.path.physicalContext)) {
                    CatalogMetricPoint point = precisePoint(intersector.getIntersection(0));
                    left.breakpoints.add(point);
                    right.breakpoints.add(point);
                    continue;
                }
                if (intersector.getIntersectionNum() != 2) continue;
                CatalogMetricPoint first = precisePoint(intersector.getIntersection(0));
                CatalogMetricPoint second = precisePoint(intersector.getIntersection(1));
                if (first.equals(second)) continue;
                left.breakpoints.add(first);
                left.breakpoints.add(second);
                right.breakpoints.add(first);
                right.breakpoints.add(second);
            }
        }
    }

    private static String indexKey(CandidatePath path) {
        return path.physicalContext + "\u0000" + path.constructionMode;
    }

    public static boolean isJunctionContext(String physicalContext) {
        return physicalContext != null && physicalContext.startsWith(JUNCTION_CONTEXT_PREFIX);
    }

    private static CatalogMetricPoint precisePoint(Coordinate coordinate) {
        if (!Double.isFinite(coordinate.x) || !Double.isFinite(coordinate.y)) {
            throw new IllegalArgumentException("Physical intersection must have finite coordinates");
        }
        return CatalogMetricPoint.fromMillimeters(coordinate.x, coordinate.y);
    }

    private static String identity(String context, CatalogPhysicalAsset.ConstructionMode constructionMode,
            CatalogMetricPoint first, CatalogMetricPoint second) {
        return context + "\u0000" + constructionMode + "\u0000"
                + first.getXMicrometers() + "\u0000" + first.getYMicrometers()
                + "\u0000" + second.getXMicrometers() + "\u0000" + second.getYMicrometers();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
            try (Formatter formatter = new Formatter(java.util.Locale.ROOT)) {
                for (byte item : digest.digest()) formatter.format("%02x", item);
                return formatter.toString();
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static final class CandidatePath {
        private final String id;
        private final String physicalContext;
        private final CatalogPhysicalAsset.ConstructionMode constructionMode;
        private final String accountingChainId;
        private final List<CatalogMetricPoint> coordinates;

        public CandidatePath(String id, String physicalContext, String accountingChainId,
                Collection<CatalogMetricPoint> coordinates) {
            this(id, physicalContext, CatalogPhysicalAsset.ConstructionMode.NEW_CONSTRUCTION,
                    accountingChainId, coordinates);
        }

        public CandidatePath(String id, String physicalContext,
                CatalogPhysicalAsset.ConstructionMode constructionMode, String accountingChainId,
                Collection<CatalogMetricPoint> coordinates) {
            this.id = required(id, "candidate path ID");
            this.physicalContext = required(physicalContext, "physical context");
            this.constructionMode = Objects.requireNonNull(constructionMode, "constructionMode");
            this.accountingChainId = required(accountingChainId, "accounting chain ID");
            if (coordinates == null || coordinates.size() < 2 || coordinates.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Candidate path requires at least two non-null points");
            }
            this.coordinates = List.copyOf(coordinates);
            for (int index = 1; index < this.coordinates.size(); index++) {
                if (this.coordinates.get(index - 1).equals(this.coordinates.get(index))) {
                    throw new IllegalArgumentException("Consecutive candidate path points must differ");
                }
            }
        }

        public String getId() { return id; }
        public String getPhysicalContext() { return physicalContext; }
        public CatalogPhysicalAsset.ConstructionMode getConstructionMode() { return constructionMode; }
        public String getAccountingChainId() { return accountingChainId; }
        public List<CatalogMetricPoint> getCoordinates() { return coordinates; }
    }

    public static final class Result {
        private final List<CatalogPhysicalAsset> physicalAssets;
        private final Map<String, CompiledPath> pathsById;

        private Result(List<CatalogPhysicalAsset> physicalAssets, Map<String, CompiledPath> pathsById) {
            this.physicalAssets = List.copyOf(physicalAssets);
            this.pathsById = Collections.unmodifiableMap(new LinkedHashMap<>(pathsById));
        }

        public List<CatalogPhysicalAsset> getPhysicalAssets() { return physicalAssets; }
        public Collection<CompiledPath> getPaths() { return pathsById.values(); }
        public CompiledPath path(String id) { return pathsById.get(id); }
    }

    public static final class CompiledPath {
        private final String id;
        private final String accountingChainId;
        private final List<AssetTraversal> traversals;

        private CompiledPath(String id, String accountingChainId, List<AssetTraversal> traversals) {
            this.id = id;
            this.accountingChainId = accountingChainId;
            this.traversals = List.copyOf(traversals);
        }

        public String getId() { return id; }
        public String getAccountingChainId() { return accountingChainId; }
        public List<AssetTraversal> getTraversals() { return traversals; }
        public List<String> getPhysicalAssetIds() {
            List<String> result = new ArrayList<>(traversals.size());
            for (AssetTraversal traversal : traversals) result.add(traversal.assetId);
            return List.copyOf(result);
        }
    }

    public static final class AssetTraversal {
        private final String assetId;
        private final boolean canonicalDirection;

        private AssetTraversal(String assetId, boolean canonicalDirection) {
            this.assetId = assetId;
            this.canonicalDirection = canonicalDirection;
        }

        public String getAssetId() { return assetId; }
        public boolean isCanonicalDirection() { return canonicalDirection; }
    }

    private static final class Segment {
        private final int globalIndex;
        private final CandidatePath path;
        private final CatalogMetricPoint start;
        private final CatalogMetricPoint end;
        private final Set<CatalogMetricPoint> breakpoints = new LinkedHashSet<>();

        private Segment(int globalIndex, CandidatePath path,
                CatalogMetricPoint start, CatalogMetricPoint end) {
            this.globalIndex = globalIndex;
            this.path = path;
            this.start = start;
            this.end = end;
            this.breakpoints.add(start);
            this.breakpoints.add(end);
        }

        private Envelope envelope() {
            return new Envelope(start.getXMillimeters(), end.getXMillimeters(),
                    start.getYMillimeters(), end.getYMillimeters());
        }

        private Coordinate startCoordinate() {
            return new Coordinate(start.getXMillimeters(), start.getYMillimeters());
        }
        private Coordinate endCoordinate() {
            return new Coordinate(end.getXMillimeters(), end.getYMillimeters());
        }

        private List<CatalogMetricPoint> orderedBreakpoints() {
            List<CatalogMetricPoint> result = new ArrayList<>(breakpoints);
            double deltaX = end.getXMillimeters() - start.getXMillimeters();
            double deltaY = end.getYMillimeters() - start.getYMillimeters();
            result.sort(Comparator.comparingDouble(point ->
                    (point.getXMillimeters() - start.getXMillimeters()) * deltaX
                            + (point.getYMillimeters() - start.getYMillimeters()) * deltaY));
            return result;
        }
    }

    private static final class AssetAccumulator {
        private final String id;
        private final String physicalContext;
        private final CatalogPhysicalAsset.ConstructionMode constructionMode;
        private final CatalogMetricPoint first;
        private final CatalogMetricPoint second;
        private final Set<String> sourcePathIds = new LinkedHashSet<>();

        private AssetAccumulator(String id, String physicalContext,
                CatalogPhysicalAsset.ConstructionMode constructionMode,
                CatalogMetricPoint first, CatalogMetricPoint second) {
            this.id = id;
            this.physicalContext = physicalContext;
            this.constructionMode = constructionMode;
            this.first = first;
            this.second = second;
        }

        private CatalogPhysicalAsset freeze() {
            return new CatalogPhysicalAsset(id, physicalContext, constructionMode,
                    first, second, sourcePathIds);
        }
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }
}
