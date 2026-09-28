package ru.lct.heatroute.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.run.OfficialRunParameters;

class RoutingProblemSnapshotTest {
    @Test
    void zeroFlowDemandRemainsInSnapshotAndMissingFlowIsRejected() {
        RoutingProblemSnapshot.Demand zero = new RoutingProblemSnapshot.Demand(
                "demand-0", BigDecimal.ZERO, new CatalogMetricPoint(1, 2), "oks-0");
        RoutingProblemSnapshot snapshot = snapshot(List.of(zero));

        assertThat(snapshot.getDemands()).containsExactly(zero);
        assertThat(snapshot.getDemands().get(0).getFlowTph()).isZero();
        assertThat(snapshot.getSnapshotHash()).hasSize(64);
        assertThatThrownBy(() -> new RoutingProblemSnapshot.Demand(
                "missing", null, new CatalogMetricPoint(1, 2), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void snapshotOwnsDemandAndRootCollectionsAndUsesMetricCrs() {
        ArrayList<RoutingProblemSnapshot.Demand> demands = new ArrayList<>(List.of(
                new RoutingProblemSnapshot.Demand("demand-1", new BigDecimal("1.25"),
                        new CatalogMetricPoint(10, 20), null)));
        ArrayList<RoutingProblemSnapshot.RootCandidate> roots = new ArrayList<>(List.of(
                new RoutingProblemSnapshot.RootCandidate("root-1", new CatalogMetricPoint(0, 0),
                        List.of(new RoutingProblemSnapshot.DirectionVector(1, 0)))));
        RoutingProblemSnapshot snapshot = snapshot(demands, roots);
        demands.clear();
        roots.clear();

        assertThat(snapshot.getDemands()).hasSize(1);
        assertThat(snapshot.getRoots()).hasSize(1);
        assertThat(snapshot.getMetricCrs()).isEqualTo("EPSG:32637");
    }

    @Test
    void semanticHashIsStableAcrossInputOrderAndDecimalScale() {
        RoutingProblemSnapshot.Demand a = new RoutingProblemSnapshot.Demand(
                "a", new BigDecimal("1.0"), new CatalogMetricPoint(1, 1), null);
        RoutingProblemSnapshot.Demand b = new RoutingProblemSnapshot.Demand(
                "b", new BigDecimal("2.00"), new CatalogMetricPoint(2, 2), null);
        RoutingProblemSnapshot first = snapshot(List.of(a, b));
        RoutingProblemSnapshot second = snapshot(List.of(
                new RoutingProblemSnapshot.Demand("b", new BigDecimal("2"),
                        new CatalogMetricPoint(2, 2), null),
                new RoutingProblemSnapshot.Demand("a", new BigDecimal("1.000"),
                        new CatalogMetricPoint(1, 1), null)));

        assertThat(second.getSnapshotHash()).isEqualTo(first.getSnapshotHash());
    }

    @Test
    void connectionAndLinkedOksIdentityParticipateInTheSnapshotHash() {
        RoutingProblemSnapshot first = snapshot(List.of(
                new RoutingProblemSnapshot.Demand(
                        "demand", BigDecimal.ONE, new CatalogMetricPoint(1, 1),
                        "connection", "oks-a")));
        RoutingProblemSnapshot second = snapshot(List.of(
                new RoutingProblemSnapshot.Demand(
                        "demand", BigDecimal.ONE, new CatalogMetricPoint(1, 1),
                        "connection", "oks-b")));

        assertThat(second.getSnapshotHash()).isNotEqualTo(first.getSnapshotHash());
    }

    @Test
    void exactRootRealizationParticipatesInTheSnapshotIdentity() {
        RoutingProblemSnapshot.RootRealization firstRealization =
                new RoutingProblemSnapshot.RootRealization(
                        "existing_chamber_tie_in", true, 1, "chamber-1", 200);
        RoutingProblemSnapshot.RootCandidate firstRoot = new RoutingProblemSnapshot.RootCandidate(
                "root-1", new CatalogMetricPoint(0, 0),
                List.of(new RoutingProblemSnapshot.DirectionVector(1, 0)), firstRealization);
        RoutingProblemSnapshot.RootCandidate secondRoot = new RoutingProblemSnapshot.RootCandidate(
                "root-1", new CatalogMetricPoint(0, 0),
                List.of(new RoutingProblemSnapshot.DirectionVector(1, 0)),
                new RoutingProblemSnapshot.RootRealization(
                        "existing_chamber_tie_in", true, 1, "chamber-1", 250));

        assertThat(snapshot(List.of(new RoutingProblemSnapshot.Demand(
                "demand", BigDecimal.ONE, new CatalogMetricPoint(1, 1), null)),
                List.of(firstRoot)).getSnapshotHash()).isNotEqualTo(
                        snapshot(List.of(new RoutingProblemSnapshot.Demand(
                                "demand", BigDecimal.ONE, new CatalogMetricPoint(1, 1), null)),
                                List.of(secondRoot)).getSnapshotHash());
        assertThatThrownBy(() -> new RoutingProblemSnapshot.RootCandidate(
                "root-1", new CatalogMetricPoint(0, 0), List.of(), firstRealization))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incidence");
    }

    private RoutingProblemSnapshot snapshot(List<RoutingProblemSnapshot.Demand> demands) {
        return snapshot(demands, List.of(new RoutingProblemSnapshot.RootCandidate(
                "root-1", new CatalogMetricPoint(0, 0), List.of())));
    }

    private RoutingProblemSnapshot snapshot(List<RoutingProblemSnapshot.Demand> demands,
            List<RoutingProblemSnapshot.RootCandidate> roots) {
        return new RoutingProblemSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "source-1", "extended", "nextgen-1", "official", "rules-1", "cost-1",
                "import-version-1", OfficialRunParameters.defaults(), demands, roots);
    }
}
