package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;

/**
 * Соединяет нормальный выход из ОКС с осью коридора, даже если фасад повёрнут относительно неё.
 * Каждый фактический поворот соответствует внутреннему углу 90–120°.
 * Конечный прямой подход к камере не короче minimumApproachM. Расстояние между
 * внутренними поворотами повторно проверяется по фактическому ДУ. Это предложения геометрии:
 * препятствия, округление, фактические углы и весь обязательный ввод проверяет вызывающий код.
 */
final class NormalCorridorTransitions {
    private static final double MAX_TURN_COSINE = 0.5;
    // Погрешность аналитического пересечения лучей в UTM, не ослабление табличного подхода камеры.
    // Без неё ровно минимальное смещение пропадает после поворота/переноса координат.
    private static final double INTERSECTION_LENGTH_EPSILON_M = 1e-8;

    private NormalCorridorTransitions() { }

    static List<List<Coordinate>> build(Coordinate start, Coordinate exit, Coordinate port,
            double orientation, double minimumApproachM) {
        requireFinite(start); requireFinite(exit); requireFinite(port);
        if (!Double.isFinite(orientation) || !Double.isFinite(minimumApproachM) || minimumApproachM < 2.0) {
            throw new IllegalArgumentException("Finite orientation and at least two-metre chamber approach required");
        }
        ensureActive();
        double required = start.distance(exit);
        if (required <= 0.001 || !Double.isFinite(required)) return List.of();
        double nx = (exit.x - start.x) / required, ny = (exit.y - start.y) / required;
        // Соосные фасады сохраняют прямоугольные вводы; диагональ нужна для перехода
        // между разными системами осей, а не для срезания обычных прямых углов.
        boolean tiltedNormal = Math.abs(Math.sin(2 * (Math.atan2(ny, nx) - orientation))) > 1e-8;
        double dx = port.x - start.x, dy = port.y - start.y;
        double reach = start.distance(port) + required + 80.0;
        List<List<Coordinate>> candidates = new ArrayList<>();
        for (int direction = 0; direction < 4; direction++) {
            double angle = orientation + direction * Math.PI / 2;
            double rx = Math.cos(angle), ry = Math.sin(angle);
            double determinant = cross(nx, ny, rx, ry);
            if (Math.abs(determinant) > 1e-8 && validTurn(-nx * rx - ny * ry)) {
                double t = cross(dx, dy, rx, ry) / determinant;
                double s = cross(dx, dy, nx, ny) / determinant;
                if (t >= required + 0.01 && t <= reach && s >= minimumApproachM && s <= reach) {
                    candidates.add(List.of(new Coordinate(exit), new Coordinate(start.x + nx * t, start.y + ny * t), new Coordinate(port)));
                }
            }
            for (int side : new int[] {-1, 1}) {
                for (boolean diagonal : new boolean[] {false, true}) {
                    if (diagonal && !tiltedNormal) continue;
                    double wx = -ry * side, wy = rx * side;
                    if (diagonal) {
                        // Биссектриса прежней поперечной оси и направления прихода -r.
                        // Допускает короткий подход вместо обхода с тремя поворотами.
                        wx = (wx - rx) * MAX_TURN_COSINE;
                        wy = (wy - ry) * MAX_TURN_COSINE;
                    }
                    if (!validTurn(nx * wx + ny * wy)) continue;
                    double denominator = cross(nx, ny, wx, wy);
                    if (Math.abs(denominator) <= 1e-8) continue;
                    for (double s : new double[] {minimumApproachM, 5.0, 10.0, 20.0, 40.0}) {
                        ensureActive();
                        if (s < minimumApproachM) continue;
                        Coordinate elbow2 = new Coordinate(port.x + rx * s, port.y + ry * s);
                        double t = cross(elbow2.x - start.x, elbow2.y - start.y, wx, wy) / denominator;
                        if (t < required + 0.01 - INTERSECTION_LENGTH_EPSILON_M || t > reach) continue;
                        Coordinate elbow1 = new Coordinate(start.x + nx * t, start.y + ny * t);
                        double q = (elbow2.x - elbow1.x) * wx + (elbow2.y - elbow1.y) * wy;
                        if (q < 0.01 - INTERSECTION_LENGTH_EPSILON_M || q > reach) continue;
                        candidates.add(List.of(new Coordinate(exit), elbow1, elbow2, new Coordinate(port)));
                    }
                }
            }
        }
        List<List<Coordinate>> legal = new ArrayList<>();
        for (List<Coordinate> candidate : candidates) {
            if (legalTurns(start, candidate)) legal.add(candidate);
        }
        if (tiltedNormal && legal.size() < 84) {
            addThreeBendTransitions(
                    legal,
                    start,
                    exit,
                    port,
                    orientation,
                    minimumApproachM,
                    reach,
                    84);
        }
        return List.copyOf(legal);
    }

    /**
     * Три поворота нужны, когда разность нормали ОКС и оси коридора лежит между 90° и 120°:
     * двумя поворотами по 60–90° такую разность получить нельзя. Длины двух внутренних звеньев
     * сразу выдерживают тот же табличный минимум, который затем проверяется по фактическому ДУ.
     */
    private static void addThreeBendTransitions(
            List<List<Coordinate>> result,
            Coordinate start,
            Coordinate exit,
            Coordinate port,
            double orientation,
            double minimumM,
            double reach,
            int limit) {
        double initialAngle = Math.atan2(exit.y - start.y, exit.x - start.x);
        double[] lengths = {minimumM, 5.0, 10.0, 20.0, 40.0};
        List<List<Coordinate>> generated = new ArrayList<>();
        for (int finalDirection = 0; finalDirection < 4; finalDirection++) {
            double finalAngle = orientation + finalDirection * Math.PI / 2;
            double d3x = Math.cos(finalAngle), d3y = Math.sin(finalAngle);
            for (int firstSign : new int[] {-1, 1}) {
                for (int secondSign : new int[] {-1, 1}) {
                    for (int firstDegrees = 60; firstDegrees <= 90; firstDegrees += 5) {
                        double firstAngle = initialAngle + firstSign * Math.toRadians(firstDegrees);
                        double d1x = Math.cos(firstAngle), d1y = Math.sin(firstAngle);
                        for (int secondDegrees = 60; secondDegrees <= 90; secondDegrees += 5) {
                            double secondAngle = firstAngle + secondSign * Math.toRadians(secondDegrees);
                            if (!legalTurn(secondAngle, finalAngle)) continue;
                            double d2x = Math.cos(secondAngle), d2y = Math.sin(secondAngle);
                            double determinant = cross(d1x, d1y, d2x, d2y);
                            if (Math.abs(determinant) <= 1e-8) continue;
                            List<Coordinate> best = null;
                            double bestLength = Double.POSITIVE_INFINITY;
                            for (double firstLength : lengths) {
                                for (double lastLength : lengths) {
                                    ensureActive();
                                    double remainingX = port.x - exit.x
                                            - Math.cos(initialAngle) * firstLength - d3x * lastLength;
                                    double remainingY = port.y - exit.y
                                            - Math.sin(initialAngle) * firstLength - d3y * lastLength;
                                    double middle1 = cross(remainingX, remainingY, d2x, d2y) / determinant;
                                    double middle2 = cross(d1x, d1y, remainingX, remainingY) / determinant;
                                    if (middle1 + INTERSECTION_LENGTH_EPSILON_M < minimumM
                                            || middle2 + INTERSECTION_LENGTH_EPSILON_M < minimumM
                                            || middle1 > reach || middle2 > reach) continue;
                                    Coordinate elbow1 = new Coordinate(
                                            exit.x + Math.cos(initialAngle) * firstLength,
                                            exit.y + Math.sin(initialAngle) * firstLength);
                                    Coordinate elbow2 = new Coordinate(
                                            elbow1.x + d1x * middle1,
                                            elbow1.y + d1y * middle1);
                                    Coordinate elbow3 = new Coordinate(
                                            elbow2.x + d2x * middle2,
                                            elbow2.y + d2y * middle2);
                                    List<Coordinate> candidate = List.of(
                                            new Coordinate(exit), elbow1, elbow2, elbow3, new Coordinate(port));
                                    if (legalTurns(start, candidate)) {
                                        double length = pathLength(start, candidate);
                                        if (length < bestLength) {
                                            best = candidate;
                                            bestLength = length;
                                        }
                                    }
                                }
                            }
                            if (best != null) generated.add(best);
                        }
                    }
                }
            }
        }
        generated.sort(Comparator.comparingDouble(candidate -> pathLength(start, candidate)));
        for (List<Coordinate> candidate : generated) {
            if (result.size() >= limit) return;
            result.add(candidate);
        }
    }

    private static boolean legalTurns(Coordinate start, List<Coordinate> path) {
        Coordinate before = start;
        double previousAngle = Double.NaN;
        for (Coordinate current : path) {
            double dx = current.x - before.x, dy = current.y - before.y;
            double length = Math.hypot(dx, dy);
            if (!(length > 1e-9) || !Double.isFinite(length)) return false;
            double angle = Math.atan2(dy, dx);
            if (Double.isFinite(previousAngle) && !legalTurn(previousAngle, angle)) return false;
            previousAngle = angle;
            before = current;
        }
        return true;
    }

    private static boolean legalTurn(double firstAngle, double secondAngle) {
        double change = Math.abs(Math.IEEEremainder(secondAngle - firstAngle, 2 * Math.PI));
        return change <= 1e-9
                || change + 1e-9 >= Math.PI / 3 && change <= Math.PI / 2 + 1e-9;
    }

    private static double pathLength(Coordinate start, List<Coordinate> path) {
        double result = 0;
        Coordinate previous = start;
        for (Coordinate point : path) {
            result += previous.distance(point);
            previous = point;
        }
        return result;
    }

    private static boolean validTurn(double cosine) {
        // Изменение направления 60–90° равно внутреннему углу 120–90°. Здесь только погрешность double.
        return cosine >= -1e-9 && cosine <= MAX_TURN_COSINE + 1e-9;
    }

    private static double cross(double ax, double ay, double bx, double by) { return ax * by - ay * bx; }
    private static void requireFinite(Coordinate point) {
        if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)) {
            throw new IllegalArgumentException("Finite metric coordinates required");
        }
    }
    private static void ensureActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Normal transition cancelled");
    }
}
