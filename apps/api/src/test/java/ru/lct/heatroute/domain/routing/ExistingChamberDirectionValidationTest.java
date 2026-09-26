package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatroute.domain.topology.ExistingNetworkSupportIndex;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class ExistingChamberDirectionValidationTest {
    private final GeometryFactory geometries = new GeometryFactory();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExpertChamberRouteValidator validator = new ExpertChamberRouteValidator();

    @ParameterizedTest
    @ValueSource(doubles = {0, 17.4, 37, 89.2, 135, 203.7})
    void normalsFollowRotatedExistingNetworkWithoutAssumingCoordinateAxes(double angleDegrees) {
        Coordinate before = rotate(-20, 0, angleDegrees), after = rotate(20, 0, angleDegrees);
        ExistingNetworkSupportIndex support = new ExistingNetworkSupportIndex(List.of(pipe("source", before, after)));
        RouteNode chamber = node("c", rotate(0, 0, angleDegrees), true);
        RouteNode normal = node("normal", rotate(0, 20, angleDegrees), false);
        RouteNode oblique = node("oblique", rotate(15, 20, angleDegrees), false);
        assertThat(validator.validate(List.of(chamber, normal), List.of(edge(chamber, normal)), support::existingDirections)).isEmpty();
        assertThat(validator.validate(List.of(chamber, oblique), List.of(edge(chamber, oblique)), support::existingDirections))
                .extracting(RouteValidationIssue::getCode).contains("EXPERT_CHAMBER_OBLIQUE_ENTRY");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void subdivisionAndCoordinateOrderDoNotDuplicateOrLoseExistingRays(boolean split) {
        Coordinate west = rotate(-20, 0, 0), center = rotate(0, 0, 0), east = rotate(20, 0, 0);
        List<ImportedOfficialFeature> sources = split ? List.of(pipe("west", center, west), pipe("east", east, center))
                : List.of(pipe("through", west, center, center, east));
        ExistingNetworkSupportIndex support = new ExistingNetworkSupportIndex(sources);
        RouteNode chamber = node("c", center, true);
        RouteNode north = node("north", rotate(0, 20, 0), false), south = node("south", rotate(0, -20, 0), false);
        assertThat(support.existingDirections(chamber)).hasSize(2);
        assertThat(validator.validate(List.of(chamber, north, south), List.of(edge(chamber, north), edge(chamber, south)),
                support::existingDirections)).isEmpty();
    }

    @Test
    void nonRootChamberDoesNotBorrowAnUnrelatedNearbyExistingNetworkAxis() {
        RouteNode chamber = new RouteNode("c", "new_junction_chamber", new RouteCoordinate(400000, 6000000), true, false, 0, null);
        ExistingNetworkSupportIndex support = new ExistingNetworkSupportIndex(List.of(pipe("source",
                rotate(-20, 0, 0), rotate(20, 0, 0))));
        assertThat(support.existingDirections(chamber)).isEmpty();
    }

    @Test
    void streamingSummaryRetainsWholeStraightRunsAndDoesNotIterateTwice() {
        int[] reads = {0};
        List<RouteCoordinate> points = List.of(new RouteCoordinate(0, 0), new RouteCoordinate(0, 0),
                new RouteCoordinate(1, 0), new RouteCoordinate(2, 0), new RouteCoordinate(2, 1), new RouteCoordinate(2, 3));
        ExpertChamberGeometryRules.PolylineSummary summary = ExpertChamberGeometryRules.summarize(() -> {
            assertThat(++reads[0]).isEqualTo(1);
            return points.iterator();
        });
        assertThat(summary.getActualLengthM()).isEqualTo(5);
        assertThat(summary.getFirstBendDistanceM()).isEqualTo(2);
        assertThat(summary.getLastBendDistanceM()).isEqualTo(3);
        assertThat(summary.getFirstDx()).isEqualTo(2);
        assertThat(summary.getFirstDy()).isZero();
        assertThat(summary.getLastDx()).isZero();
        assertThat(summary.getLastDy()).isEqualTo(3);
    }

    private RouteNode node(String id, Coordinate coordinate, boolean chamber) {
        return new RouteNode(id, chamber ? "existing_chamber_tie_in" : "demand_connection",
                new RouteCoordinate(coordinate.x, coordinate.y), chamber, chamber, chamber ? 2 : 0, null);
    }
    private RouteEdge edge(RouteNode from, RouteNode to) {
        return new RouteEdge(from.getId() + "-" + to.getId(), from.getId(), to.getId(), 20,
                List.of(from.getCoordinate(), to.getCoordinate()), List.of(), null, 100);
    }
    private Coordinate rotate(double x, double y, double degrees) {
        double angle = Math.toRadians(degrees);
        return new Coordinate(400000 + x * Math.cos(angle) - y * Math.sin(angle),
                6000000 + x * Math.sin(angle) + y * Math.cos(angle));
    }
    private ImportedOfficialFeature pipe(String id, Coordinate... coordinates) {
        return new ImportedOfficialFeature(id, "heat_network", mapper.createObjectNode().put("diameter", 100),
                geometries.createLineString(coordinates));
    }
}
