package ru.lct.heatroute.domain.constraints;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/**
 * Проверяет осевой отступ до линейной коммуникации вне явно разрешённых интервалов.
 * Класс не выводит спецучастки, врезки или иные исключения и не изменяет исходные оси.
 */
public final class UtilityHorizontalClearance {
    private static final double NUMERIC_EPSILON_M = 1e-6;
    private static final double MAX_ABS_ORDINATE = Math.sqrt(Double.MAX_VALUE) / 16.0;
    private final OfficialAxisClearance axisClearance;

    public UtilityHorizontalClearance(
            OfficialPipeCatalog pipes, OfficialConstraintCatalog constraints) {
        axisClearance =
                new OfficialAxisClearance(
                        Objects.requireNonNull(pipes, "pipes"),
                        Objects.requireNonNull(constraints, "constraints"));
    }

    /**
     * Проверяет полную переданную ось с фактическим ДУ. Интервалы измеряются вдоль этой оси.
     */
    public Assessment assess(
            LineString physicalAxis,
            int actualNewDu,
            UtilitySource source,
            List<AllowedInterval> allowedIntervals) {
        active();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(allowedIntervals, "allowedIntervals");
        checkedGeometry(physicalAxis, "physical axis");
        if (!physicalAxis.isSimple()) {
            throw new IllegalArgumentException("Physical axis must be simple");
        }
        double length = physicalAxis.getLength();
        if (!Double.isFinite(length) || length <= 0) {
            throw new IllegalArgumentException("Physical axis must have finite positive length");
        }
        BigDecimal required =
                axisClearance.axisClearanceM(
                        source.type.code, actualNewDu, source.existingHeatDu);
        List<AllowedInterval> merged = merge(source.id, allowedIntervals, length);
        LengthIndexedLine indexed = new LengthIndexedLine(physicalAxis);
        List<Violation> violations = new ArrayList<>();
        int checkedParts = 0;
        double start = 0;
        boolean startsAtAllowedBoundary = false;
        for (AllowedInterval interval : merged) {
            active();
            if (interval.startM > start) {
                checkPart(
                        indexed,
                        start,
                        interval.startM,
                        startsAtAllowedBoundary,
                        true,
                        source,
                        required.doubleValue(),
                        violations);
                checkedParts++;
            }
            start = interval.endM;
            startsAtAllowedBoundary = true;
        }
        if (start < length) {
            checkPart(
                    indexed,
                    start,
                    length,
                    startsAtAllowedBoundary,
                    false,
                    source,
                    required.doubleValue(),
                    violations);
            checkedParts++;
        }
        return new Assessment(
                source.id,
                source.type,
                actualNewDu,
                source.existingHeatDu,
                required,
                merged,
                checkedParts,
                violations);
    }

    private void checkPart(
            LengthIndexedLine indexed,
            double start,
            double end,
            boolean allowedBefore,
            boolean allowedAfter,
            UtilitySource source,
            double required,
            List<Violation> result) {
        active();
        Geometry part = indexed.extractLine(start, end);
        double distance = part.distance(source.geometry);
        if (!Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("Utility distance is not checkable");
        }
        if (distance >= required - NUMERIC_EPSILON_M) {
            return;
        }
        Coordinate[] nearest = DistanceOp.nearestPoints(part, source.geometry);
        double station = start + new LengthIndexedLine(part).project(nearest[0]);
        boolean atStart = allowedBefore && Math.abs(station - start) <= NUMERIC_EPSILON_M;
        boolean atEnd = allowedAfter && Math.abs(station - end) <= NUMERIC_EPSILON_M;
        boolean boundary = atStart || atEnd;
        if (boundary) {
            Coordinate[] coordinates = part.getCoordinates();
            Coordinate far = coordinates[atStart ? coordinates.length - 1 : 0];
            double farDistance = part.getFactory().createPoint(far).distance(source.geometry);
            // A constant parallel near-pass can return its first coordinate as the nearest one;
            // the minimum is boundary-adjacent only when the route actually departs from it.
            boundary = farDistance > distance + NUMERIC_EPSILON_M;
        }
        result.add(
                new Violation(
                        start,
                        end,
                        distance,
                        station,
                        nearest[0],
                        nearest[1],
                        boundary
                                ? MinimumLocation.BOUNDARY_ADJACENT_MINIMUM
                                : MinimumLocation.ORDINARY_MINIMUM));
        active();
    }

    private List<AllowedInterval> merge(
            String sourceId, List<AllowedInterval> input, double length) {
        List<AllowedInterval> sorted = new ArrayList<>();
        for (AllowedInterval interval : input) {
            active();
            if (interval == null || !sourceId.equals(interval.sourceId)) {
                throw new IllegalArgumentException("Allowed interval source identity mismatch");
            }
            if (!Double.isFinite(interval.startM)
                    || !Double.isFinite(interval.endM)
                    || interval.startM < 0
                    || interval.endM < interval.startM
                    || interval.endM > length) {
                throw new IllegalArgumentException("Allowed interval is outside the physical axis");
            }
            if (interval.endM > interval.startM) {
                sorted.add(interval);
            }
        }
        sorted.sort(
                Comparator.comparingDouble((AllowedInterval interval) -> interval.startM)
                        .thenComparingDouble(interval -> interval.endM));
        List<AllowedInterval> merged = new ArrayList<>();
        for (AllowedInterval interval : sorted) {
            active();
            if (!merged.isEmpty()
                    && interval.startM <= merged.get(merged.size() - 1).endM) {
                AllowedInterval previous = merged.remove(merged.size() - 1);
                merged.add(
                        new AllowedInterval(
                                sourceId,
                                previous.startM,
                                Math.max(previous.endM, interval.endM)));
            } else {
                merged.add(interval);
            }
        }
        return List.copyOf(merged);
    }

    private static void checkedGeometry(Geometry geometry, String name) {
        active();
        if (geometry == null
                || geometry.isEmpty()
                || !(geometry instanceof LineString || geometry instanceof MultiLineString)) {
            throw new IllegalArgumentException(name + " must be a nonempty linear geometry");
        }
        for (Coordinate coordinate : geometry.getCoordinates()) {
            active();
            if (!Double.isFinite(coordinate.x)
                    || !Double.isFinite(coordinate.y)
                    || Math.abs(coordinate.x) > MAX_ABS_ORDINATE
                    || Math.abs(coordinate.y) > MAX_ABS_ORDINATE) {
                throw new IllegalArgumentException(name + " coordinate is outside numeric range");
            }
        }
        if (!geometry.isValid()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void active() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Utility horizontal clearance cancelled");
        }
    }

    public static final class UtilitySource {
        private final String id;
        private final UtilityType type;
        private final Geometry geometry;
        private final Integer existingHeatDu;

        public UtilitySource(
                String id, UtilityType type, Geometry geometry, Integer existingHeatDu) {
            if (id == null || id.isBlank() || type == null) {
                throw new IllegalArgumentException("Utility source identity/type is invalid");
            }
            if (type == UtilityType.HEAT_NETWORK
                            && (existingHeatDu == null || existingHeatDu <= 0)
                    || type != UtilityType.HEAT_NETWORK && existingHeatDu != null) {
                throw new IllegalArgumentException("Existing heat-network DU is invalid");
            }
            checkedGeometry(geometry, "utility source");
            this.id = id;
            this.type = type;
            this.geometry = geometry.copy();
            this.existingHeatDu = existingHeatDu;
        }
    }

    public enum UtilityType {
        GAS_PIPELINE("gas_pipeline"),
        POWER_CABLE("power_cable"),
        HEAT_NETWORK("heat_network");

        private final String code;

        UtilityType(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public static final class AllowedInterval {
        private final String sourceId;
        private final double startM;
        private final double endM;

        public AllowedInterval(String sourceId, double startM, double endM) {
            this.sourceId = sourceId;
            this.startM = startM;
            this.endM = endM;
        }

        public String getSourceId() {
            return sourceId;
        }

        public double getStartM() {
            return startM;
        }

        public double getEndM() {
            return endM;
        }
    }

    public enum MinimumLocation {
        BOUNDARY_ADJACENT_MINIMUM,
        ORDINARY_MINIMUM
    }

    public static final class Violation {
        private final double startM;
        private final double endM;
        private final double minimumAxisDistanceM;
        private final double witnessStationM;
        private final Coordinate axisWitness;
        private final Coordinate sourceWitness;
        private final MinimumLocation minimumLocation;

        private Violation(
                double startM,
                double endM,
                double minimumAxisDistanceM,
                double witnessStationM,
                Coordinate axisWitness,
                Coordinate sourceWitness,
                MinimumLocation minimumLocation) {
            this.startM = startM;
            this.endM = endM;
            this.minimumAxisDistanceM = minimumAxisDistanceM;
            this.witnessStationM = witnessStationM;
            this.axisWitness = new Coordinate(axisWitness);
            this.sourceWitness = new Coordinate(sourceWitness);
            this.minimumLocation = minimumLocation;
        }

        public double getStartM() {
            return startM;
        }

        public double getEndM() {
            return endM;
        }

        public double getMinimumAxisDistanceM() {
            return minimumAxisDistanceM;
        }

        public double getWitnessStationM() {
            return witnessStationM;
        }

        public Coordinate getAxisWitness() {
            return new Coordinate(axisWitness);
        }

        public Coordinate getSourceWitness() {
            return new Coordinate(sourceWitness);
        }

        public MinimumLocation getMinimumLocation() {
            return minimumLocation;
        }
    }

    public static final class Assessment {
        private final String sourceId;
        private final UtilityType type;
        private final int actualNewDu;
        private final Integer existingHeatDu;
        private final BigDecimal requiredAxisDistanceM;
        private final List<AllowedInterval> allowedIntervals;
        private final int checkedParts;
        private final List<Violation> violations;

        private Assessment(
                String sourceId,
                UtilityType type,
                int actualNewDu,
                Integer existingHeatDu,
                BigDecimal requiredAxisDistanceM,
                List<AllowedInterval> allowedIntervals,
                int checkedParts,
                List<Violation> violations) {
            this.sourceId = sourceId;
            this.type = type;
            this.actualNewDu = actualNewDu;
            this.existingHeatDu = existingHeatDu;
            this.requiredAxisDistanceM = requiredAxisDistanceM;
            this.allowedIntervals = allowedIntervals;
            this.checkedParts = checkedParts;
            this.violations = List.copyOf(violations);
        }

        public String getSourceId() {
            return sourceId;
        }

        public UtilityType getType() {
            return type;
        }

        public int getActualNewDu() {
            return actualNewDu;
        }

        public Integer getExistingHeatDu() {
            return existingHeatDu;
        }

        public BigDecimal getRequiredAxisDistanceM() {
            return requiredAxisDistanceM;
        }

        public List<AllowedInterval> getAllowedIntervals() {
            return allowedIntervals;
        }

        public int getCheckedParts() {
            return checkedParts;
        }

        public List<Violation> getViolations() {
            return violations;
        }

        public boolean isAllowed() {
            return violations.isEmpty();
        }
    }
}
