package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import org.locationtech.jts.geom.Coordinate;

/**
 * Предлагает до восьми локальных переносов камеры на повёрнутой сетке Ханана в радиусе 40 м.
 * Это поисковая эвристика, не инженерный допуск: подходы, всю сеть, ДУ и цену проверяет planner.
 */
final class CorridorChamberRelocations {
    private static final int MAX_EDGE_COORDINATES = 512;
    private static final int MAX_CANDIDATES = 8;
    private static final double RADIUS_M = 40.0;
    private static final double ENDPOINT_TOLERANCE_M = 0.01;
    private static final double RADIUS_EPSILON_M = 1e-8;

    private CorridorChamberRelocations() { }

    /**
     * Принимает 3–4 разных ребра одной новой некорневой камеры; направление хранения полилиний любое.
     * Некорректный вход отклоняется явно, ребро длиннее 512 вершин даёт пустой набор без копирования.
     */
    static List<Coordinate> build(RouteNode chamber, List<RouteEdge> incident, double orientation,
            Predicate<Coordinate> pointAllowed) {
        ensureActive();
        require(chamber != null && !chamber.isRoot() && chamber.isChamber()
                && "new_branch_chamber".equals(chamber.getNodeType()) && named(chamber.getId()),
                "A non-root new branch chamber is required");
        require(incident != null && incident.size() >= 3 && incident.size() <= 4,
                "Three or four incident edges are required");
        require(Double.isFinite(orientation) && pointAllowed != null, "Finite orientation and point predicate required");
        Coordinate center = coordinate(chamber.getCoordinate());
        Frame frame = new Frame(center, orientation);
        Set<String> edgeIds = new HashSet<>(), outerIds = new HashSet<>();
        boolean oversized = false;
        for (RouteEdge edge : incident) {
            ensureActive();
            require(edge != null && named(edge.getId()) && edgeIds.add(edge.getId()), "Distinct named edges required");
            String upstream = edge.getUpstreamNodeId(), downstream = edge.getDownstreamNodeId();
            require(named(upstream) && named(downstream) && !upstream.equals(downstream)
                    && (upstream.equals(chamber.getId()) ^ downstream.equals(chamber.getId())),
                    "Every edge must have two nodes and touch the chamber exactly once");
            require(outerIds.add(upstream.equals(chamber.getId()) ? downstream : upstream), "Distinct outer nodes required");
            require(edge.getCoordinates() != null && edge.getCoordinates().size() >= 2, "Two geometry endpoints required");
            oversized |= edge.getCoordinates().size() > MAX_EDGE_COORDINATES;
        }
        if (oversized) return List.of();

        List<Coordinate> seeds = new ArrayList<>();
        seeds.add(new Coordinate(center));
        List<LocalPoint> outer = new ArrayList<>();
        for (RouteEdge edge : incident) {
            ensureActive();
            List<RouteCoordinate> points = edge.getCoordinates();
            Coordinate first = coordinate(points.get(0)), last = coordinate(points.get(points.size() - 1));
            boolean firstAtChamber = first.distance(center) <= ENDPOINT_TOLERANCE_M;
            boolean lastAtChamber = last.distance(center) <= ENDPOINT_TOLERANCE_M;
            require(firstAtChamber ^ lastAtChamber, "Exactly one geometry endpoint must match the chamber within 1 cm");
            Coordinate endpoint = firstAtChamber ? last : first;
            outer.add(frame.project(endpoint));
            addPoint(seeds, endpoint);
            List<LocalPoint> nearby = new ArrayList<>();
            for (int index = 1; index + 1 < points.size(); index++) {
                ensureActive();
                Coordinate point = coordinate(points.get(index));
                LocalPoint local = frame.project(point);
                if (!point.equals2D(center) && !point.equals2D(endpoint)
                        && local.movement <= RADIUS_M + RADIUS_EPSILON_M) nearby.add(local);
            }
            nearby.sort(localOrder());
            List<Coordinate> selected = new ArrayList<>();
            for (LocalPoint point : nearby) {
                if (addPoint(selected, point.point)) {
                    addPoint(seeds, point.point);
                    if (selected.size() == 2) break;
                }
            }
        }
        // 1 камера + 4 внешних конца + 2 внутренние вершины на ребро = максимум 13 seeds /169 пересечений.
        List<Double> us = new ArrayList<>(), vs = new ArrayList<>();
        for (Coordinate seed : seeds) {
            LocalPoint local = frame.project(seed);
            if (!us.contains(local.u)) us.add(local.u);
            if (!vs.contains(local.v)) vs.add(local.v);
        }
        // Порядок суммирования не зависит от перестановки входных рёбер.
        outer.sort(Comparator.comparingDouble((LocalPoint p) -> p.u).thenComparingDouble(p -> p.v));
        List<LocalPoint> candidates = new ArrayList<>();
        List<Coordinate> seen = new ArrayList<>();
        for (double u : us) for (double v : vs) {
            ensureActive();
            if (Math.hypot(u, v) > RADIUS_M + RADIUS_EPSILON_M) continue;
            Coordinate raw = frame.unproject(u, v);
            Coordinate rounded = new RouteCoordinate(raw.x, raw.y).toCoordinate();
            LocalPoint candidate = frame.project(rounded);
            if (rounded.equals2D(center) || candidate.movement > RADIUS_M + RADIUS_EPSILON_M
                    || !addPoint(seen, rounded)) continue;
            for (LocalPoint endpoint : outer) candidate.score += Math.abs(candidate.u - endpoint.u) + Math.abs(candidate.v - endpoint.v);
            require(Double.isFinite(candidate.score), "Non-finite Manhattan score");
            candidates.add(candidate);
        }
        candidates.sort(Comparator.comparingDouble((LocalPoint p) -> p.score).thenComparing(localOrder()));
        List<Coordinate> accepted = new ArrayList<>();
        for (LocalPoint candidate : candidates) {
            ensureActive();
            // Предикат получает округлённую копию: его побочные изменения не портят результат.
            boolean allowed = pointAllowed.test(new Coordinate(candidate.point));
            ensureActive();
            if (allowed) {
                accepted.add(new Coordinate(candidate.point));
                if (accepted.size() == MAX_CANDIDATES) break;
            }
        }
        return List.copyOf(accepted);
    }

    private static Comparator<LocalPoint> localOrder() {
        return Comparator.comparingDouble((LocalPoint p) -> p.movement).thenComparingDouble(p -> p.u).thenComparingDouble(p -> p.v);
    }

    private static boolean addPoint(List<Coordinate> points, Coordinate point) {
        if (points.stream().anyMatch(previous -> previous.equals2D(point))) return false;
        points.add(new Coordinate(point));
        return true;
    }

    private static Coordinate coordinate(RouteCoordinate value) {
        require(value != null, "Finite XY coordinates required");
        Coordinate point = value.toCoordinate();
        require(point != null && Double.isFinite(point.x) && Double.isFinite(point.y), "Finite XY coordinates required");
        return point;
    }

    private static boolean named(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Chamber relocation cancelled");
    }

    private static final class Frame {
        private final Coordinate center;
        private final double cos, sin;
        private Frame(Coordinate center, double angle) {
            this.center = center; this.cos = Math.cos(angle); this.sin = Math.sin(angle);
        }
        private LocalPoint project(Coordinate point) {
            double dx = point.x - center.x, dy = point.y - center.y;
            double u = dx * cos + dy * sin, v = -dx * sin + dy * cos;
            require(Double.isFinite(u) && Double.isFinite(v) && Double.isFinite(Math.hypot(dx, dy)), "Non-finite local coordinates");
            return new LocalPoint(point, u, v, Math.hypot(dx, dy));
        }
        private Coordinate unproject(double u, double v) {
            Coordinate point = new Coordinate(center.x + u * cos - v * sin, center.y + u * sin + v * cos);
            require(Double.isFinite(point.x) && Double.isFinite(point.y), "Non-finite candidate coordinates");
            return point;
        }
    }

    private static final class LocalPoint {
        private final Coordinate point;
        private final double u, v, movement;
        private double score;
        private LocalPoint(Coordinate point, double u, double v, double movement) {
            this.point = new Coordinate(point); this.u = u; this.v = v; this.movement = movement;
        }
    }
}
