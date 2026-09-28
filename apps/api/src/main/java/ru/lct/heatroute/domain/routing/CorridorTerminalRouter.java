package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/**
 * Предлагает прямой ввод или ввод с одним прямым углом и осевым подходом к общему коридору.
 * Выбор направления из нескольких выходов не сводится к лучу прямо на конечный порт.
 * Свободные части проходят строгую проверку отступов; неподходящий случай остаётся общему поиску.
 */
final class CorridorTerminalRouter {
    private static final double PORT_AXIS_TOLERANCE = Math.sin(Math.toRadians(1));
    private static final int MAX_ALTERNATIVES = 8;
    private static final int MAX_ALTERNATIVE_EGRESSES = 16;
    private static final int MAX_CONTROL_POINTS = 512;
    private static final double ROUNDING_MARGIN_M = 0.02;
    private static final GeometryFactory GEOMETRIES = new GeometryFactory();
    private final OfficialObstacleRouter router;
    private final OfficialRoutingEnvironment environment;
    private final SharedSpineNetworkBuilder.TerminalRouter fallback;
    private final double orientation;
    private int attempts;
    private int axialPaths;

    CorridorTerminalRouter(OfficialObstacleRouter router, OfficialRoutingEnvironment environment,
            SharedSpineNetworkBuilder.TerminalRouter fallback, double orientation) {
        this.router = Objects.requireNonNull(router);
        this.environment = Objects.requireNonNull(environment);
        this.fallback = Objects.requireNonNull(fallback);
        if (!Double.isFinite(orientation)) throw new IllegalArgumentException("Finite corridor orientation required");
        this.orientation = orientation;
    }

    RoutePath route(String id, Coordinate point, Coordinate port, int diameter) {
        return routeChoice(id, point, port, diameter).path();
    }

    /** Отмечает замену короткого L-ввода, чтобы поиск мог сохранить отдельное контрольное дерево. */
    Choice routeChoice(String id, Coordinate point, Coordinate port, int diameter) {
        attempts++;
        RoutePath best = null;
        RoutePath straightAlternative = null;
        double rejectedShortestM = Double.POSITIVE_INFINITY;
        List<OfficialRouteGeometryRules.NormalEgress> egresses = environment.normalEgressCandidates(
                diameter, point, port, RoutePlannerTuning.stable().getEngineeringEgressExtraM(), RouteTraversal.REVERSED);
        for (OfficialRouteGeometryRules.NormalEgress egress : egresses) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor terminal cancelled");
            Coordinate exit = egress.exit();
            double required = point.distance(exit);
            if (required <= 0.001) continue;
            if (exit.distance(port) <= 0.01) {
                RoutePath candidate = checkedTerminalEgress(egress, diameter);
                if (candidate != null && (best == null || candidate.lengthM() < best.lengthM())) best = candidate;
                continue;
            }
            double nx = (exit.x - point.x) / required, ny = (exit.y - point.y) / required;
            double along = (port.x - point.x) * nx + (port.y - point.y) * ny;
            if (along <= required + 0.01) continue;
            RoutePath straight = checkedStraightContinuation(egress, port, diameter);
            if (straight != null && (straightAlternative == null || straight.lengthM() < straightAlternative.lengthM())) {
                straightAlternative = straight;
            }
            Coordinate elbow = new Coordinate(point.x + nx * along, point.y + ny * along);
            Coordinate approach = elbow.distance(port) > 0.001 ? elbow : point;
            double angle = Math.atan2(port.y - approach.y, port.x - approach.x);
            // Допуск осевого подхода 0,5°: sin(2θ) имеет период 90°. Это фильтр кандидата,
            // а не ослабление официальной проверки или округление геометрии для красоты.
            if (Math.abs(Math.sin(2 * (angle - orientation))) > PORT_AXIS_TOLERANCE) continue;
            List<Coordinate> coordinates = new ArrayList<>(List.of(exit));
            if (elbow.distance(exit) > 0.001 && elbow.distance(port) > 0.001) coordinates.add(elbow);
            coordinates.add(port);
            RoutePath outside = checkedOutside(coordinates, egress, diameter);
            if (outside == null) continue;
            RoutePath candidate = router.withCheckedCorridorTerminalPrefix(egress, outside, diameter, environment, RouteTraversal.REVERSED);
            if (candidate == null) {
                double prefixM = new RouteCoordinate(point.x, point.y).toCoordinate()
                        .distance(outside.coordinates().get(0));
                rejectedShortestM = Math.min(rejectedShortestM, outside.lengthM() + prefixM);
                continue;
            }
            if (best == null || candidate.lengthM() < best.lengthM()) best = candidate;
        }
        boolean redirectedControl = rejectedShortestM < (best == null ? Double.POSITIVE_INFINITY : best.lengthM());
        // Сохраняем контрольную геометрию дерева; прямой вариант также доступен совместному
        // выбору через alternatives(), а здесь заменяет только отсутствующий допустимый L-ввод.
        if (best == null) best = straightAlternative;
        // Нормали реальных фасадов могут быть повёрнуты относительно общей сетки.
        // Проверенный переход должен участвовать уже в первичном выборе порта.
        if (best == null && !egresses.isEmpty()) {
            best = localAlternatives(id, point, port, diameter).stream()
                    .min(Comparator.comparingDouble(RoutePath::lengthM)).orElse(null);
        }
        if (best != null) axialPaths++;
        RoutePath path = best == null ? fallback.route(id, port, diameter, List.of()) : best;
        return new Choice(path, redirectedControl);
    }

    /** Выбранный путь и происхождение выбора; отклонённая геометрия здесь не хранится. */
    static final class Choice {
        private final RoutePath path;
        private final boolean redirectedControl;

        private Choice(RoutePath path, boolean redirectedControl) {
            this.path = path;
            this.redirectedControl = redirectedControl;
        }

        RoutePath path() { return path; }
        boolean redirectedControl() { return redirectedControl; }
    }

    /**
     * Возвращает до восьми проверенных путей, сначала контрольный route(), если он проходит проверки.
     * Новые варианты: до 16 выходов и пяти двухповоротных смещений на выход. Сначала сохраняются
     * разные конечные лучи, затем дополнительные геометрии; route() и его fallback не меняются.
     */
    List<RoutePath> alternatives(String id, Coordinate point, Coordinate port, int diameter) {
        return alternatives(id, point, port, diameter, true);
    }

    /** Только ограниченные геометрические вводы; перенос камеры не запускает общий поиск пути. */
    List<RoutePath> localAlternatives(String id, Coordinate point, Coordinate port, int diameter) {
        return alternatives(id, point, port, diameter, false);
    }

    private List<RoutePath> alternatives(String id, Coordinate point, Coordinate port, int diameter,
            boolean includeFallback) {
        ensureActive();
        requireInput(id, point, port);
        double clearance = router.buildingClearanceM(diameter);
        Coordinate start = new Coordinate(point), end = new Coordinate(port);
        // Контроль запускается один раз, без replay/cache; fallback не получает владение входами.
        RoutePath original = includeFallback ? route(id, new Coordinate(start), new Coordinate(end), diameter) : null;
        ensureActive();
        List<OfficialRouteGeometryRules.NormalEgress> egresses = environment.normalEgressCandidates(
                diameter, start, end, RoutePlannerTuning.stable().getEngineeringEgressExtraM(), RouteTraversal.REVERSED);
        RoutePath control = checkedControl(original, start, end, diameter, egresses);
        List<RoutePath> candidates = new ArrayList<>();
        if (egresses.isEmpty()) addFreeSpaceAlternatives(candidates, start, end, diameter, clearance);
        for (int i = 0; i < Math.min(MAX_ALTERNATIVE_EGRESSES, egresses.size()); i++) {
            ensureActive();
            OfficialRouteGeometryRules.NormalEgress egress = egresses.get(i);
            Coordinate exit = egress.exit();
            double required = start.distance(exit);
            if (!Double.isFinite(required) || required <= 0.001) continue;
            if (exit.distance(end) <= 0.01) {
                addDistinct(candidates, checkedTerminalEgress(egress, diameter));
                continue;
            }
            double nx = (exit.x - start.x) / required, ny = (exit.y - start.y) / required;
            double t = (end.x - start.x) * nx + (end.y - start.y) * ny;
            Coordinate projection = new Coordinate(start.x + nx * t, start.y + ny * t);
            if (t > required + 0.01) {
                addDistinct(candidates, checkedStraightContinuation(egress, end, diameter));
            }
            for (List<Coordinate> coordinates : NormalCorridorTransitions.build(
                    start, exit, end, orientation, minimumApproachM(diameter))) {
                addDistinct(candidates, checkedGenerated(egress, coordinates, diameter));
            }
            if (t > required + 0.01) {
                List<Coordinate> outside = new ArrayList<>(List.of(exit));
                if (projection.distance(exit) > 0.001 && projection.distance(end) > 0.001) outside.add(projection);
                outside.add(end);
                addDistinct(candidates, checkedGenerated(egress, outside, diameter));
            }
            // Поперечная полка может быть короткой; табличная длина относится к хвосту у камеры.
            if (projection.distance(end) <= 0.001) continue;
            double margin = Math.max(clearance + 0.5, minimumApproachM(diameter));
            double minimumH = required + margin;
            double[] heights = {minimumH, t - margin, (minimumH + t) / 2,
                Math.max(minimumH, t + margin), Math.max(minimumH, t + 2 * margin)};
            for (double h : heights) {
                ensureActive();
                if (!Double.isFinite(h) || h < minimumH || Math.abs(h - t) < minimumApproachM(diameter)) continue;
                Coordinate elbow1 = new Coordinate(start.x + nx * h, start.y + ny * h);
                Coordinate elbow2 = new Coordinate(end.x + nx * (h - t), end.y + ny * (h - t));
                addDistinct(candidates, checkedGenerated(egress, List.of(exit, elbow1, elbow2, end), diameter));
            }
        }
        ensureActive();
        return diverseSelection(control, candidates);
    }

    /** Для точки вне ОКС нет нормального префикса: весь L/Z-путь проверяется без endpoint-исключений. */
    private void addFreeSpaceAlternatives(List<RoutePath> candidates, Coordinate point, Coordinate port,
            int diameter, double clearance) {
        double margin = Math.max(clearance + 0.5, minimumApproachM(diameter));
        for (int direction = 0; direction < 4; direction++) {
            ensureActive();
            double angle = orientation + direction * Math.PI / 2;
            double nx = Math.cos(angle), ny = Math.sin(angle);
            double t = (port.x - point.x) * nx + (port.y - point.y) * ny;
            Coordinate projection = new Coordinate(point.x + nx * t, point.y + ny * t);
            if (t > 0.01) {
                List<Coordinate> points = new ArrayList<>(List.of(point));
                if (projection.distance(port) > 0.001) points.add(projection);
                points.add(port);
                addDistinct(candidates, checkedFreeSpace(points, point, diameter, true));
            }
            if (projection.distance(port) <= 0.001) continue;
            double[] heights = {margin, t - margin, (margin + t) / 2,
                Math.max(margin, t + margin), Math.max(margin, t + 2 * margin)};
            for (double h : heights) {
                ensureActive();
                if (!Double.isFinite(h) || h < margin || Math.abs(h - t) < minimumApproachM(diameter)) continue;
                Coordinate elbow1 = new Coordinate(point.x + nx * h, point.y + ny * h);
                Coordinate elbow2 = new Coordinate(port.x + nx * (h - t), port.y + ny * (h - t));
                addDistinct(candidates, checkedFreeSpace(List.of(point, elbow1, elbow2, port), point, diameter, true));
            }
        }
    }

    private RoutePath checkedFreeSpace(List<Coordinate> coordinates, Coordinate point, int diameter, boolean requireAxis) {
        ensureActive();
        Envelope bounds = new Envelope();
        for (Coordinate coordinate : coordinates) {
            if (!finiteMetric(coordinate)) return null;
            bounds.expandToInclude(coordinate);
        }
        PreparedCorridor checks = router.prepareCorridor(diameter, environment, bounds, point, null, RouteTraversal.REVERSED);
        // PreparedCorridor умеет локальный выход существующего корня из setback. Здесь корня
        // теплосети нет, поэтому запрещаем такое послабление и до, и после округления координат.
        for (Coordinate coordinate : coordinates) if (!checks.pointAllowed(coordinate)) return null;
        RoutePath path = checks.path(coordinates);
        if (!soundGeometry(path, diameter)) return null;
        for (Coordinate coordinate : path.coordinates()) if (!checks.pointAllowed(coordinate)) return null;
        List<Coordinate> points = path.coordinates();
        return !requireAxis || axisAligned(points.get(points.size() - 2), points.get(points.size() - 1)) ? path : null;
    }

    private RoutePath checkedGenerated(OfficialRouteGeometryRules.NormalEgress egress,
            List<Coordinate> coordinates, int diameter) {
        ensureActive();
        for (Coordinate coordinate : coordinates) if (!finiteMetric(coordinate)) return null;
        if (!axisAligned(coordinates.get(coordinates.size() - 2), coordinates.get(coordinates.size() - 1))) return null;
        RoutePath outside = checkedOutside(coordinates, egress, diameter);
        if (outside == null) return null;
        RoutePath full = router.withCheckedCorridorTerminalPrefix(egress, outside, diameter, environment, RouteTraversal.REVERSED);
        if (!soundGeometry(full, diameter)) return null;
        List<Coordinate> points = full.coordinates();
        return axisAligned(points.get(points.size() - 2), points.get(points.size() - 1)) ? full : null;
    }

    /** Почти соосному порту не нужен сантиметровый доглег; допуск прямого участка задаёт evaluator. */
    private RoutePath checkedStraightContinuation(OfficialRouteGeometryRules.NormalEgress egress,
            Coordinate port, int diameter) {
        RoutePath path = checkedGenerated(egress, List.of(egress.exit(), port), diameter);
        if (path == null) return null;
        RouteEdge edge = new RouteEdge("terminal-straight", "terminal", "port", path.lengthM(),
                path.coordinates().stream().map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()),
                path.sections(), null, diameter);
        return new EngineeringRouteEvaluator().evaluate(List.of(edge)).bendCount() == 0 ? path : null;
    }

    private RoutePath checkedTerminalEgress(OfficialRouteGeometryRules.NormalEgress egress, int diameter) {
        RoutePath path = router.withCheckedCorridorTerminalEgress(
                egress, diameter, environment, RouteTraversal.REVERSED);
        return soundGeometry(path, diameter) ? path : null;
    }

    private RoutePath checkedOutside(List<Coordinate> coordinates,
            OfficialRouteGeometryRules.NormalEgress egress, int diameter) {
        Envelope bounds = new Envelope();
        coordinates.forEach(bounds::expandToInclude);
        bounds.expandToInclude(egress.start());
        PreparedCorridor checks = router.prepareCorridor(diameter, environment, bounds, egress.exit(), null, RouteTraversal.REVERSED);
        return checks.pathAfter(egress.start(), coordinates);
    }

    /** Старый fallback остаётся доступен через route(); в список нельзя включить непроверенный результат. */
    private RoutePath checkedControl(RoutePath original, Coordinate point, Coordinate port, int diameter,
            List<OfficialRouteGeometryRules.NormalEgress> egresses) {
        if (!soundGeometry(original, diameter)) return null;
        List<Coordinate> points = original.coordinates();
        if (points.get(0).distance(point) > 0.001 || points.get(points.size() - 1).distance(port) > 0.001) return null;
        if (egresses.isEmpty()) {
            RoutePath checked = checkedFreeSpace(points, point, diameter, false);
            return soundGeometry(checked, diameter) && sameGeometry(original, checked) ? checked : null;
        }
        for (OfficialRouteGeometryRules.NormalEgress egress : egresses) {
            ensureActive();
            Coordinate exit = egress.exit();
            Coordinate roundedExit = new RouteCoordinate(exit.x, exit.y).toCoordinate();
            if (!points.get(1).equals2D(roundedExit)) continue;
            RoutePath outside = checkedOutside(points.subList(1, points.size()), egress, diameter);
            if (outside == null) continue;
            RoutePath checked = router.withCheckedCorridorTerminalPrefix(egress, outside, diameter, environment, RouteTraversal.REVERSED);
            if (soundGeometry(checked, diameter) && sameGeometry(original, checked)) return checked;
        }
        return null;
    }

    /** Запас округления относится только к последнему прямому подходу камеры. */
    private static double minimumApproachM(int diameter) {
        return ExpertChamberGeometryRules.minimumBendDistanceM(diameter) + ROUNDING_MARGIN_M;
    }

    private boolean soundGeometry(RoutePath path, int diameter) {
        ensureActive();
        if (path == null || path.coordinates().size() < 2 || path.coordinates().size() > MAX_CONTROL_POINTS
                || !Double.isFinite(path.lengthM())) return false;
        List<Coordinate> points = path.coordinates();
        for (int i = 0; i < points.size(); i++) {
            if (!finiteMetric(points.get(i)) || (i > 0 && points.get(i).distance(points.get(i - 1)) <= 0.01)) return false;
        }
        LineString line = GEOMETRIES.createLineString(points.toArray(new Coordinate[0]));
        if (line.isClosed() || !line.isSimple() || Math.abs(path.lengthM() - line.getLength()) > 0.002) return false;
        RouteEdge edge = new RouteEdge("terminal-alternative", "terminal", "port", path.lengthM(), points.stream()
                .map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()), path.sections(), null, diameter);
        boolean compliant = new EngineeringRouteEvaluator().evaluate(List.of(edge)).isCompliant();
        ensureActive();
        return compliant;
    }

    private List<RoutePath> diverseSelection(RoutePath control, List<RoutePath> candidates) {
        // Равные по длине зигзаги не должны вытеснять простой ввод из ограниченного набора.
        Map<RoutePath, Integer> bends = new java.util.IdentityHashMap<>();
        for (RoutePath candidate : candidates) {
            RouteEdge edge = new RouteEdge("terminal-choice", "terminal", "port", candidate.lengthM(),
                    candidate.coordinates().stream().map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()),
                    candidate.sections(), null, null);
            bends.put(candidate, new EngineeringRouteEvaluator().evaluate(List.of(edge)).bendCount());
        }
        // До пяти округлённых отрезков дают миллиметровую погрешность суммарной длины.
        // Среди равноценных по сантиметру форм выбираем в системе коридора, а не по мировому X/Y.
        candidates.sort(Comparator.comparingLong((RoutePath path) -> Math.round(path.lengthM() * 100))
                .thenComparingInt(bends::get).thenComparing(this::geometryKey));
        Map<Integer, ArrayDeque<RoutePath>> byRay = new TreeMap<>();
        for (RoutePath candidate : candidates) {
            if (control != null && sameGeometry(control, candidate)) continue;
            byRay.computeIfAbsent(finalRay(candidate), ignored -> new ArrayDeque<>()).add(candidate);
        }
        List<RoutePath> selected = new ArrayList<>();
        if (control != null) selected.add(control);
        for (Map.Entry<Integer, ArrayDeque<RoutePath>> entry : byRay.entrySet()) {
            if (control == null || entry.getKey() != finalRay(control)) selected.add(entry.getValue().remove());
        }
        boolean added = true;
        while (selected.size() < MAX_ALTERNATIVES && added) {
            ensureActive();
            added = false;
            for (ArrayDeque<RoutePath> paths : byRay.values()) {
                if (!paths.isEmpty() && selected.size() < MAX_ALTERNATIVES) { selected.add(paths.remove()); added = true; }
            }
        }
        return List.copyOf(selected);
    }

    private int finalRay(RoutePath path) {
        List<Coordinate> points = path.coordinates();
        Coordinate before = points.get(points.size() - 2), port = points.get(points.size() - 1);
        // Неосевой, но проверенный контрольный fallback получает отдельное место.
        if (!axisAligned(before, port)) return 4;
        double angle = Math.atan2(port.y - before.y, port.x - before.x) - orientation;
        return Math.floorMod((int) Math.round(Math.IEEEremainder(angle, 2 * Math.PI) / (Math.PI / 2)), 4);
    }

    private boolean axisAligned(Coordinate before, Coordinate port) {
        return before.distance(port) > 0.01
                && Math.abs(Math.sin(2 * (Math.atan2(port.y - before.y, port.x - before.x) - orientation))) <= PORT_AXIS_TOLERANCE;
    }

    private void addDistinct(List<RoutePath> paths, RoutePath candidate) {
        if (candidate != null && paths.stream().noneMatch(existing -> sameGeometry(existing, candidate))) paths.add(candidate);
    }

    private boolean sameGeometry(RoutePath left, RoutePath right) {
        if (left.coordinates().size() != right.coordinates().size()) return false;
        for (int i = 0; i < left.coordinates().size(); i++) {
            if (!left.coordinates().get(i).equals2D(right.coordinates().get(i))) return false;
        }
        return true;
    }

    private String geometryKey(RoutePath path) {
        StringBuilder key = new StringBuilder();
        Coordinate origin = path.coordinates().get(0);
        double c = Math.cos(orientation), s = Math.sin(orientation);
        path.coordinates().forEach(p -> {
            double dx = p.x - origin.x, dy = p.y - origin.y;
            key.append(Math.round((dx * c + dy * s) * 100)).append(',')
                    .append(Math.round((-dx * s + dy * c) * 100)).append(';');
        });
        return key.toString();
    }

    private static void requireInput(String id, Coordinate point, Coordinate port) {
        if (id == null || id.isBlank() || !finiteMetric(point) || !finiteMetric(port)
                || !Double.isFinite(point.distance(port)) || point.distance(port) <= 0.01) {
            throw new IllegalArgumentException("Terminal alternatives require ID and distinct finite metric XY points");
        }
    }

    private static boolean finiteMetric(Coordinate point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y)
                && Math.ulp(point.x) <= 0.001 && Math.ulp(point.y) <= 0.001;
    }

    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Corridor terminal alternatives cancelled");
    }

    int attempts() { return attempts; }
    int axialPaths() { return axialPaths; }
}
