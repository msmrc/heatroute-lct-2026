package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

/**
 * Предлагает общие магистрали в метрической СК: цепь камер и не более двух вводов на камеру.
 * Это топологии, а не допустимые трассы: препятствия, расходы и существующий корень проверяет вызывающий код.
 */
public final class SharedSpineCandidateBuilder {
    private static final double SPACING_M = 2.0;
    private static final double EPSILON_M = 1e-6;
    private static final int CANDIDATES_PER_AXIS = 3;

    /** Возвращает до шести вариантов, ранжированных по сумме евклидовых длин; пустой ввод даёт пустой список. */
    public List<SpineCandidate> build(List<Terminal> terminals, Coordinate root,
            List<Geometry> nearbyBuildingFootprints) {
        Coordinate origin = checkedCoordinate(root);
        List<Terminal> inputs = new ArrayList<>(List.copyOf(terminals));
        Set<String> ids = new HashSet<>();
        for (Terminal terminal : inputs) {
            if (!ids.add(terminal.id)) throw new IllegalArgumentException("Duplicate terminal id: " + terminal.id);
        }
        List<Geometry> footprints = List.copyOf(nearbyBuildingFootprints);
        if (inputs.isEmpty()) return List.of();
        inputs.sort(Comparator.comparingDouble((Terminal t) -> t.coordinate.x)
                .thenComparingDouble(t -> t.coordinate.y).thenComparing(t -> t.id));
        double angle = dominantAngle(inputs, origin, footprints);
        List<SpineCandidate> result = new ArrayList<>();
        for (int direction = 0; direction < 2; direction++) {
            double theta = angle + direction * Math.PI / 2;
            double ux = Math.cos(theta), uy = Math.sin(theta);
            double orientation = 0;
            for (Terminal terminal : inputs) {
                orientation += (terminal.coordinate.x - origin.x) * ux + (terminal.coordinate.y - origin.y) * uy;
            }
            if (Math.abs(orientation) < EPSILON_M) {
                Terminal farthest = inputs.stream().max(Comparator.comparingDouble(t -> t.coordinate.distance(origin))).orElseThrow();
                orientation = (farthest.coordinate.x - origin.x) * ux + (farthest.coordinate.y - origin.y) * uy;
            }
            if (orientation < -EPSILON_M) { ux = -ux; uy = -uy; }
            List<Projection> points = new ArrayList<>();
            for (Terminal terminal : inputs) points.add(new Projection(terminal, origin, ux, uy));
            points.sort(Comparator.comparingDouble((Projection p) -> orderKey(p.along))
                    .thenComparingDouble(p -> orderKey(p.across)).thenComparingDouble(p -> p.along)
                    .thenComparingDouble(p -> p.across).thenComparing(p -> p.terminal.id));
            List<SpineCandidate> alternatives = new ArrayList<>();
            for (double offset : offsets(points)) alternatives.add(candidate(points, origin, ux, uy, offset));
            alternatives.sort(Comparator.comparingDouble(c -> orderKey(c.estimatedLengthM)));
            result.addAll(alternatives.subList(0, Math.min(CANDIDATES_PER_AXIS, alternatives.size())));
        }
        result.sort(Comparator.comparingDouble(c -> orderKey(c.estimatedLengthM)));
        return List.copyOf(result);
    }

    private double dominantAngle(List<Terminal> terminals, Coordinate root, List<Geometry> footprints) {
        double facadeX = 0, facadeY = 0, perimeter = 0;
        for (Geometry footprint : footprints) {
            if (footprint.isEmpty() || footprint.getDimension() != 2) continue;
            Coordinate[] rectangle = MinimumDiameter.getMinimumRectangle(footprint).getCoordinates();
            for (int i = 1; i < rectangle.length; i++) {
                double length = rectangle[i - 1].distance(rectangle[i]);
                double theta = Math.atan2(rectangle[i].y - rectangle[i - 1].y, rectangle[i].x - rectangle[i - 1].x);
                facadeX += length * Math.cos(4 * theta);
                facadeY += length * Math.sin(4 * theta);
                perimeter += length;
            }
        }
        // Четвёртая гармоника объединяет параллельные и перпендикулярные фасады без привязки к северу.
        if (Math.hypot(facadeX, facadeY) > perimeter * 1e-8) return Math.atan2(facadeY, facadeX) / 4;
        double meanX = 0, meanY = 0;
        for (Terminal terminal : terminals) {
            meanX += (terminal.coordinate.x - root.x) / terminals.size();
            meanY += (terminal.coordinate.y - root.y) / terminals.size();
        }
        double xx = 0, yy = 0, xy = 0;
        for (Terminal terminal : terminals) {
            double x = terminal.coordinate.x - root.x - meanX, y = terminal.coordinate.y - root.y - meanY;
            xx += x * x; yy += y * y; xy += x * y;
        }
        if (Math.hypot(xx - yy, 2 * xy) > (xx + yy) * 1e-8) return Math.atan2(2 * xy, xx - yy) / 2;
        double longest = 0, angle = 0;
        for (int i = 0; i < terminals.size(); i++) {
            Coordinate a = terminals.get(i).coordinate;
            for (int j = i + 1; j < terminals.size(); j++) {
                Coordinate b = terminals.get(j).coordinate;
                if (a.distance(b) > longest + EPSILON_M) {
                    longest = a.distance(b); angle = Math.atan2(b.y - a.y, b.x - a.x);
                }
            }
        }
        if (longest < EPSILON_M) {
            Coordinate point = terminals.get(0).coordinate;
            angle = Math.atan2(point.y - root.y, point.x - root.x);
        }
        return angle;
    }

    private List<Double> offsets(List<Projection> points) {
        List<Double> values = new ArrayList<>();
        for (Projection point : points) values.add(point.across);
        values.sort(Double::compare);
        int n = values.size();
        double median = (values.get((n - 1) / 2) + values.get(n / 2)) / 2;
        double shift = Math.max(SPACING_M, (values.get(n - 1) - values.get(0)) / 4);
        List<Double> offsets = new ArrayList<>(List.of(median, median - shift, median + shift));
        int largestGap = 0;
        for (int i = 1; i < n; i++) {
            if (largestGap == 0 || values.get(i) - values.get(i - 1)
                    > values.get(largestGap) - values.get(largestGap - 1) + EPSILON_M) largestGap = i;
        }
        if (largestGap > 0) {
            double midpoint = (values.get(largestGap - 1) + values.get(largestGap)) / 2;
            if (offsets.stream().allMatch(offset -> Math.abs(offset - midpoint) >= SPACING_M)) offsets.add(midpoint);
        }
        return offsets;
    }

    private SpineCandidate candidate(List<Projection> points, Coordinate root, double ux, double uy, double offset) {
        List<Junction> junctions = new ArrayList<>();
        List<Double> positions = new ArrayList<>();
        double branchLength = 0;
        for (Projection[] pair : pairs(points, offset)) {
            Projection first = pair[0], last = pair[1];
            double along = (first.along + last.along) / 2;
            if (!positions.isEmpty()) along = Math.max(along, positions.get(positions.size() - 1) + SPACING_M);
            Coordinate coordinate = at(root, ux, uy, along, offset);
            while (coincides(coordinate, root, points)) {
                if (along + SPACING_M == along) throw new IllegalArgumentException("Metric coordinate precision is insufficient");
                along += SPACING_M;
                coordinate = at(root, ux, uy, along, offset);
            }
            positions.add(along);
            junctions.add(new Junction(coordinate, first == last ? List.of(first.terminal.id)
                    : List.of(first.terminal.id, last.terminal.id)));
            branchLength += coordinate.distance(first.terminal.coordinate);
            if (last != first) branchLength += coordinate.distance(last.terminal.coordinate);
        }
        int rootIndex = 0;
        double rootCost = Double.POSITIVE_INFINITY;
        for (int i = 0; i < junctions.size(); i++) {
            Junction junction = junctions.get(i);
            int degree = junction.terminalIds.size() + (i > 0 ? 1 : 0) + (i + 1 < junctions.size() ? 1 : 0);
            if (degree <= 3 && junction.coordinate.distance(root) < rootCost) {
                rootIndex = i; rootCost = junction.coordinate.distance(root);
            }
        }
        // Для корня внутри магистрали нужна отдельная камера: два ввода + две соседние камеры уже дают степень 4.
        int insertIndex = -1;
        double insertPosition = 0;
        for (int i = 1; i < positions.size(); i++) {
            double low = positions.get(i - 1) + SPACING_M, high = positions.get(i) - SPACING_M;
            if (low > high) continue;
            for (double along : new double[] {Math.max(low, Math.min(high, 0)), low, high}) {
                Coordinate coordinate = at(root, ux, uy, along, offset);
                double cost = coordinate.distance(root);
                if (cost < rootCost - EPSILON_M && !coincides(coordinate, root, points)) {
                    rootCost = cost; insertIndex = i; insertPosition = along;
                }
            }
        }
        if (insertIndex >= 0) {
            junctions.add(insertIndex, new Junction(at(root, ux, uy, insertPosition, offset), List.of()));
            rootIndex = insertIndex;
        }
        double length = rootCost + branchLength;
        for (int i = 0; i < junctions.size(); i++) {
            Junction junction = junctions.get(i);
            if (i > 0) length += junction.coordinate.distance(junctions.get(i - 1).coordinate);
        }
        return new SpineCandidate(junctions, rootIndex, length);
    }

    private List<Projection[]> pairs(List<Projection> points, double offset) {
        List<Projection> remaining = new ArrayList<>(points);
        List<Projection[]> pairs = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Projection first = remaining.remove(0), last = first;
            if (!remaining.isEmpty()) {
                int partner = 0;
                double stationWidth = Math.max(SPACING_M, 2 * (remaining.get(0).along - first.along));
                // Противоположные вводы уменьшают острые веера, но не оправдывают длинный скачок вдоль магистрали.
                for (int i = 0; i < remaining.size(); i++) {
                    Projection next = remaining.get(i);
                    if (next.along - first.along > stationWidth + EPSILON_M) break;
                    if ((first.across < offset - EPSILON_M && next.across > offset + EPSILON_M)
                            || (first.across > offset + EPSILON_M && next.across < offset - EPSILON_M)) {
                        partner = i; break;
                    }
                }
                last = remaining.remove(partner);
            }
            pairs.add(new Projection[] {first, last});
        }
        pairs.sort(Comparator.comparingDouble(pair -> orderKey((pair[0].along + pair[1].along) / 2)));
        return pairs;
    }

    private boolean coincides(Coordinate coordinate, Coordinate root, List<Projection> points) {
        if (coordinate.distance(root) < EPSILON_M) return true;
        for (Projection point : points) if (coordinate.distance(point.terminal.coordinate) < EPSILON_M) return true;
        return false;
    }

    private static Coordinate at(Coordinate root, double ux, double uy, double along, double across) {
        return new Coordinate(root.x + ux * along - uy * across, root.y + uy * along + ux * across);
    }

    // Микрометровый допуск устраняет зависимость сортировки равных проекций от округления поворота СК.
    private static double orderKey(double value) { return Math.rint(value / EPSILON_M) * EPSILON_M; }

    private static Coordinate checkedCoordinate(Coordinate coordinate) {
        if (coordinate == null || !Double.isFinite(coordinate.x) || !Double.isFinite(coordinate.y)) {
            throw new IllegalArgumentException("Expected finite metric coordinate");
        }
        return new Coordinate(coordinate.x, coordinate.y);
    }

    private static final class Projection {
        private final Terminal terminal;
        private final double along, across;
        private Projection(Terminal terminal, Coordinate root, double ux, double uy) {
            this.terminal = terminal;
            double x = terminal.coordinate.x - root.x, y = terminal.coordinate.y - root.y;
            along = x * ux + y * uy; across = -x * uy + y * ux;
        }
    }

    public static final class Terminal {
        private final String id;
        private final Coordinate coordinate;
        public Terminal(String id, Coordinate coordinate) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Terminal id is required");
            this.id = id; this.coordinate = checkedCoordinate(coordinate);
        }
        public String getId() { return id; }
        public Coordinate getCoordinate() { return new Coordinate(coordinate); }
        public String id() { return id; }
        public Coordinate coordinate() { return getCoordinate(); }
    }

    public static final class Junction {
        private final Coordinate coordinate;
        private final List<String> terminalIds;
        private Junction(Coordinate coordinate, List<String> terminalIds) {
            this.coordinate = checkedCoordinate(coordinate); this.terminalIds = List.copyOf(terminalIds);
        }
        public Coordinate getCoordinate() { return new Coordinate(coordinate); }
        public List<String> getTerminalIds() { return terminalIds; }
        public Coordinate coordinate() { return getCoordinate(); }
        public List<String> terminalIds() { return terminalIds; }
    }

    public static final class SpineCandidate {
        private final List<Junction> junctions;
        private final int rootJunctionIndex;
        private final double estimatedLengthM;
        private SpineCandidate(List<Junction> junctions, int rootJunctionIndex, double estimatedLengthM) {
            this.junctions = List.copyOf(junctions);
            this.rootJunctionIndex = rootJunctionIndex; this.estimatedLengthM = estimatedLengthM;
        }
        public List<Junction> getJunctions() { return junctions; }
        public int getRootJunctionIndex() { return rootJunctionIndex; }
        public double getEstimatedLengthM() { return estimatedLengthM; }
        public List<Junction> junctions() { return junctions; }
        public int rootJunctionIndex() { return rootJunctionIndex; }
        public double estimatedLengthM() { return estimatedLengthM; }
    }
}
