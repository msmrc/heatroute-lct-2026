package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.engineering.PipeCatalogEntry;

/** Ignored one-edge prototype. Physical intervals and stored-millimetre slope constraints. */
final class CriticalDepthSolver {
    private static final int ORDINARY = 3000;
    private final OfficialPipeCatalog pipes;

    CriticalDepthSolver(OfficialPipeCatalog pipes) { this.pipes = pipes; }

    DepthProfileResult optimize(BigDecimal length, int diameter, List<DepthCrossing> crossings,
            BigDecimal minimum, BigDecimal maximum) {
        return optimize(length, diameter, crossings, minimum, maximum, List.of());
    }

    DepthProfileResult optimize(BigDecimal length, int diameter, List<DepthCrossing> crossings,
            BigDecimal minimum, BigDecimal maximum, List<DepthFloorInterval> floors) {
        PreparedEdge prepared = prepare(length, diameter, crossings, minimum, maximum, floors);
        if (!prepared.issues.isEmpty()) return normal(length, prepared.issues);
        List<Component> components = prepared.components;
        if (components.isEmpty()) return normal(length, List.of());
        List<DepthIntervalClosure.Domain> domains = new ArrayList<>();
        int[] differences = new int[Math.max(0, components.size() - 1)];
        for (int i = 0; i < components.size(); i++) {
            domains.add(components.get(i).domain);
            if (i > 0) differences[i - 1] = prepared.metric.allowedRise(components.get(i - 1).end, components.get(i).start);
        }
        Optional<int[]> least = DepthIntervalClosure.least(domains, differences);
        if (least.isEmpty()) return failure(prepared);
        int[] heights = DepthIntervalClosure.preferOrdinary(domains, differences, least.get(), ORDINARY).orElseThrow();
        int startHeight = nearestOrdinary(heights[0], prepared.metric.allowedRise(0, components.get(0).start));
        int endHeight = nearestOrdinary(heights[heights.length - 1],
                prepared.metric.allowedRise(components.get(components.size() - 1).end, prepared.lengthMm));
        return render(prepared, heights, startHeight, endHeight);
    }

    PreparedEdge prepare(BigDecimal length, int diameter, List<DepthCrossing> crossings,
            BigDecimal minimum, BigDecimal maximum, List<DepthFloorInterval> floors) {
        return prepare(length, diameter, crossings, minimum, maximum, floors,
                DepthStationMetric.identity(mm(length, RoundingMode.HALF_UP)));
    }

    PreparedEdge prepare(BigDecimal length, int diameter, List<DepthCrossing> crossings,
            BigDecimal minimum, BigDecimal maximum, List<DepthFloorInterval> floors, DepthStationMetric metric) {
        int lengthMm = mm(length, RoundingMode.HALF_UP);
        int min = mm(minimum, RoundingMode.CEILING), max = mm(maximum, RoundingMode.FLOOR);
        if (lengthMm <= 0 || min < 700 || min > ORDINARY || max < ORDINARY) {
            throw new IllegalArgumentException("unsupported edge length or user depth bounds");
        }
        PipeCatalogEntry pipe = pipes.byDiameter(diameter).orElseThrow();
        List<DepthCrossing> ordered = new ArrayList<>(crossings);
        ordered.sort(Comparator.comparing(DepthCrossing::getStationM).thenComparing(DepthCrossing::getId));
        List<Component> components = new ArrayList<>();
        for (DepthCrossing crossing : ordered) {
            int station = mm(crossing.getStationM(), RoundingMode.HALF_UP);
            int start = mm(crossing.getPlateauStartM(), RoundingMode.FLOOR), end = mm(crossing.getPlateauEndM(), RoundingMode.CEILING);
            if (start < 0 || end > lengthMm) return new PreparedEdge(length, lengthMm, ordered,
                    components, pipe, metric, List.of(new DepthProfileIssue("NO_VERTICAL_PASSAGE", crossing.getId(),
                            "Four-metre crossing plateau lies outside the edge")));
            BigDecimal above = crossing.getExistingTopDepthM().subtract(pipe.getEnvelopeHeightM())
                    .subtract(crossing.getMinimumVerticalClearanceM());
            BigDecimal below = crossing.getExistingTopDepthM().add(crossing.getExistingHeightM())
                    .add(crossing.getMinimumVerticalClearanceM());
            DepthIntervalClosure.Domain domain = new DepthIntervalClosure.Domain(List.of(
                    new int[]{min, Math.min(max, mm(above, RoundingMode.FLOOR))},
                    new int[]{Math.max(min, mm(below, RoundingMode.CEILING)), max}));
            Component last = components.isEmpty() ? null : components.get(components.size() - 1);
            if (last != null && start <= last.end) {
                last.end = Math.max(last.end, end);
                last.domain = last.domain.intersection(domain);
                last.crossings.add(crossing);
            } else components.add(new Component(start, end, domain, crossing));
        }
        for (DepthFloorInterval floor : floors) {
            int start = Math.max(0, mm(floor.getStartM(), RoundingMode.FLOOR));
            int end = Math.min(lengthMm, mm(floor.getEndM(), RoundingMode.CEILING));
            if (floor.getEndM().compareTo(length) > 0) throw new IllegalArgumentException("floor outside edge");
            int depth = mm(floor.getMinimumDepthM(), RoundingMode.CEILING);
            for (Component component : components) {
                if (component.start <= end && component.end >= start) {
                    component.domain = component.domain.intersection(DepthIntervalClosure.Domain.range(depth, max));
                }
            }
        }
        for (DepthFloorInterval floor : floors) {
            for (int station : new int[]{Math.max(0, mm(floor.getStartM(), RoundingMode.FLOOR)),
                    Math.min(lengthMm, mm(floor.getEndM(), RoundingMode.CEILING))}) {
                boolean covered = components.stream().anyMatch(c -> c.start <= station && c.end >= station);
                if (covered) continue;
                int depth = min;
                for (DepthFloorInterval active : floors) {
                    if (mm(active.getStartM(), RoundingMode.FLOOR) <= station
                            && mm(active.getEndM(), RoundingMode.CEILING) >= station) {
                        depth = Math.max(depth, mm(active.getMinimumDepthM(), RoundingMode.CEILING));
                    }
                }
                components.add(new Component(station, station, DepthIntervalClosure.Domain.range(depth, max), null));
            }
        }
        components.sort(Comparator.comparingInt(component -> component.start));
        return new PreparedEdge(length, lengthMm, ordered, components, pipe, metric, List.of());
    }

    DepthProfileResult render(PreparedEdge prepared, int[] heights, int startHeight, int endHeight) {
        List<Component> components = prepared.components;
        PipeCatalogEntry pipe = prepared.pipe;
        BigDecimal length = prepared.length;
        int lengthMm = prepared.lengthMm;
        TreeMap<Integer, Integer> vertices = new TreeMap<>();
        if (components.isEmpty()) {
            bridge(prepared.metric, vertices, 0, startHeight, lengthMm, endHeight);
            List<DepthProfilePoint> points = compact(vertices);
            DepthProfileResult profile = new DepthProfileResult(true, points, List.of(), List.of(), length3d(points), length);
            return new DepthProfileResult(true, points, List.of(), List.of(), length3d(points),
                    horizontalWeightedMeters(profile, List.of()));
        }
        int firstHeight = heights[0], lastHeight = heights[heights.length - 1];
        put(vertices, 0, startHeight);
        bridge(prepared.metric, vertices, 0, startHeight, components.get(0).start, firstHeight);
        for (int i = 0; i < components.size(); i++) {
            Component component = components.get(i);
            put(vertices, component.start, heights[i]);
            put(vertices, component.end, heights[i]);
            if (i + 1 < components.size()) {
                bridge(prepared.metric, vertices, component.end, heights[i], components.get(i + 1).start, heights[i + 1]);
            }
        }
        bridge(prepared.metric, vertices, components.get(components.size() - 1).end, lastHeight, lengthMm, endHeight);
        List<DepthProfilePoint> points = compact(vertices);
        List<DepthCrossingDecision> decisions = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            Component component = components.get(i);
            BigDecimal depth = metres(heights[i]);
            for (DepthCrossing crossing : component.crossings) {
                BigDecimal above = crossing.getExistingTopDepthM().subtract(depth).subtract(pipe.getEnvelopeHeightM());
                boolean passageAbove = above.compareTo(crossing.getMinimumVerticalClearanceM()) >= 0;
                BigDecimal clearance = passageAbove ? above
                        : depth.subtract(crossing.getExistingTopDepthM()).subtract(crossing.getExistingHeightM());
                int station = mm(crossing.getStationM(), RoundingMode.HALF_UP);
                Integer before = vertices.lowerKey(component.start), after = vertices.higherKey(component.end);
                decisions.add(new DepthCrossingDecision(crossing.getId(), crossing.getType(),
                        passageAbove ? "above" : "below", depth,
                        metres(before == null ? component.start : before), crossing.getPlateauStartM(),
                        crossing.getPlateauEndM(), metres(after == null ? component.end : after),
                        clearance, crossing.getMinimumVerticalClearanceM()));
            }
        }
        DepthProfileResult provisional = new DepthProfileResult(true, points, decisions, List.of(), length, length);
        return new DepthProfileResult(true, points, decisions, List.of(), length3d(points),
                horizontalWeightedMeters(provisional, prepared.crossings));
    }

    /** Exact XY integral before DTO rounding, using max special multiplier at all real cuts. */
    static BigDecimal horizontalWeightedMeters(DepthProfileResult profile, List<DepthCrossing> crossings) {
        Map<String, DepthCrossing> byId = new TreeMap<>();
        crossings.forEach(c -> byId.put(c.getId(), c));
        TreeSet<BigDecimal> cuts = new TreeSet<>();
        profile.getPoints().forEach(p -> cuts.add(p.getStationM()));
        cuts.addAll(profile.costBreakpoints(profile.getPoints().get(0).getStationM(),
                profile.getPoints().get(profile.getPoints().size() - 1).getStationM()));
        profile.getCrossings().forEach(c -> { cuts.add(c.getPlateauStartM()); cuts.add(c.getPlateauEndM()); });
        List<BigDecimal> ordered = new ArrayList<>(cuts);
        BigDecimal result = BigDecimal.ZERO;
        for (int i = 1; i < ordered.size(); i++) {
            BigDecimal start = ordered.get(i - 1), end = ordered.get(i);
            BigDecimal special = profile.getCrossings().stream()
                    .filter(c -> start.compareTo(c.getPlateauStartM()) >= 0 && end.compareTo(c.getPlateauEndM()) <= 0)
                    .map(c -> byId.get(c.getCrossingId()).getSpecialCostMultiplier())
                    .max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
            BigDecimal average = multiplier(exactDepthAt(profile, start)).add(multiplier(exactDepthAt(profile, end)))
                    .divide(new BigDecimal("2"));
            result = result.add(end.subtract(start).multiply(average).multiply(special));
        }
        return result;
    }

    private static BigDecimal exactDepthAt(DepthProfileResult profile, BigDecimal station) {
        for (int i = 1; i < profile.getPoints().size(); i++) {
            DepthProfilePoint a = profile.getPoints().get(i - 1), b = profile.getPoints().get(i);
            if (station.compareTo(b.getStationM()) <= 0) return a.getDepthM().add(
                    b.getDepthM().subtract(a.getDepthM()).multiply(station.subtract(a.getStationM()))
                            .divide(b.getStationM().subtract(a.getStationM()), 15, RoundingMode.HALF_UP));
        }
        throw new IllegalArgumentException("station outside profile");
    }

    private static BigDecimal multiplier(BigDecimal depth) {
        return BigDecimal.ONE.add(depth.subtract(new BigDecimal("3")).max(BigDecimal.ZERO).multiply(new BigDecimal(".1")));
    }

    private void bridge(DepthStationMetric metric, TreeMap<Integer, Integer> vertices,
            int start, int left, int end, int right) {
        if (Math.abs(left - right) > metric.allowedRise(start, end)) throw new IllegalStateException("closure physical slope mismatch");
        put(vertices, start, left);
        if (left >= ORDINARY && right >= ORDINARY) {
            int low = ORDINARY, high = Math.min(left, right);
            while (low < high) {
                int mid = low + (high - low) / 2;
                if (fits(metric, start, end, left - mid, right - mid)) high = mid;
                else low = mid + 1;
            }
            put(vertices, metric.ascendingEnd(start, end, left - low), low);
            put(vertices, metric.descendingStart(start, end, right - low), low);
        } else if (left <= ORDINARY && right <= ORDINARY) {
            int low = Math.max(left, right), high = ORDINARY;
            while (low < high) {
                int mid = low + (high - low + 1) / 2;
                if (fits(metric, start, end, mid - left, mid - right)) low = mid;
                else high = mid - 1;
            }
            put(vertices, metric.ascendingEnd(start, end, low - left), low);
            put(vertices, metric.descendingStart(start, end, low - right), low);
        } else if (fits(metric, start, end, Math.abs(left - ORDINARY), Math.abs(right - ORDINARY))) {
            put(vertices, metric.ascendingEnd(start, end, Math.abs(left - ORDINARY)), ORDINARY);
            put(vertices, metric.descendingStart(start, end, Math.abs(right - ORDINARY)), ORDINARY);
        }
        // If the h=3 station cannot be represented in millimetres, the lawful linear bridge remains;
        // arithmetic/export split that segment at its exact coefficient threshold.
        put(vertices, end, right);
    }

    private boolean fits(DepthStationMetric metric, int start, int end, int leftRise, int rightRise) {
        return metric.ascendingEnd(start, end, leftRise) <= metric.descendingStart(start, end, rightRise);
    }

    private List<DepthProfilePoint> compact(TreeMap<Integer, Integer> vertices) {
        List<int[]> points = new ArrayList<>();
        vertices.forEach((station, depth) -> points.add(new int[]{station, depth}));
        List<DepthProfilePoint> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            int[] point = points.get(i);
            if (i > 0 && i + 1 < points.size()) {
                int[] previous = points.get(i - 1), next = points.get(i + 1);
                boolean crossesOrdinary = (long) (previous[1] - ORDINARY) * (next[1] - ORDINARY) < 0;
                boolean collinear = (long) (point[1] - previous[1]) * (next[0] - point[0])
                        == (long) (next[1] - point[1]) * (point[0] - previous[0]);
                if (collinear && !crossesOrdinary) continue;
            }
            result.add(new DepthProfilePoint(metres(point[0]), metres(point[1])));
        }
        return result;
    }

    private static void put(TreeMap<Integer, Integer> points, int station, int depth) {
        Integer previous = points.put(station, depth);
        if (previous != null && previous != depth) throw new IllegalStateException("depth jump at one station");
    }

    private static BigDecimal length3d(List<DepthProfilePoint> points) {
        double length = 0;
        for (int i = 1; i < points.size(); i++) {
            length += Math.hypot(points.get(i).getStationM().subtract(points.get(i - 1).getStationM()).doubleValue(),
                    points.get(i).getDepthM().subtract(points.get(i - 1).getDepthM()).doubleValue());
        }
        return BigDecimal.valueOf(length);
    }

    private static int nearestOrdinary(int height, int permittedChange) {
        return Math.max(height - permittedChange, Math.min(ORDINARY, height + permittedChange));
    }

    private DepthProfileResult failed(BigDecimal length, DepthCrossing crossing, String code) {
        return normal(length, List.of(new DepthProfileIssue(code, crossing.getId(), "No representable lawful interval profile")));
    }

    private DepthProfileResult normal(BigDecimal length, List<DepthProfileIssue> issues) {
        return new DepthProfileResult(issues.isEmpty(), List.of(new DepthProfilePoint(BigDecimal.ZERO, metres(ORDINARY)),
                new DepthProfilePoint(length, metres(ORDINARY))), List.of(), issues, length, length);
    }

    static int mm(BigDecimal metres, RoundingMode rounding) {
        return metres.movePointRight(3).setScale(0, rounding).intValueExact();
    }

    private static BigDecimal metres(int millimetres) { return BigDecimal.valueOf(millimetres, 3); }

    static final class PreparedEdge {
        final BigDecimal length;
        final int lengthMm;
        final List<DepthCrossing> crossings;
        final List<Component> components;
        final PipeCatalogEntry pipe;
        final DepthStationMetric metric;
        final List<DepthProfileIssue> issues;

        PreparedEdge(BigDecimal length, int lengthMm, List<DepthCrossing> crossings,
                List<Component> components, PipeCatalogEntry pipe, DepthStationMetric metric, List<DepthProfileIssue> issues) {
            this.length = length; this.lengthMm = lengthMm; this.crossings = crossings;
            this.components = components; this.pipe = pipe; this.metric = metric; this.issues = issues;
        }
    }

    DepthProfileResult failure(PreparedEdge edge) {
        if (!edge.issues.isEmpty()) return normal(edge.length, edge.issues);
        String crossingId = edge.crossings.isEmpty() ? null : edge.crossings.get(0).getId();
        return normal(edge.length, List.of(new DepthProfileIssue("NO_VERTICAL_PASSAGE", crossingId,
                "No representable lawful interval profile")));
    }

    static final class Component {
        final int start;
        int end;
        DepthIntervalClosure.Domain domain;
        private final List<DepthCrossing> crossings = new ArrayList<>();

        private Component(int start, int end, DepthIntervalClosure.Domain domain, DepthCrossing crossing) {
            this.start = start; this.end = end; this.domain = domain;
            if (crossing != null) crossings.add(crossing);
        }
    }
}
