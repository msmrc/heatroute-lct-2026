package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class CatalogEdgeSectionAssemblerFactoryTest {
    @Test
    void classifiesValidSpecialCrossingsAndLeavesInvalidHybridGeometryForExactRejection()
            throws Exception {
        OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
                new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
        ImportedOfficialFeature road = new ImportedOfficialFeature(
                "road-1", "restriction",
                new ObjectMapper().readTree("{\"restriction_type\":\"road\"}"),
                new WKTReader().read(
                        "POLYGON ((40 -10, 60 -10, 60 10, 40 10, 40 -10))"));
        CatalogEdgeSectionAssemblerFactory.PreparedAssembler assembler =
                new CatalogEdgeSectionAssemblerFactory(
                        new OfficialObstacleRouter(rules)).prepare(List.of(road));

        List<RouteSection> valid = assembler.sections(edge(List.of(
                point(0, 0), point(100, 0))));
        List<RouteSection> invalidTurn = assembler.sections(edge(List.of(
                point(0, 0), point(50, 0), point(50, 30))));

        assertThat(valid).filteredOn(section -> "special".equals(section.getKind()))
                .singleElement().satisfies(section -> {
                    assertThat(section.getRestrictionType()).isEqualTo("road");
                    assertThat(section.getRestrictionId()).isEqualTo("road-1");
                });
        assertThat(invalidTurn).singleElement().satisfies(section ->
                assertThat(section.getKind()).isEqualTo("base"));
        assertThat(assembler.getAssemblyCalls()).isEqualTo(2);
        assertThat(assembler.getFallbackAssemblies()).isEqualTo(1);
    }

    private static CatalogFrozenCandidateAssembler.EdgeAssembly edge(
            List<RouteCoordinate> coordinates) {
        return new CatalogFrozenCandidateAssembler.EdgeAssembly(
                "edge", List.of("arc"), List.of("asset"), coordinates, 50, null);
    }

    private static RouteCoordinate point(double x, double y) {
        return new RouteCoordinate(x, y);
    }
}
