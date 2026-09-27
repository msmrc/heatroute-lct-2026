package ru.lct.heatroute.domain.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.depth.OfficialSpecialSectionIntervals;
import ru.lct.heatroute.domain.economics.OfficialVariantEconomicsCalculator;
import ru.lct.heatroute.domain.engineering.OfficialEconomics;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.reconstruction.ExistingNetworkReconstructionResult;
import ru.lct.heatroute.domain.routing.RouteConnection;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.routing.RouteNode;
import ru.lct.heatroute.domain.routing.RouteSection;
import ru.lct.heatroute.domain.routing.RouteVariant;
import ru.lct.heatroute.domain.run.OfficialRunParameters;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class SavedSpecialSourceIdentityTest {
    static final ObjectMapper mapper =
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    static final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    static final OfficialEconomics economics = new OfficialEconomics();
    static final OfficialVariantEconomicsCalculator costs =
            new OfficialVariantEconomicsCalculator(pipes, economics);
    static final OfficialGeoJsonExporter exporter =
            new OfficialGeoJsonExporter(
                    mapper, pipes, economics, new OfficialOutputContractValidator(), costs);
    static final OfficialRunParameters parameters =
            new OfficialRunParameters(new BigDecimal(".7"), BigDecimal.TEN, false);

    static RouteCoordinate xy(double x, double y) {
        return new RouteCoordinate(500000 + x, 6100000 + y);
    }

    static ImportedOfficialFeature source(String id, String type, String wkt) throws Exception {
        ObjectNode attrs = mapper.createObjectNode();
        if (type.equals("heat_network")) attrs.put("diameter", 50);
        else attrs.put("restriction_type", type);
        return new ImportedOfficialFeature(
                id,
                type.equals("heat_network") ? type : "restriction",
                attrs,
                new WKTReader().read(wkt));
    }

    @org.junit.jupiter.api.Test
    void sourceIdentityAndEmittedGeometryRequireIndependentAdmission() throws Exception {
        for (double second : new double[] {.04, .06}) {
            List<ImportedOfficialFeature> features =
                    List.of(
                            source(
                                    "heat-root",
                                    "heat_network",
                                    "LINESTRING (500000 6099990,500000 6100010,"
                                            + (500000 + second)
                                            + " 6100010,"
                                            + (500000 + second)
                                            + " 6099990)"));
            RouteEdge edge = edge(List.of(xy(0, 0), xy(100, 0)));
            var spans =
                    new OfficialSpecialSectionIntervals(pipes)
                            .extract(List.of(edge), features, Map.of("root", Set.of("heat-root")));
            org.assertj.core.api.Assertions.assertThat(spans.get("edge")).hasSize(1);
            write("SECOND_HEAT_CROSSING " + second, edge, features);
        }
        List<ImportedOfficialFeature> features =
                List.of(
                        source(
                                "heat-root",
                                "heat_network",
                                "LINESTRING (500000 6099990,500000 6100010)"),
                        source(
                                "power",
                                "power_cable",
                                "LINESTRING (500050 6100000.0005,500050 6100010)"));
        write(
                "EMITTED_UTILITY_CROSSING",
                edge(List.of(xy(0, 0), xy(50, .001), xy(100, 0))),
                features);
        List<ImportedOfficialFeature> reversedSources =
                List.of(
                        source(
                                "heat-root",
                                "heat_network",
                                "LINESTRING (500100 6099990,500100 6100010)"),
                        source("z", "power_cable", "LINESTRING (500049 6099990,500049 6100010)"),
                        source("x", "power_cable", "LINESTRING (500050 6099990,500050 6100010)"));
        List<RouteSection> reversedSections =
                List.of(
                        section("base", null, null, 100, 52),
                        section("special", "power_cable", "x", 52, 51),
                        section("special", "power_cable", "z+x", 51, 48),
                        section("special", "power_cable", "z", 48, 47),
                        section("base", null, null, 47, 0));
        RouteEdge reversed =
                new RouteEdge(
                        "edge",
                        "root",
                        "end",
                        100,
                        List.of(xy(100, 0), xy(0, 0)),
                        reversedSections,
                        new BigDecimal("2"),
                        50);
        write("REVERSED_ORDERED_SOURCE_IDS", reversed, reversedSources);
        List<ImportedOfficialFeature> collision =
                List.of(
                        source(
                                "heat-root",
                                "heat_network",
                                "LINESTRING (500000 6099990,500000 6100010)"),
                        source("a+b", "power_cable", "LINESTRING (500049 6099990,500049 6100010)"),
                        source("a", "power_cable", "LINESTRING (500053 6099990,500053 6100010)"),
                        source("b", "power_cable", "LINESTRING (500053 6099990,500053 6100010)"));
        RouteEdge mergedCollision =
                new RouteEdge(
                        "edge",
                        "root",
                        "end",
                        100,
                        List.of(xy(0, 0), xy(100, 0)),
                        List.of(
                                section("base", null, null, 0, 47),
                                section("special", "power_cable", "a+b", 47, 55),
                                section("base", null, null, 55, 100)),
                        new BigDecimal("2"),
                        50);
        write("COLLISION_WITHOUT_ACTIVE_SET_BOUNDARY", mergedCollision, collision);
        RouteEdge splitCollision =
                new RouteEdge(
                        "edge",
                        "root",
                        "end",
                        100,
                        List.of(xy(0, 0), xy(100, 0)),
                        List.of(
                                section("base", null, null, 0, 47),
                                section("special", "power_cable", "a+b", 47, 51),
                                section("special", "power_cable", "a+b", 51, 55),
                                section("base", null, null, 55, 100)),
                        new BigDecimal("2"),
                        50);
        write("COLLISION_WITH_LEGAL_BOUNDARY", splitCollision, collision);
    }

    @org.junit.jupiter.api.Test
    void onlyPointContactCanBeExemptAtSelectedHeatRoot() throws Exception {
        RouteEdge edge = edge(List.of(xy(0, 0), xy(100, 0)));
        for (String wkt :
                List.of(
                        "LINESTRING (500000 6099990,500000 6100010)",
                        "LINESTRING (500000 6100000,500000.04 6100000)",
                        "LINESTRING (500099.96 6100000,500100 6100000)")) {
            if (wkt.contains("6099990")) {
                var spans = new OfficialSpecialSectionIntervals(pipes).extract(
                        List.of(edge), List.of(source("heat-root", "heat_network", wkt)),
                        Map.of("root", Set.of("heat-root"), "end", Set.of("heat-root")));
                org.assertj.core.api.Assertions.assertThat(spans.get("edge")).isEmpty();
            } else {
                org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new OfficialSpecialSectionIntervals(pipes).extract(
                                List.of(edge), List.of(source("heat-root", "heat_network", wkt)),
                                Map.of("root", Set.of("heat-root"), "end", Set.of("heat-root"))))
                        .hasMessageContaining("SPECIAL_SECTION_CROSSING_ANGLE");
            }
        }
    }

    @org.junit.jupiter.api.Test
    void roundedRootContactCannotHideAnotherSegmentOfSameSource() throws Exception {
        List<ImportedOfficialFeature> missedLocal =
                List.of(
                        source(
                                "heat-root",
                                "heat_network",
                                "LINESTRING (500000.04 6100010,500000.04 6099990,500000 6099990,500000 6100000.0005)"));
        List<RouteCoordinate> nearRoot = List.of(xy(0, .001), xy(100, .001));
        RouteEdge roundedContact =
                new RouteEdge(
                        "edge",
                        "root",
                        "end",
                        100,
                        nearRoot,
                        List.of(new RouteSection("base", null, null, nearRoot, 100, null)),
                        new BigDecimal("2"),
                        50);
        write("FIRST_EVENT_IS_DISTINCT_AFTER_ROUNDED_TIE", roundedContact, missedLocal);
        List<ImportedOfficialFeature> roundedLocal =
                List.of(
                        source(
                                "heat-root",
                                "heat_network",
                                "LINESTRING (500000.0005 6099990,500000.0005 6100010)"));
        write("ROUNDED_LOCAL_CONTACT", edge(List.of(xy(0, 0), xy(100, 0))), roundedLocal);
    }

    @org.junit.jupiter.api.Test
    void reversedProducerOrderUsesOppositeSpanEndsForDifferentWidths() throws Exception {
        List<ImportedOfficialFeature> features =
                List.of(
                        source(
                                "heat-root",
                                "heat_network",
                                "LINESTRING (500100 6099990,500100 6100010)"),
                        source(
                                "z",
                                "road",
                                "POLYGON ((500044 6099990,500056 6099990,500056 6100010,500044 6100010,500044 6099990))"),
                        source("a", "gas_pipeline", "LINESTRING (500050 6099990,500050 6100010)"),
                        source("b", "power_cable", "LINESTRING (500051 6099990,500051 6100010)"));
        List<RouteSection> sections =
                List.of(
                        section("base", null, null, 100, 59),
                        section("special", "road", "z", 59, 53),
                        section("special", "power_cable+road", "z+b", 53, 52),
                        section("special", "gas_pipeline+power_cable+road", "z+a+b", 52, 49),
                        section("special", "gas_pipeline+road", "z+a", 49, 48),
                        section("special", "road", "z", 48, 41),
                        section("base", null, null, 41, 0));
        RouteEdge edge =
                new RouteEdge(
                        "edge",
                        "root",
                        "end",
                        100,
                        List.of(xy(100, 0), xy(0, 0)),
                        sections,
                        new BigDecimal("2"),
                        50);
        write("REVERSED_MIXED_WIDTH_SOURCE_IDS", edge, features);
    }

    static RouteEdge edge(List<RouteCoordinate> sectionPoints) {
        List<RouteCoordinate> original = List.of(xy(0, 0), xy(100, 0));
        return new RouteEdge(
                "edge",
                "root",
                "end",
                100,
                original,
                List.of(new RouteSection("base", null, null, sectionPoints, 100, null)),
                new BigDecimal("2"),
                50);
    }

    static RouteSection section(String kind, String type, String id, double start, double end) {
        return new RouteSection(
                kind, type, id, List.of(xy(start, 0), xy(end, 0)), Math.abs(end - start), null);
    }

    static void write(String label, RouteEdge edge, List<ImportedOfficialFeature> features)
            throws Exception {
        List<RouteNode> nodes =
                List.of(
                        new RouteNode(
                                "root",
                                "new_tie_in_chamber",
                                edge.getCoordinates().get(0),
                                true,
                                true,
                                2,
                                "heat-root",
                                50),
                        new RouteNode(
                                "end",
                                "demand_connection",
                                edge.getCoordinates().get(edge.getCoordinates().size() - 1),
                                false,
                                false,
                                0,
                                null));
        List<RouteConnection> connections =
                List.of(
                        new RouteConnection(
                                "demand", "end", new BigDecimal("2"), "connected", null));
        var reconstruction = ExistingNetworkReconstructionResult.empty();
        var variant =
                new RouteVariant(
                        "one",
                        "one",
                        nodes,
                        List.of(edge),
                        connections,
                        new BigDecimal("100"),
                        List.of(),
                        List.of(),
                        reconstruction,
                        costs.calculate(nodes, List.of(edge), connections, reconstruction),
                        1);
        ObjectNode saved = mapper.createObjectNode().put("input_profile", "baseline_input");
        saved.putArray("variants").add(mapper.valueToTree(variant));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean legal =
                label.equals("REVERSED_MIXED_WIDTH_SOURCE_IDS")
                        || label.equals("ROUNDED_LOCAL_CONTACT")
                        || label.equals("REVERSED_ORDERED_SOURCE_IDS")
                        || label.equals("COLLISION_WITH_LEGAL_BOUNDARY");
        if (legal) {
            exporter.writeValidated(saved, features, parameters, out);
            org.assertj.core.api.Assertions.assertThat(out.size()).as(label).isPositive();
        } else {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> exporter.writeValidated(saved, features, parameters, out))
                    .as(label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SPECIAL_SECTION");
            org.assertj.core.api.Assertions.assertThat(out.size()).as(label).isZero();
        }
    }
}
