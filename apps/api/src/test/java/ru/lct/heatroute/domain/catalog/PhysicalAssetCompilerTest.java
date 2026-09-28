package ru.lct.heatroute.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PhysicalAssetCompilerTest {
    private final PhysicalAssetCompiler compiler = new PhysicalAssetCompiler();

    @Test
    void atomizesPartialOverlapAndReusesOnlyTheSharedInterval() {
        PhysicalAssetCompiler.Result result = compiler.compile(List.of(
                path("a", "surface:new", points(0, 0, 10_000, 0)),
                path("b", "surface:new", points(5_000, 0, 15_000, 0))));

        assertThat(result.getPhysicalAssets()).hasSize(3);
        assertThat(result.path("a").getPhysicalAssetIds()).hasSize(2);
        assertThat(result.path("b").getPhysicalAssetIds()).hasSize(2);
        assertThat(result.path("a").getPhysicalAssetIds().get(1))
                .isEqualTo(result.path("b").getPhysicalAssetIds().get(0));
        CatalogPhysicalAsset shared = result.getPhysicalAssets().stream()
                .filter(asset -> asset.getSourcePathIds().size() == 2)
                .findFirst().orElseThrow();
        assertThat(shared.getFirstPoint()).isEqualTo(new CatalogMetricPoint(5_000, 0));
        assertThat(shared.getSecondPoint()).isEqualTo(new CatalogMetricPoint(10_000, 0));
    }

    @Test
    void reverseTraversalUsesTheSamePhysicalIdentityOnlyInTheSameContext() {
        PhysicalAssetCompiler.Result result = compiler.compile(List.of(
                path("forward", "level:0:new", points(0, 0, 10_000, 0)),
                path("reverse", "level:0:new", points(10_000, 0, 0, 0)),
                path("other-level", "level:-1:new", points(0, 0, 10_000, 0))));

        assertThat(result.getPhysicalAssets()).hasSize(2);
        PhysicalAssetCompiler.AssetTraversal forward = result.path("forward").getTraversals().get(0);
        PhysicalAssetCompiler.AssetTraversal reverse = result.path("reverse").getTraversals().get(0);
        assertThat(forward.getAssetId()).isEqualTo(reverse.getAssetId());
        assertThat(forward.isCanonicalDirection()).isNotEqualTo(reverse.isCanonicalDirection());
        assertThat(result.path("other-level").getPhysicalAssetIds().get(0)).isNotEqualTo(forward.getAssetId());
    }

    @Test
    void ordinaryXyCrossingDoesNotCreateAFreeJunctionOrTechnicalSplit() {
        PhysicalAssetCompiler.Result result = compiler.compile(List.of(
                path("horizontal", "surface:new", points(0, 5_000, 10_000, 5_000)),
                path("vertical", "surface:new", points(5_000, 0, 5_000, 10_000))));

        assertThat(result.getPhysicalAssets()).hasSize(2);
        assertThat(result.path("horizontal").getTraversals()).hasSize(1);
        assertThat(result.path("vertical").getTraversals()).hasSize(1);
    }

    @Test
    void technicalSplitDoesNotChangePhysicalCoverageOrExactLength() {
        PhysicalAssetCompiler.Result result = compiler.compile(List.of(
                path("single", "surface:new", points(0, 0, 10_000, 0)),
                path("split", "surface:new", points(0, 0, 5_000, 0, 10_000, 0))));

        assertThat(result.getPhysicalAssets()).hasSize(2);
        assertThat(result.path("single").getPhysicalAssetIds())
                .containsExactlyElementsOf(result.path("split").getPhysicalAssetIds());
        assertThat(result.getPhysicalAssets().stream()
                .mapToDouble(CatalogPhysicalAsset::getExactLengthMm).sum()).isEqualTo(10_000.0);
    }

    @Test
    void declaredSnapshotRequiresAContinuousKnownPhysicalChain() {
        PhysicalAssetCompiler.Result physical = compiler.compile(List.of(
                path("route", "surface:new", points(0, 0, 5_000, 0, 10_000, 0))));
        DirectedPathOption valid = option("route", physical.path("route").getPhysicalAssetIds());

        RoutingCatalogSnapshot snapshot = new RoutingCatalogSnapshot(
                "source-1", "official", "rules-1", "catalog-1",
                physical.getPhysicalAssets(), List.of(valid));

        assertThat(snapshot.hasDeclaredPhysicalAssets()).isTrue();
        assertThat(snapshot.getPhysicalAssets()).hasSize(2);
        assertThat(snapshot.getCatalogHash()).hasSize(64);
        DirectedPathOption unknown = option("unknown", List.of("asset:missing"));
        assertThatThrownBy(() -> new RoutingCatalogSnapshot(
                "source-1", "official", "rules-1", "catalog-1",
                physical.getPhysicalAssets(), List.of(unknown)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown physical asset");
    }

    private PhysicalAssetCompiler.CandidatePath path(String id, String context,
            List<CatalogMetricPoint> points) {
        return new PhysicalAssetCompiler.CandidatePath(id, context, "chain:" + id, points);
    }

    private DirectedPathOption option(String id, List<String> assetIds) {
        DirectedPathOption.Section section = new DirectedPathOption.Section("base", null, null, 0, 2);
        PathAdmissionCertificate certificate = new PathAdmissionCertificate(
                PathAdmissionCertificate.Level.COMPLETE_PHYSICAL_PATH,
                PathAdmissionCertificate.Status.VERIFIED_ALLOWED, "official", "rules-1",
                "source-1", 100, PathAdmissionCertificate.Direction.FORWARD, "normal",
                List.of(section.getSignature()), List.of("forbidden_clearance"),
                "checker-1", "verified");
        return new DirectedPathOption(id, "root", "terminal",
                PathAdmissionCertificate.Direction.FORWARD, "normal",
                points(0, 0, 5_000, 0, 10_000, 0), assetIds, List.of(section),
                5_000, 5_000, new DirectedPathOption.Provenance(
                        "bounded-router", "router-1", "source-1", "window-1"), List.of(certificate));
    }

    private static List<CatalogMetricPoint> points(long... coordinates) {
        if (coordinates.length % 2 != 0) throw new IllegalArgumentException("Coordinate pairs required");
        java.util.ArrayList<CatalogMetricPoint> result = new java.util.ArrayList<>();
        for (int index = 0; index < coordinates.length; index += 2) {
            result.add(new CatalogMetricPoint(coordinates[index], coordinates[index + 1]));
        }
        return result;
    }
}
