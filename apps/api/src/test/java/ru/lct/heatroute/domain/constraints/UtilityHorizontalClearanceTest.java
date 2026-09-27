package ru.lct.heatroute.domain.constraints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.UtilityHorizontalClearance.AllowedInterval;
import ru.lct.heatroute.domain.constraints.UtilityHorizontalClearance.MinimumLocation;
import ru.lct.heatroute.domain.constraints.UtilityHorizontalClearance.UtilitySource;
import ru.lct.heatroute.domain.constraints.UtilityHorizontalClearance.UtilityType;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

class UtilityHorizontalClearanceTest {
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialConstraintCatalog constraints = new OfficialConstraintCatalog();
    private final OfficialAxisClearance axis = new OfficialAxisClearance(pipes, constraints);
    private final UtilityHorizontalClearance clearance =
            new UtilityHorizontalClearance(pipes, constraints);

    @Test
    void appliesEveryOfficialNewDiameterAtTheExactGasPowerAndHeatBoundary() {
        for (var pipe : pipes.entries()) {
            int newDu = pipe.getDiameter();
            for (UtilityType type : UtilityType.values()) {
                Integer sourceDu = type == UtilityType.HEAT_NETWORK ? 400 : null;
                double required =
                        axis.axisClearanceM(type.getCode(), newDu, sourceDu).doubleValue();
                UtilitySource legal =
                        source(type, line(-10, required, 30, required), sourceDu);
                UtilitySource illegal =
                        source(type, line(-10, required - .001, 30, required - .001), sourceDu);

                assertThat(clearance.assess(line(0, 0, 20, 0), newDu, legal, List.of()).isAllowed())
                        .as("%s DU%s equality", type, newDu)
                        .isTrue();
                assertThat(clearance.assess(line(0, 0, 20, 0), newDu, illegal, List.of()).isAllowed())
                        .as("%s DU%s one millimetre below", type, newDu)
                        .isFalse();
            }
        }
    }

    @Test
    void appliesEveryOfficialNewAndExistingHeatDiameterPair() {
        for (var fresh : pipes.entries()) {
            for (var existing : pipes.entries()) {
                double required =
                        1
                                + fresh.getPairWidthM().doubleValue() / 2
                                + existing.getPairWidthM().doubleValue() / 2;
                assertThat(
                                clearance
                                        .assess(
                                                line(0, 0, 20, 0),
                                                fresh.getDiameter(),
                                                source(
                                                        UtilityType.HEAT_NETWORK,
                                                        line(-10, required, 30, required),
                                                        existing.getDiameter()),
                                                List.of())
                                        .isAllowed())
                        .as("new DU%s existing DU%s equality", fresh.getDiameter(), existing.getDiameter())
                        .isTrue();
                assertThat(
                                clearance
                                        .assess(
                                                line(0, 0, 20, 0),
                                                fresh.getDiameter(),
                                                source(
                                                        UtilityType.HEAT_NETWORK,
                                                        line(-10, required - .001, 30, required - .001),
                                                        existing.getDiameter()),
                                                List.of())
                                        .isAllowed())
                        .as("new DU%s existing DU%s below", fresh.getDiameter(), existing.getDiameter())
                        .isFalse();
            }
        }
    }

    @Test
    void keepsLiteralTwoMetreCrossingBoundarySeparateFromOrdinaryViolations() {
        LineString route = line(-10, 0, 10, 0);
        UtilitySource gas =
                source(UtilityType.GAS_PIPELINE, line(0, -20, 0, 20), null);

        var literal =
                clearance.assess(
                        route,
                        50,
                        gas,
                        List.of(new AllowedInterval("source", 8, 12)));

        assertThat(literal.getRequiredAxisDistanceM()).hasToString("2.400");
        assertThat(literal.getViolations()).hasSize(2);
        assertThat(literal.getViolations())
                .allSatisfy(
                        violation -> {
                            assertThat(violation.getMinimumAxisDistanceM()).isEqualTo(2);
                            assertThat(violation.getMinimumLocation())
                                    .isEqualTo(MinimumLocation.BOUNDARY_ADJACENT_MINIMUM);
                        });

        var ordinary =
                clearance.assess(
                        route,
                        50,
                        source(UtilityType.HEAT_NETWORK, line(-1, 1, 1, 1), 50),
                        List.of());
        assertThat(ordinary.getViolations())
                .singleElement()
                .extracting(UtilityHorizontalClearance.Violation::getMinimumLocation)
                .isEqualTo(MinimumLocation.ORDINARY_MINIMUM);
    }

    @Test
    void unionsOnlyExplicitIntervalsAndLeavesPositiveGapsChecked() {
        LineString route = line(0, 0, 4, 0, 10, 0, 20, 0);
        UtilitySource crossing =
                source(UtilityType.HEAT_NETWORK, line(10, -20, 10, 20), 50);

        var merged =
                clearance.assess(
                        route,
                        50,
                        crossing,
                        List.of(
                                interval(11, 12),
                                interval(8, 10),
                                interval(9, 11),
                                interval(10, 10)));
        assertThat(merged.isAllowed()).isTrue();
        assertThat(merged.getAllowedIntervals())
                .singleElement()
                .satisfies(
                        interval -> {
                            assertThat(interval.getStartM()).isEqualTo(8);
                            assertThat(interval.getEndM()).isEqualTo(12);
                        });

        var gap =
                clearance.assess(
                        route,
                        50,
                        crossing,
                        List.of(interval(8, 9.9), interval(10.1, 12)));
        assertThat(gap.getViolations())
                .singleElement()
                .satisfies(
                        violation ->
                                assertThat(violation.getWitnessStationM()).isEqualTo(10));
    }

    @Test
    void rejectsUnsupportedDiametersAndIntervalsOwnedByAnotherSource() {
        UtilitySource source =
                source(UtilityType.HEAT_NETWORK, line(0, 5, 20, 5), 50);
        assertThatThrownBy(
                        () -> clearance.assess(line(0, 0, 20, 0), 75, source, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                clearance.assess(
                                        line(0, 0, 20, 0),
                                        50,
                                        source,
                                        List.of(new AllowedInterval("other", 0, 20))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity mismatch");
    }

    private AllowedInterval interval(double start, double end) {
        return new AllowedInterval("source", start, end);
    }

    private UtilitySource source(
            UtilityType type, LineString geometry, Integer existingHeatDu) {
        return new UtilitySource("source", type, geometry, existingHeatDu);
    }

    private LineString line(double... xy) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (int index = 0; index < xy.length; index += 2) {
            coordinates.add(new Coordinate(xy[index], xy[index + 1]));
        }
        return geometryFactory.createLineString(coordinates.toArray(Coordinate[]::new));
    }
}
