package ru.lct.heatroute.domain.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.routing.ExpertChamberGeometryRules;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteNode;

class ExistingNetworkSupportIndexTest {
    @Test
    void selectedPipeAndAllSharedEndpointPipesContributeButNearbyPipesDoNot() throws Exception {
        ExistingNetworkSupportIndex index = new ExistingNetworkSupportIndex(List.of(
                pipe("selected", "LINESTRING(0 0,100 0)", "100"),
                pipe("joined", "LINESTRING(0 0,-100 0)", "1000"),
                pipe("nearby", "LINESTRING(0 1,100 1)", "1400")));
        assertThat(index.maximumDiameter("selected", new Coordinate(50, 0))).isEqualTo(100);
        assertThat(index.maximumDiameter("selected", new Coordinate(0, 0))).isEqualTo(1000);
        assertThat(index.maximumDiameter("joined", new Coordinate(0, 0))).isEqualTo(1000);
    }

    @Test
    void proximityToTheRoundedNodeDoesNotDoubleTheEndpointConnectionTolerance() throws Exception {
        ExistingNetworkSupportIndex index = new ExistingNetworkSupportIndex(List.of(
                pipe("selected", "LINESTRING(0 0,100 0)", "100"),
                pipe("nearby", "LINESTRING(0 0.016,100 0.016)", "1400")));
        assertThat(index.maximumDiameter("selected", new Coordinate(0, 0.008))).isEqualTo(100);
    }

    @Test
    void reversedTranslatedAndNonAxisAlignedSupportHasTheSameMeaning() throws Exception {
        ExistingNetworkSupportIndex index = new ExistingNetworkSupportIndex(List.of(
                pipe("selected", "LINESTRING(110 220,100 200)", "250"),
                pipe("joined", "LINESTRING(100 200,90 205)", "600")));
        assertThat(index.maximumDiameter("selected", new Coordinate(105, 210))).isEqualTo(250);
        assertThat(index.maximumDiameter("selected", new Coordinate(100, 200))).isEqualTo(600);
    }

    @Test
    void acceptsMillimetreCoordinateRoundingButRejectsADifferentSupport() throws Exception {
        ExistingNetworkSupportIndex index = new ExistingNetworkSupportIndex(List.of(
                pipe("selected", "LINESTRING(0 0,100 33.333333)", "500")));
        assertThat(index.maximumDiameter("selected", new Coordinate(50, 16.667))).isEqualTo(500);
        assertThatThrownBy(() -> index.maximumDiameter("selected", new Coordinate(50, 20)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not lie");
        assertThatThrownBy(() -> index.maximumDiameter("missing", new Coordinate(0, 0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing");
    }

    @Test
    void crossingAndOverlappingSegmentsAreNotSilentlyTreatedAsValidConnections() throws Exception {
        for (String other : List.of("LINESTRING(50 -20,50 20)", "LINESTRING(0 0,100 0)")) {
            ExistingNetworkSupportIndex index = new ExistingNetworkSupportIndex(List.of(
                    pipe("selected", "LINESTRING(0 0,100 0)", "100"), pipe("other", other, "1000")));
            assertThatThrownBy(() -> index.maximumDiameter("selected", new Coordinate(50, 0)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ambiguous");
        }
    }

    @Test
    void missingInvalidOrNonIntegralDiameterIsNotInvented() throws Exception {
        for (String diameter : List.of("null", "0", "101", "100.5", "\"100\"")) {
            ExistingNetworkSupportIndex index = new ExistingNetworkSupportIndex(List.of(
                    pipe("selected", "LINESTRING(0 0,100 0)", diameter)));
            assertThatThrownBy(() -> index.maximumDiameter("selected", new Coordinate(50, 0)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("diameter");
        }
    }

    @Test
    void joinedPipeWithoutDiameterBlocksTheMaximumInsteadOfBeingIgnored() throws Exception {
        ExistingNetworkSupportIndex index = new ExistingNetworkSupportIndex(List.of(
                pipe("selected", "LINESTRING(0 0,100 0)", "100"),
                pipe("joined", "LINESTRING(0 0,-100 0)", "null")));
        assertThatThrownBy(() -> index.maximumDiameter("selected", new Coordinate(0, 0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("joined");
    }

    @Test
    void resolvesLegacyMetadataButRejectsAContradictorySavedDiameter() throws Exception {
        ExistingNetworkSupportIndex index = new ExistingNetworkSupportIndex(List.of(
                pipe("selected", "LINESTRING(0 0,100 0)", "1000")));
        RouteNode old = new RouteNode("root", "new_tie_in_chamber", new RouteCoordinate(50, 0),
                true, true, 2, "selected");
        assertThat(index.verified(old).getExistingIncidentDiameter()).isEqualTo(1000);
        assertThat(old.getExistingIncidentDiameter()).isNull();
        assertThat(index.verified(old.withExistingIncidentDiameter(1000)).getExistingIncidentDiameter()).isEqualTo(1000);
        assertThatThrownBy(() -> index.verified(old.withExistingIncidentDiameter(100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("differs");
    }

    @Test
    void newBranchAndExistingChamberDoNotRequireAFictitiousNetworkTarget() {
        ExistingNetworkSupportIndex index = new ExistingNetworkSupportIndex(List.of());
        for (RouteNode node : List.of(
                new RouteNode("branch", "new_branch_chamber", new RouteCoordinate(0, 0), true, false, 0, null),
                new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(0, 0), true, true, 2, "old-camera"))) {
            assertThat(index.verified(node)).isSameAs(node);
        }
    }

    @Test
    void digitizedExistingSectionsAreResolvedToOneExactSquareAxisSystem() throws Exception {
        ExistingNetworkSupportIndex index = new ExistingNetworkSupportIndex(List.of(
                pipe("east", "LINESTRING(0 0,20 0)", "100"),
                pipe("north", "LINESTRING(0 0,0.349 19.997)", "100"),
                pipe("west", "LINESTRING(0 0,-19.997 -0.628)", "100")));
        RouteNode chamber = new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(0, 0),
                true, true, 3, "chamber");

        List<Coordinate> directions = index.existingDirections(chamber);

        assertThat(directions).hasSize(3);
        for (int left = 0; left < directions.size(); left++) {
            for (int right = left + 1; right < directions.size(); right++) {
                assertThat(ExpertChamberGeometryRules.compatibleRays(
                        directions.get(left).x, directions.get(left).y,
                        directions.get(right).x, directions.get(right).y)).isTrue();
            }
        }
    }

    @Test
    void coincidentOrMateriallyObliqueExistingSectionsAreNotInventedIntoSquareAxes() throws Exception {
        for (String second : List.of("LINESTRING(0 0,20 0)", "LINESTRING(0 0,17.321 10)")) {
            ExistingNetworkSupportIndex index = new ExistingNetworkSupportIndex(List.of(
                    pipe("first", "LINESTRING(0 0,20 0)", "100"), pipe("second", second, "100")));
            RouteNode chamber = new RouteNode("root", "existing_chamber_tie_in", new RouteCoordinate(0, 0),
                    true, true, 2, "chamber");
            List<Coordinate> directions = index.existingDirections(chamber);
            assertThat(directions).hasSize(2);
            assertThat(ExpertChamberGeometryRules.compatibleRays(
                    directions.get(0).x, directions.get(0).y,
                    directions.get(1).x, directions.get(1).y)).isFalse();
        }
    }

    @Test
    void duplicateIdsAndCancellationAreExplicitFailures() throws Exception {
        ImportedOfficialFeature pipe = pipe("same", "LINESTRING(0 0,100 0)", "100");
        assertThatThrownBy(() -> new ExistingNetworkSupportIndex(List.of(pipe, pipe)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
        ExistingNetworkSupportIndex index = new ExistingNetworkSupportIndex(List.of(pipe));
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> index.maximumDiameter("same", new Coordinate(0, 0)))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private ImportedOfficialFeature pipe(String id, String wkt, String diameter) throws Exception {
        return new ImportedOfficialFeature(id, "heat_network", new ObjectMapper().readTree("{\"diameter\":" + diameter + "}"),
                new WKTReader().read(wkt));
    }
}
