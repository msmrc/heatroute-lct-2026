package ru.lct.heatroute.domain.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.depth.OfficialDepthCrossingExtractor;
import ru.lct.heatroute.domain.depth.OfficialDepthOptimizer;
import ru.lct.heatroute.domain.depth.OfficialDepthPlanner;
import ru.lct.heatroute.domain.depth.OfficialDepthProfileValidator;
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

/**
 * Денежные секции сверяются с источниками независимо от сохранённой самосогласованной стоимости.
 */
class SavedSpecialCoverageTest {
    private final ObjectMapper mapper =
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final OfficialPipeCatalog pipes = new OfficialPipeCatalog();
    private final OfficialEconomics economics = new OfficialEconomics();
    private final OfficialVariantEconomicsCalculator costs =
            new OfficialVariantEconomicsCalculator(pipes, economics);
    private final OfficialGeoJsonExporter exporter =
            new OfficialGeoJsonExporter(
                    mapper, pipes, economics, new OfficialOutputContractValidator(), costs);
    private final OfficialDepthPlanner depths =
            new OfficialDepthPlanner(
                    new OfficialDepthCrossingExtractor(new OfficialConstraintCatalog(), pipes),
                    new OfficialDepthOptimizer(pipes, economics),
                    new OfficialDepthProfileValidator(pipes));
    private final OfficialRunParameters parameters =
            new OfficialRunParameters(new BigDecimal(".7"), BigDecimal.TEN, true);

    @Test
    void completeSpecialAcrossTechnicalSplitExports() throws Exception {
        Fixture fixture = fixture("power", 50);
        assertThat(write(fixture)).isPositive();
    }

    @Test
    void opaqueIdContainingPlusExportsAsAWholeReference() throws Exception {
        assertThat(write(fixture("power+line", 50))).isPositive();
    }

    @Test
    void roundedOuterBoundariesRemainLegal() throws Exception {
        assertThat(write(fixture("power", 50.0004))).isPositive();
    }

    @Test
    void repeatedOneMillimetreBaseGapsCannotAccumulateTolerance() throws Exception {
        Fixture f = fixture("power", 50);
        List<RouteSection> sections = new ArrayList<>();
        for (int mm = 0; mm < 1000; mm += 4) {
            sections.add(
                    section(
                            "special",
                            "power_cable",
                            "power",
                            51 + mm / 1000.0,
                            51 + (mm + 3) / 1000.0));
            sections.add(
                    section("base", null, null, 51 + (mm + 3) / 1000.0, 51 + (mm + 4) / 1000.0));
        }
        sections.add(section("base", null, null, 52, 100));
        f.replaceRight(sections);
        rejected(f);
    }

    @Test
    void erasedSpecialWithCheaperConsistentEconomicsFails() throws Exception {
        Fixture f = fixture("power", 50);
        f.replaceRight(List.of(section("base", null, null, 51, 100)));
        rejected(f);
    }

    @Test
    void unknownSourceReferenceFailsBeforeBytes() throws Exception {
        Fixture f = fixture("power", 50);
        f.replaceRight(
                List.of(
                        section("special", "power_cable", "absent", 51, 52),
                        section("base", null, null, 52, 100)));
        rejected(f);
    }

    @Test
    void unknownReferenceAddedToKnownSourceFailsBeforeBytes() throws Exception {
        Fixture f = fixture("power", 50);
        f.replaceRight(
                List.of(
                        section("special", "power_cable", "power+not-in-input", 51, 52),
                        section("base", null, null, 52, 100)));
        rejected(f);
    }

    @Test
    void cheaperWrongSpecialTypeCannotReplacePower() throws Exception {
        Fixture f = fixture("power", 50);
        f.replaceRight(
                List.of(
                        section("special", "heat_network", "power", 51, 52),
                        section("base", null, null, 52, 100)));
        rejected(f);
    }

    @Test
    void opaqueRoadReferenceRetainsQ4GeometryAndCoverage() throws Exception {
        assertThat(write(roadFixture("road+main", false))).isPositive();
    }

    @Test
    void overlappingRoadAndTramUseActualUnionAndMaximumMultiplier() throws Exception {
        Fixture fixture = roadFixture("road", true);
        assertThat(write(fixture)).isPositive();
        List<RouteSection> sections = new ArrayList<>();
        for (RouteSection section : fixture.edges.get(1).getSections()) {
            if ("road+tram_tracks".equals(section.getRestrictionType())) {
                sections.add(
                        new RouteSection(
                                "special",
                                "road",
                                "road",
                                section.getCoordinates(),
                                section.getLengthM().doubleValue(),
                                null));
            } else sections.add(section);
        }
        fixture.replaceRight(sections);
        rejected(fixture);
    }

    @Test
    void unknownRoadReferenceDoesNotGetCoveragePrivilege() throws Exception {
        Fixture fixture = roadFixture("road", false);
        List<RouteSection> sections = new ArrayList<>();
        for (RouteSection section : fixture.edges.get(1).getSections()) {
            sections.add(
                    "road".equals(section.getRestrictionType())
                            ? new RouteSection(
                                    "special",
                                    "road",
                                    "absent",
                                    section.getCoordinates(),
                                    section.getLengthM().doubleValue(),
                                    null)
                            : section);
        }
        fixture.replaceRight(sections);
        rejected(fixture);
    }

    @Test
    void duplicatedKnownReferenceDoesNotCreateAnotherSource() throws Exception {
        Fixture f = fixture("power", 50);
        f.replaceRight(
                List.of(
                        section("special", "power_cable", "power+power", 51, 52),
                        section("base", null, null, 52, 100)));
        rejected(f);
    }

    @Test
    void disabledDepthStillChecksUtilityMonetaryCoverage() throws Exception {
        Fixture f = fixture("power", 50);
        for (int i = 0; i < f.edges.size(); i++) {
            RouteEdge e = f.edges.get(i);
            f.edges.set(
                    i,
                    new RouteEdge(
                            e.getId(),
                            e.getUpstreamNodeId(),
                            e.getDownstreamNodeId(),
                            e.getLengthM().doubleValue(),
                            e.getCoordinates(),
                            e.getSections(),
                            e.getFlowTph(),
                            e.getDiameter()));
        }
        OfficialRunParameters planar =
                new OfficialRunParameters(new BigDecimal(".7"), BigDecimal.TEN, false);
        ByteArrayOutputStream positive = new ByteArrayOutputStream();
        exporter.writeValidated(f.json(), f.sources, planar, positive);
        assertThat(positive.size()).isPositive();
        f.replaceRight(List.of(section("base", null, null, 51, 100)));
        ByteArrayOutputStream rejected = new ByteArrayOutputStream();
        assertThatThrownBy(() -> exporter.writeValidated(f.json(), f.sources, planar, rejected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPECIAL_SECTION");
        assertThat(rejected.size()).isZero();
    }

    @Test
    void oneBadLaterVariantPreventsAllOutput() throws Exception {
        Fixture good = fixture("power", 50), bad = fixture("power", 50);
        bad.replaceRight(List.of(section("base", null, null, 51, 100)));
        ObjectNode saved = good.json();
        ObjectNode second = (ObjectNode) bad.json().path("variants").path(0);
        second.put("id", "second").put("rank", 2);
        ((com.fasterxml.jackson.databind.node.ArrayNode) saved.path("variants")).add(second);
        ByteArrayOutputStream rejected = new ByteArrayOutputStream();
        assertThatThrownBy(() -> exporter.writeValidated(saved, good.sources, parameters, rejected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPECIAL_SECTION");
        assertThat(rejected.size()).isZero();
    }

    @Test
    void cancellationEscapesPreflightBeforeBytes() throws Exception {
        Fixture f = fixture("power", 50);
        ObjectNode saved = f.json();
        ByteArrayOutputStream rejected = new ByteArrayOutputStream();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(
                            () -> exporter.writeValidated(saved, f.sources, parameters, rejected))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(rejected.size()).isZero();
        } finally {
            Thread.interrupted();
        }
    }

    private Fixture roadFixture(String roadId, boolean tram) throws Exception {
        Fixture base = fixture("power", 50);
        List<ImportedOfficialFeature> sources = new ArrayList<>(base.sources);
        sources.add(
                new ImportedOfficialFeature(
                        roadId,
                        "restriction",
                        mapper.createObjectNode().put("restriction_type", "road"),
                        new WKTReader()
                                .read(
                                        "POLYGON ((500065 6099990,500071 6099990,500071 6100010,500065 6100010,500065 6099990))")));
        if (tram)
            sources.add(
                    new ImportedOfficialFeature(
                            "tram",
                            "restriction",
                            mapper.createObjectNode().put("restriction_type", "tram_tracks"),
                            new WKTReader()
                                    .read(
                                            "POLYGON ((500067 6099990,500069 6099990,500069 6100010,500067 6100010,500067 6099990))")));
        Fixture result = new Fixture(base.edges, sources);
        List<RouteSection> sections = new ArrayList<>();
        sections.add(section("special", "power_cable", "power", 51, 52));
        sections.add(section("base", null, null, 52, 62));
        if (tram) {
            sections.add(section("special", "road", roadId, 62, 64));
            sections.add(section("special", "road+tram_tracks", roadId + "+tram", 64, 72));
            sections.add(section("special", "road", roadId, 72, 74));
        } else sections.add(section("special", "road", roadId, 62, 74));
        sections.add(section("base", null, null, 74, 100));
        result.replaceRight(sections);
        return result;
    }

    private Fixture fixture(String id, double station) throws Exception {
        ImportedOfficialFeature source =
                new ImportedOfficialFeature(
                        id,
                        "restriction",
                        mapper.createObjectNode().put("restriction_type", "power_cable"),
                        new WKTReader()
                                .read(
                                        "LINESTRING ("
                                                + (500000 + station)
                                                + " 6099990,"
                                                + (500000 + station)
                                                + " 6100010)"));
        List<ImportedOfficialFeature> sources = List.of(source);
        List<RouteEdge> edges =
                new ArrayList<>(
                        depths.planNetwork(
                                List.of(
                                        edge("left", "start", "joint", 0, 51),
                                        edge("right", "joint", "end", 51, 100)),
                                sources,
                                parameters.getMinimumDepthM(),
                                parameters.getMaximumDepthM(),
                                Map.of(),
                                Map.of()));
        // Ожидаемые границы: физическая станция источника ±2 м; округляются только сохраняемые XY.
        for (int i = 0; i < edges.size(); i++) {
            double start = i == 0 ? 0 : 51, end = i == 0 ? 51 : 100;
            List<RouteSection> sections = new ArrayList<>();
            if (start < station - 2)
                sections.add(section("base", null, null, start, Math.min(end, station - 2)));
            sections.add(
                    section(
                            "special",
                            "power_cable",
                            id,
                            Math.max(start, station - 2),
                            Math.min(end, station + 2)));
            if (end > station + 2)
                sections.add(section("base", null, null, Math.max(start, station + 2), end));
            edges.set(i, withSections(edges.get(i), sections));
        }
        return new Fixture(edges, sources);
    }

    private int write(Fixture fixture) throws Exception {
        JsonNode reloaded = mapper.readTree(mapper.writeValueAsBytes(fixture.json()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        exporter.writeValidated(reloaded, fixture.sources, parameters, bytes);
        return bytes.size();
    }

    private void rejected(Fixture fixture) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThatThrownBy(
                        () ->
                                exporter.writeValidated(
                                        fixture.json(), fixture.sources, parameters, bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPECIAL_SECTION");
        assertThat(bytes.size()).isZero();
    }

    private RouteEdge edge(String id, String from, String to, double start, double end) {
        return new RouteEdge(
                id,
                from,
                to,
                end - start,
                List.of(xy(start), xy(end)),
                List.of(),
                new BigDecimal("2"),
                50);
    }

    private RouteEdge withSections(RouteEdge e, List<RouteSection> sections) {
        return new RouteEdge(
                e.getId(),
                e.getUpstreamNodeId(),
                e.getDownstreamNodeId(),
                e.getLengthM().doubleValue(),
                e.getCoordinates(),
                sections,
                e.getFlowTph(),
                e.getDiameter(),
                e.getDepthProfile());
    }

    private RouteSection section(String kind, String type, String id, double start, double end) {
        return new RouteSection(kind, type, id, List.of(xy(start), xy(end)), end - start, null);
    }

    private RouteCoordinate xy(double station) {
        return new RouteCoordinate(500000 + station, 6100000);
    }

    private final class Fixture {
        final List<RouteEdge> edges;
        final List<ImportedOfficialFeature> sources;

        Fixture(List<RouteEdge> edges, List<ImportedOfficialFeature> sources) {
            this.edges = edges;
            this.sources = sources;
        }

        void replaceRight(List<RouteSection> sections) {
            edges.set(1, withSections(edges.get(1), sections));
        }

        ObjectNode json() {
            List<RouteNode> nodes =
                    List.of(
                            new RouteNode(
                                    "start", "new_branch_chamber", xy(0), true, true, 0, null),
                            new RouteNode("joint", "technical_node", xy(51), false, false, 0, null),
                            new RouteNode(
                                    "end", "demand_connection", xy(100), false, false, 0, null));
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
                            edges,
                            connections,
                            new BigDecimal("100"),
                            List.of(),
                            List.of(),
                            reconstruction,
                            costs.calculate(nodes, edges, connections, reconstruction),
                            1);
            ObjectNode json = mapper.createObjectNode().put("input_profile", "baseline_input");
            json.putArray("variants").add(mapper.valueToTree(variant));
            return json;
        }
    }
}
