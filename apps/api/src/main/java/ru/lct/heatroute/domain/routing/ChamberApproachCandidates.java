package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;

/**
 * Предлагает точки нормального подхода к камере: новый луч образует только 90° или 180° с занятыми.
 * Существующие лучи задаются векторами от камеры, а не абсолютными координатами;
 * проверка препятствий и окончательная допустимость сети остаются у вызывающего кода.
 */
public final class ChamberApproachCandidates {
    private static final double ANGLE_EPSILON_DEGREES = 1e-9;
    private static final double FULL_TURN_DEGREES = 360.0;
    private static final double[] ROTATIONS_DEGREES = {-90, 90, 180};
    private static final double[] ALLOWED_ANGLES_DEGREES = {90, 180};

    /**
     * Возвращает точки на расстоянии approachLengthM от камеры, по углу от оси +X в диапазоне [0, 360).
     * Пустой набор лучей или уже четыре луча не дают кандидатов; некорректные координаты,
     * нулевые векторы, неположительная длина и отрицательный допуск отклоняются.
     */
    public List<Coordinate> build(Coordinate junction, List<Coordinate> existingOutwardRays,
            double approachLengthM, double toleranceDegrees) {
        requireFinite(junction, "junction");
        if (existingOutwardRays == null) {
            throw new IllegalArgumentException("existing outward rays are required");
        }
        if (!Double.isFinite(approachLengthM) || approachLengthM < ExpertChamberGeometryRules.MIN_BEND_DISTANCE_M) {
            throw new IllegalArgumentException("approach length must be finite and at least two metres");
        }
        if (!Double.isFinite(toleranceDegrees) || toleranceDegrees < 0) {
            throw new IllegalArgumentException("angle tolerance must be finite and non-negative");
        }
        List<Double> existingAngles = new ArrayList<>();
        for (Coordinate ray : existingOutwardRays) {
            requireFinite(ray, "outward ray");
            if (ray.x == 0 && ray.y == 0) {
                throw new IllegalArgumentException("outward ray must be nonzero");
            }
            existingAngles.add(normalizedDegrees(Math.toDegrees(Math.atan2(ray.y, ray.x))));
        }
        if (existingAngles.isEmpty() || existingAngles.size() >= 4) {
            return List.of();
        }

        List<Double> directions = new ArrayList<>();
        for (double existing : existingAngles) {
            for (double rotation : ROTATIONS_DEGREES) {
                double direction = normalizedDegrees(existing + rotation);
                if (allowedAgainstEveryRay(direction, existingAngles, toleranceDegrees)
                        && normalAgainstEveryRay(direction, approachLengthM, existingOutwardRays)) {
                    directions.add(direction);
                }
            }
        }
        directions.sort(Comparator.naturalOrder());
        List<Double> distinct = new ArrayList<>();
        for (double direction : directions) {
            if (distinct.stream().noneMatch(accepted -> separationDegrees(accepted, direction) <= ANGLE_EPSILON_DEGREES)) {
                distinct.add(direction);
            }
        }

        // Не более 3 * 3 направлений. Усечение по абсолютному азимуту нарушило бы инвариантность поворота.
        List<Coordinate> result = new ArrayList<>();
        for (double direction : distinct) {
            double radians = Math.toRadians(direction);
            Coordinate point = new Coordinate(junction.x + approachLengthM * Math.cos(radians),
                    junction.y + approachLengthM * Math.sin(radians));
            requireFinite(point, "approach point");
            if (point.equals2D(junction)) {
                throw new IllegalArgumentException("metric coordinate precision cannot represent the approach length");
            }
            if (result.stream().noneMatch(existing -> existing.equals2D(point))) {
                result.add(point);
            }
        }
        return List.copyOf(result);
    }

    private boolean normalAgainstEveryRay(double direction, double length, List<Coordinate> rays) {
        double radians = Math.toRadians(direction);
        for (Coordinate ray : rays) {
            if (!ExpertChamberGeometryRules.compatibleRays(length * Math.cos(radians), length * Math.sin(radians),
                    ray.x, ray.y)) return false;
        }
        return true;
    }

    private boolean allowedAgainstEveryRay(double direction, List<Double> existingAngles, double toleranceDegrees) {
        for (double existing : existingAngles) {
            double angle = separationDegrees(direction, existing);
            if (angle <= ANGLE_EPSILON_DEGREES) {
                return false;
            }
            boolean allowed = false;
            for (double standard : ALLOWED_ANGLES_DEGREES) {
                if (Math.abs(angle - standard) <= toleranceDegrees + ANGLE_EPSILON_DEGREES) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private double separationDegrees(double left, double right) {
        double difference = Math.abs(left - right);
        return Math.min(difference, FULL_TURN_DEGREES - difference);
    }

    private double normalizedDegrees(double angle) {
        double normalized = angle % FULL_TURN_DEGREES;
        if (normalized < 0) normalized += FULL_TURN_DEGREES;
        return normalized == 0 || normalized == FULL_TURN_DEGREES ? 0 : normalized;
    }

    private void requireFinite(Coordinate coordinate, String name) {
        if (coordinate == null || !Double.isFinite(coordinate.x) || !Double.isFinite(coordinate.y)) {
            throw new IllegalArgumentException(name + " must have finite x and y");
        }
    }
}
