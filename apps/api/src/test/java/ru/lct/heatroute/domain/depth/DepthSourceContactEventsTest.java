package ru.lct.heatroute.domain.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.AffineTransformation;
import java.util.concurrent.CancellationException;
import ru.lct.heatroute.domain.topology.ExistingSourceContact;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Врезка исключает только крайний точечный контакт, а не повторное пересечение того же объекта. */
class DepthSourceContactEventsTest {
    private final OfficialDepthCrossingExtractor extractor = new OfficialDepthCrossingExtractor(
            new OfficialConstraintCatalog(), new OfficialPipeCatalog());
    private final RouteEdge edge = new RouteEdge("edge", "root", "end", 100,
            List.of(new RouteCoordinate(0, 0), new RouteCoordinate(100, 0)),
            List.of(), new BigDecimal("2"), 50);

    @Test
    void actualUpstreamPointContactRemainsExempt() throws Exception {
        assertThat(extract("LINESTRING (0 -10, 0 10)", Set.of("heat"), Set.of()).getCrossings()).isEmpty();
    }

    @Test
    void actualDownstreamPointContactRemainsExempt() throws Exception {
        assertThat(extract("LINESTRING (100 -10, 100 10)", Set.of(), Set.of("heat")).getCrossings()).isEmpty();
    }

    @Test
    void secondSameSourceCrossingWithinSnapWindowStillRequiresDepthClearance() throws Exception {
        DepthCrossingExtraction result = extract("LINESTRING (0 -10, 0 10, .04 10, .04 -10)", Set.of("heat"), Set.of());
        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getCrossings()).hasSize(1);
        assertThat(result.getCrossings().get(0).getStationM()).isEqualByComparingTo("0.040");
        assertThat(result.getCrossings().get(0).getMinimumVerticalClearanceM()).isEqualByComparingTo("0.5");
    }

    @Test
    void precedingSameSourceCrossingBeforeEndContactIsNotExempt() throws Exception {
        DepthCrossingExtraction result = extract("LINESTRING (99.96 -10, 99.96 10, 100 10, 100 -10)", Set.of(), Set.of("heat"));
        assertThat(result.getCrossings()).hasSize(1);
        assertThat(result.getCrossings().get(0).getStationM()).isEqualByComparingTo("99.960");
    }

    @Test
    void bothEndContactsDoNotHideEitherInteriorCrossing() throws Exception {
        DepthCrossingExtraction result = extract("LINESTRING (0 -10, 0 10, .04 10, .04 -10, 99.96 -10, 99.96 10, 100 10, 100 -10)",
                Set.of("heat"), Set.of("heat"));
        assertThat(result.getCrossings()).hasSize(2);
        assertThat(result.getCrossings().get(0).getStationM()).isEqualByComparingTo("0.040");
        assertThat(result.getCrossings().get(1).getStationM()).isEqualByComparingTo("99.960");
    }

    @Test
    void anotherSourceIdentifierCannotCreateContactPrivilege() throws Exception {
        assertThat(extract("LINESTRING (0 -10, 0 10, .04 10, .04 -10)", Set.of("other"), Set.of())
                .getCrossings()).hasSize(2);
    }

    @Test
    void extendedFirstOverlapIsNotATieInPoint() throws Exception {
        // Негатив самостоятельного extractor: полный маршрут дополнительно ограничен нормалями камеры.
        DepthCrossingExtraction result = extract("LINESTRING (0 -10, 0 0, 10 0, 10 10)", Set.of("heat"), Set.of());
        assertThat(result.getCrossings()).hasSize(1);
        assertThat(result.getCrossings().get(0).getStationM()).isEqualByComparingTo("5");
    }

    @Test
    void extendedLastOverlapIsNotATieInPoint() throws Exception {
        DepthCrossingExtraction result = extract("LINESTRING (90 -10, 90 0, 100 0, 100 10)", Set.of(), Set.of("heat"));
        assertThat(result.getCrossings()).hasSize(1);
        assertThat(result.getCrossings().get(0).getStationM()).isEqualByComparingTo("95");
    }


    @Test
    void firstActualHitOnDistantSourceBranchIsNotTheRoundedTieIn() throws Exception {
        RouteEdge rounded = new RouteEdge("edge", "root", "end", 100,
                List.of(new RouteCoordinate(0, .001), new RouteCoordinate(100, .001)),
                List.of(), new BigDecimal("2"), 50);
        ImportedOfficialFeature source = source("LINESTRING (.04 10, .04 -10, 0 -10, 0 .0005)");
        DepthCrossingExtraction result = extractor.extractPhysical(rounded, List.of(source), Set.of("heat"), Set.of());
        assertThat(result.getCrossings()).hasSize(1);
        assertThat(result.getCrossings().get(0).getStationM()).isEqualByComparingTo(".040");
    }

    @Test
    void roundedLocalPointOnNearestSegmentRemainsExempt() throws Exception {
        assertThat(extract("LINESTRING (.0005 -10, .0005 10)", Set.of("heat"), Set.of()).getCrossings()).isEmpty();
    }

    @Test
    void reversedPhysicalDirectionKeepsTheInteriorEvent() throws Exception {
        RouteEdge reversed = new RouteEdge("edge", "end", "root", 100,
                List.of(new RouteCoordinate(100, 0), new RouteCoordinate(0, 0)),
                List.of(), new BigDecimal("2"), 50);
        DepthCrossingExtraction result = extractor.extractPhysical(reversed,
                List.of(source("LINESTRING (100 -10, 100 10, 99.96 10, 99.96 -10)")), Set.of("heat"), Set.of());
        assertThat(result.getCrossings()).hasSize(1);
        assertThat(result.getCrossings().get(0).getStationM()).isEqualByComparingTo(".040");
    }

    @Test
    void nearestSourceSegmentRuleIsRotationAndUtmTranslationInvariant() throws Exception {
        Geometry local = new WKTReader().read("LINESTRING (.0005 -10, .0005 10)");
        Geometry loop = new WKTReader().read("LINESTRING (.04 10, .04 -10, 0 -10, 0 .0005)");
        for (int degrees : new int[] {0, 17, 45, 90, 137, 179}) {
            AffineTransformation transform = AffineTransformation.rotationInstance(Math.toRadians(degrees));
            transform.translate(500000, 6100000);
            Coordinate endpoint = transform.transform(new Coordinate(0, 0), new Coordinate());
            Coordinate hit = transform.transform(new Coordinate(.0005, 0), new Coordinate());
            assertThat(ExistingSourceContact.liesOnNearestSegment(transform.transform(local), endpoint, hit)).isTrue();
            Coordinate roundedEndpoint = transform.transform(new Coordinate(0, .001), new Coordinate());
            Coordinate distantHit = transform.transform(new Coordinate(.04, .001), new Coordinate());
            assertThat(ExistingSourceContact.liesOnNearestSegment(transform.transform(loop), roundedEndpoint, distantHit)).isFalse();
        }
    }

    @Test
    void separateLineComponentsDoNotGainAnArtificialConnectingSegment() throws Exception {
        Geometry source = new WKTReader().read("MULTILINESTRING ((0 -10, 0 .0005),(.04 -10,.04 10))");
        assertThat(ExistingSourceContact.liesOnNearestSegment(source, new Coordinate(0, .001), new Coordinate(.04, .001))).isFalse();
        assertThat(ExistingSourceContact.liesOnNearestSegment(source, new Coordinate(.0005, .001), new Coordinate(0, .0005))).isTrue();
    }

    @Test
    void nonFiniteContactAndCancellationRemainExplicit() throws Exception {
        Geometry source = new WKTReader().read("LINESTRING (0 0,0 10)");
        assertThatThrownBy(() -> ExistingSourceContact.liesOnNearestSegment(source,
                new Coordinate(Double.NaN, 0), new Coordinate(0, 0))).isInstanceOf(IllegalArgumentException.class);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> ExistingSourceContact.liesOnNearestSegment(source,
                    new Coordinate(0, 0), new Coordinate(0, 0))).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private ImportedOfficialFeature source(String wkt) throws Exception {
        return new ImportedOfficialFeature("heat", "heat_network",
                new ObjectMapper().createObjectNode().put("diameter", 50), new WKTReader().read(wkt));
    }

    private DepthCrossingExtraction extract(String wkt, Set<String> upstream, Set<String> downstream) throws Exception {
        ImportedOfficialFeature source = new ImportedOfficialFeature("heat", "heat_network",
                new ObjectMapper().createObjectNode().put("diameter", 50), new WKTReader().read(wkt));
        return extractor.extractPhysical(edge, List.of(source), upstream, downstream);
    }
}
