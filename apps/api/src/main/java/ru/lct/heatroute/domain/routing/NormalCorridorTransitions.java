package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;

/**
 * Соединяет нормальный выход из ОКС с осью коридора, даже если фасад повёрнут относительно неё.
 * Внутренние углы 90–135°, между двумя изгибами не меньше minimumLegM. Это предложения геометрии:
 * препятствия, округление, фактические углы и весь обязательный ввод проверяет вызывающий код.
 */
final class NormalCorridorTransitions {
    private static final double MAX_TURN_COSINE = Math.sqrt(0.5);

    private NormalCorridorTransitions() { }

    static List<List<Coordinate>> build(Coordinate start, Coordinate exit, Coordinate port,
            double orientation, double minimumLegM) {
        requireFinite(start); requireFinite(exit); requireFinite(port);
        if (!Double.isFinite(orientation) || !Double.isFinite(minimumLegM) || minimumLegM < 2.0) {
            throw new IllegalArgumentException("Finite orientation and at least two-metre legs required");
        }
        ensureActive();
        double required = start.distance(exit);
        if (required <= 0.001 || !Double.isFinite(required)) return List.of();
        double nx = (exit.x - start.x) / required, ny = (exit.y - start.y) / required;
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
                if (t >= required + 0.01 && t <= reach && s >= minimumLegM && s <= reach) {
                    candidates.add(List.of(new Coordinate(exit), new Coordinate(start.x + nx * t, start.y + ny * t), new Coordinate(port)));
                }
            }
            for (int side : new int[] {-1, 1}) {
                double wx = -ry * side, wy = rx * side;
                if (!validTurn(nx * wx + ny * wy)) continue;
                double denominator = cross(nx, ny, wx, wy);
                if (Math.abs(denominator) <= 1e-8) continue;
                for (double s : new double[] {minimumLegM, 5.0, 10.0, 20.0, 40.0}) {
                    ensureActive();
                    if (s < minimumLegM) continue;
                    Coordinate elbow2 = new Coordinate(port.x + rx * s, port.y + ry * s);
                    double t = cross(elbow2.x - start.x, elbow2.y - start.y, wx, wy) / denominator;
                    if (t < required + minimumLegM || t > reach) continue;
                    Coordinate elbow1 = new Coordinate(start.x + nx * t, start.y + ny * t);
                    double q = (elbow2.x - elbow1.x) * wx + (elbow2.y - elbow1.y) * wy;
                    if (q < minimumLegM || q > reach) continue;
                    candidates.add(List.of(new Coordinate(exit), elbow1, elbow2, new Coordinate(port)));
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static boolean validTurn(double cosine) {
        // Deflection 45..90° равна внутреннему углу 135..90°. Погрешность только double.
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
