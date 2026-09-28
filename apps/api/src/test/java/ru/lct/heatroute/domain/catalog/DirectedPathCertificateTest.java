package ru.lct.heatroute.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DirectedPathCertificateTest {
    @Test
    void admissionIsSpecificToDirectionContextDiameterRulesAndSource() {
        DirectedPathOption.Section section = new DirectedPathOption.Section("base", null, null, 0, 2);
        PathAdmissionCertificate allowed = certificate(PathAdmissionCertificate.Direction.FORWARD,
                "root-contact:a", 100, "rules-1", "source-1", section.getSignature());
        DirectedPathOption option = option("forward", PathAdmissionCertificate.Direction.FORWARD,
                "root-contact:a", "source-1", List.of(section), List.of(allowed));

        assertThat(option.admissionStatus(100, "official", "rules-1", "source-1"))
                .isEqualTo(PathAdmissionCertificate.Status.VERIFIED_ALLOWED);
        assertThat(option.admissionStatus(200, "official", "rules-1", "source-1"))
                .isEqualTo(PathAdmissionCertificate.Status.UNCHECKED);
        assertThat(option.admissionStatus(100, "official", "rules-2", "source-1"))
                .isEqualTo(PathAdmissionCertificate.Status.UNCHECKED);
        assertThat(option.admissionStatus(100, "official", "rules-1", "source-2"))
                .isEqualTo(PathAdmissionCertificate.Status.UNCHECKED);
    }

    @Test
    void reverseOrDifferentSectionCertificateCannotBeAttachedToForwardGeometry() {
        DirectedPathOption.Section section = new DirectedPathOption.Section("base", null, null, 0, 2);
        PathAdmissionCertificate reverse = certificate(PathAdmissionCertificate.Direction.REVERSE,
                "root-contact:a", 100, "rules-1", "source-1", section.getSignature());
        assertThatThrownBy(() -> option("forward", PathAdmissionCertificate.Direction.FORWARD,
                "root-contact:a", "source-1", List.of(section), List.of(reverse)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("context");

        PathAdmissionCertificate wrongSections = certificate(PathAdmissionCertificate.Direction.FORWARD,
                "root-contact:a", 100, "rules-1", "source-1", "special:road:r1:0:2");
        assertThatThrownBy(() -> option("forward", PathAdmissionCertificate.Direction.FORWARD,
                "root-contact:a", "source-1", List.of(section), List.of(wrongSections)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sections");
    }

    @Test
    void optionOwnsItsCollectionsAndSharedAssetsRemainExplicit() {
        DirectedPathOption.Section section = new DirectedPathOption.Section("base", null, null, 0, 2);
        ArrayList<CatalogMetricPoint> points = new ArrayList<>(List.of(
                new CatalogMetricPoint(0, 0), new CatalogMetricPoint(1_000, 0),
                new CatalogMetricPoint(1_000, 1_000)));
        ArrayList<String> assets = new ArrayList<>(List.of("asset:trunk", "asset:branch"));
        DirectedPathOption option = new DirectedPathOption("path-1", "root", "terminal",
                PathAdmissionCertificate.Direction.FORWARD, "normal",
                points, assets, List.of(section), 1_000, 1_000,
                provenance("source-1"), List.of(certificate(PathAdmissionCertificate.Direction.FORWARD,
                        "normal", 100, "rules-1", "source-1", section.getSignature())));
        points.clear();
        assets.clear();

        assertThat(option.getCoordinates()).hasSize(3);
        assertThat(option.getPhysicalAssetIds()).containsExactly("asset:trunk", "asset:branch");
        assertThat(option.getLengthMm()).isEqualTo(2_000L);
        assertThat(option.getFingerprint()).hasSize(64);
    }

    @Test
    void snapshotRejectsForeignRulesAndBuildResultReportsTruncationAsIncomplete() {
        DirectedPathOption.Section section = new DirectedPathOption.Section("base", null, null, 0, 2);
        DirectedPathOption foreignRules = option("path-1", PathAdmissionCertificate.Direction.FORWARD,
                "normal", "source-1", List.of(section), List.of(certificate(
                        PathAdmissionCertificate.Direction.FORWARD, "normal", 100,
                        "rules-2", "source-1", section.getSignature())));
        assertThatThrownBy(() -> new RoutingCatalogSnapshot(
                "source-1", "official", "rules-1", "catalog-1", List.of(foreignRules)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rule version");

        RoutingCatalogSnapshot empty = new RoutingCatalogSnapshot(
                "source-1", "official", "rules-1", "catalog-1", List.of());
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("paths", 0L);
        CatalogBuildResult result = new CatalogBuildResult(empty, counters,
                Map.of("bounded-router", false), List.of("terminal:17"), List.of("time_budget"));

        assertThat(result.getStatus()).isEqualTo(CatalogBuildResult.Status.CATALOG_INCOMPLETE);
        assertThat(result.isComplete()).isFalse();
        assertThat(result.getSnapshot().getPathOptions()).isEmpty();
    }

    private DirectedPathOption option(String id, PathAdmissionCertificate.Direction direction,
            String context, String sourceHash, List<DirectedPathOption.Section> sections,
            List<PathAdmissionCertificate> certificates) {
        return new DirectedPathOption(id, "root", "terminal", direction, context,
                List.of(new CatalogMetricPoint(0, 0), new CatalogMetricPoint(1_000, 0),
                        new CatalogMetricPoint(1_000, 1_000)),
                List.of("asset:trunk", "asset:branch"), sections, 1_000, 1_000,
                provenance(sourceHash), certificates);
    }

    private DirectedPathOption.Provenance provenance(String sourceHash) {
        return new DirectedPathOption.Provenance(
                "bounded-router", "router-1", sourceHash, "window:0:0:1000:1000");
    }

    private PathAdmissionCertificate certificate(PathAdmissionCertificate.Direction direction,
            String context, int diameter, String ruleVersion, String sourceHash, String sectionSignature) {
        return new PathAdmissionCertificate(PathAdmissionCertificate.Level.COMPLETE_PHYSICAL_PATH,
                PathAdmissionCertificate.Status.VERIFIED_ALLOWED, "official", ruleVersion,
                sourceHash, diameter, direction, context, List.of(sectionSignature),
                List.of("forbidden_clearance", "special_sections"), "checker-1", "verified");
    }
}
