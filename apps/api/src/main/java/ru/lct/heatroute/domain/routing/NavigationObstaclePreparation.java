package ru.lct.heatroute.domain.routing;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;

/**
 * Подготовка запрещённых препятствий для проходов одного поиска; ключ — сам Constraint.
 * Геометрия и опоры доступны только для чтения. Контекст не хранит узлы, карманы или пути.
 * Лимиты учитывают координаты buffer + hull + опоры (даже при общем владении), но не байты JTS.
 * Превышение бюджета оставляет прежнее вычисление без удержания результата; один текущий
 * временный результат, как и раньше, может быть больше бюджета. Контекст живёт только в find.
 */
final class NavigationObstaclePreparation {
    private static final int MAX_ENTRIES = 128;
    private static final long MAX_COORDINATES = 32_768;

    private final double marginM;
    private final Function<Geometry, Coordinate[]> supportCoordinates;
    private final int maxEntries;
    private final long maxCoordinates;
    private final Map<Constraint, Obstacle> retained = new IdentityHashMap<>();
    private long retainedCoordinates;

    NavigationObstaclePreparation(double marginM, Function<Geometry, Coordinate[]> supportCoordinates) {
        this(marginM, supportCoordinates, MAX_ENTRIES, MAX_COORDINATES);
    }

    NavigationObstaclePreparation(double marginM, Function<Geometry, Coordinate[]> supportCoordinates,
            int maxEntries, long maxCoordinates) {
        if (maxEntries < 0 || maxCoordinates < 0) {
            throw new IllegalArgumentException("Preparation limits must be nonnegative");
        }
        this.marginM = marginM;
        this.supportCoordinates = Objects.requireNonNull(supportCoordinates);
        this.maxEntries = maxEntries;
        this.maxCoordinates = maxCoordinates;
    }

    /** Вызывается только после прежних фильтров коридора; не меняет порядок опор. */
    Obstacle prepare(Constraint constraint) {
        ensureNotCancelled();
        Obstacle existing = retained.get(constraint);
        if (existing != null) return existing;
        Geometry boundary = constraint.blocked().buffer(marginM, 2);
        ensureNotCancelled();
        Geometry hull = boundary.convexHull();
        Coordinate[] support = supportCoordinates.apply(hull);
        ensureNotCancelled();
        Obstacle prepared = new Obstacle(boundary, hull, support);
        long coordinates = (long) boundary.getNumPoints() + hull.getNumPoints() + support.length;
        if (retained.size() < maxEntries && coordinates <= maxCoordinates - retainedCoordinates) {
            retained.put(constraint, prepared);
            retainedCoordinates += coordinates;
        }
        return prepared;
    }

    private static void ensureNotCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Route calculation was cancelled");
        }
    }

    static final class Obstacle {
        private final Geometry bufferedBoundary;
        private final Geometry hull;
        private final Coordinate[] support;

        private Obstacle(Geometry bufferedBoundary, Geometry hull, Coordinate[] support) {
            this.bufferedBoundary = bufferedBoundary;
            this.hull = hull;
            this.support = support;
        }

        Geometry bufferedBoundary() { return bufferedBoundary; }
        Geometry hull() { return hull; }
        Coordinate[] support() { return support; }
    }
}
