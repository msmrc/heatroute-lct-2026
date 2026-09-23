package ru.lct.heatroute.domain.topology;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

class ExistingNetworkTopologyAnalyzerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WKTReader wktReader = new WKTReader();
    private final ExistingNetworkTopologyAnalyzer analyzer = new ExistingNetworkTopologyAnalyzer();
    private final NetworkSegmentSplitter splitter = new NetworkSegmentSplitter();

    @Test
    void validatesChainToSourceAndCreatesLineTieInCandidate() throws Exception {
        TopologyAnalysis result = analyzer.analyze(List.of(
                feature("source", "src", "POINT (0 0)", "{}"),
                feature("heat_network", "net", "LINESTRING (0 0, 100 0)", upstream("src")),
                feature("heat_chamber", "ch", "POINT (100 0)", upstream("net")),
                feature("oks_connection_point", "cp", "POINT (50 50)", "{\"oks_id\":\"oks\"}")));

        assertThat(result.isValid()).isTrue();
        assertThat(result.getSourceCount()).isEqualTo(1);
        assertThat(result.getTieInCandidates()).hasSize(1);
        TieInCandidate candidate = result.getTieInCandidates().get(0);
        assertThat(candidate.getTargetId()).isEqualTo("net");
        assertThat(candidate.getTargetType()).isEqualTo("heat_network");
        assertThat(candidate.isNewChamberRequired()).isTrue();
        assertThat(candidate.getDistanceM()).isEqualByComparingTo("50.000");
    }

    @Test
    void detectsCycleAndAmbiguousInteriorCrossing() throws Exception {
        TopologyAnalysis result = analyzer.analyze(List.of(
                feature("source", "src", "POINT (0 0)", "{}"),
                feature("heat_network", "a", "LINESTRING (0 0, 10 10)", upstream("b")),
                feature("heat_network", "b", "LINESTRING (0 10, 10 0)", upstream("a"))));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getIssues()).extracting(TopologyIssue::getCode)
                .contains("UPSTREAM_CYCLE", "AMBIGUOUS_NETWORK_INTERSECTION");
    }

    @Test
    void infersConnectivityForProvidedDatasetProfileWithoutUpstreamLinks() throws Exception {
        TopologyAnalysis result = analyzer.analyze(List.of(
                feature("source", "5", "POINT (0 0.1)", "{}"),
                feature("heat_network", "4", "LINESTRING (0 0, 100 0)", "{\"diameter\":500}"),
                feature("heat_network", "6", "LINESTRING (100 0, 150 40)", "{\"diameter\":400}"),
                feature("heat_chamber", "3", "POINT (100 0)", "{}"),
                feature("oks_connection_point", "1", "POINT (120 50)", "{\"flow_tph\":24.87}")));

        assertThat(result.isValid()).isTrue();
        assertThat(result.getTieInCandidates()).isNotEmpty();
        assertThat(result.getTieInCandidates())
                .anySatisfy(candidate -> assertThat(candidate.getTargetType())
                        .isEqualTo("heat_chamber"));
        assertThat(result.getTieInCandidates())
                .anySatisfy(candidate -> assertThat(candidate.getTargetType())
                        .isEqualTo("heat_network"));
        assertThat(result.getIssues()).isEmpty();
    }

    @Test
    void reportsDisconnectedGeometryWhenUpstreamLinksAreUnavailable() throws Exception {
        TopologyAnalysis result = analyzer.analyze(List.of(
                feature("source", "src", "POINT (0 0)", "{}"),
                feature("heat_network", "connected", "LINESTRING (0 0, 10 0)", "{}"),
                feature("heat_network", "island", "LINESTRING (50 0, 60 0)", "{}")));

        assertThat(result.getIssues()).extracting(TopologyIssue::getCode)
                .contains("GEOMETRIC_NETWORK_DISCONNECTED");
    }

    @Test
    void reusesNearbyChamberOnlyWhileFourthIncidentIsStillAvailable() throws Exception {
        TopologyAnalysis reusable = analyzer.analyze(List.of(
                feature("source", "src", "POINT (0 0)", "{}"),
                feature("heat_network", "net", "LINESTRING (0 0, 50 0)", upstream("src")),
                feature("heat_chamber", "ch", "POINT (50 0)", upstream("net")),
                feature("oks_connection_point", "cp", "POINT (50 20)", "{\"oks_id\":\"oks\"}")));

        assertThat(reusable.getTieInCandidates())
                .filteredOn(candidate -> "ch".equals(candidate.getTargetId()))
                .singleElement().satisfies(candidate -> {
            assertThat(candidate.getTargetId()).isEqualTo("ch");
            assertThat(candidate.isNewChamberRequired()).isFalse();
        });

        TopologyAnalysis full = analyzer.analyze(List.of(
                feature("source", "src", "POINT (-20 0)", "{}"),
                feature("heat_chamber", "ch", "POINT (0 0)", upstream("src")),
                feature("heat_network", "n1", "LINESTRING (0 0, 20 0)", upstream("src")),
                feature("heat_network", "n2", "LINESTRING (0 0, -20 0)", upstream("src")),
                feature("heat_network", "n3", "LINESTRING (0 0, 0 20)", upstream("src")),
                feature("heat_network", "n4", "LINESTRING (0 0, 0 -20)", upstream("src")),
                feature("oks_connection_point", "cp", "POINT (5 5)", "{\"oks_id\":\"oks\"}")));

        assertThat(full.getTieInCandidates()).isNotEmpty();
        assertThat(full.getTieInCandidates()).allSatisfy(candidate ->
                assertThat(candidate.isNewChamberRequired()).isTrue());
    }

    @Test
    void reusesExistingChamberAtTenMetresButCreatesOneBeyondTheBoundary() throws Exception {
        TopologyAnalysis exactlyTenMetres = analyzer.analyze(List.of(
                feature("source", "src", "POINT (0 0)", "{}"),
                feature("heat_network", "net", "LINESTRING (0 0, 100 0)", upstream("src")),
                feature("heat_chamber", "ch", "POINT (40 0)", upstream("net")),
                feature("oks_connection_point", "cp", "POINT (50 20)", "{\"oks_id\":\"oks\"}")));

        assertThat(exactlyTenMetres.getTieInCandidates())
                .filteredOn(candidate -> "ch".equals(candidate.getTargetId()))
                .singleElement().satisfies(candidate -> {
            assertThat(candidate.getTargetId()).isEqualTo("ch");
            assertThat(candidate.getTargetType()).isEqualTo("heat_chamber");
            assertThat(candidate.isNewChamberRequired()).isFalse();
        });
        assertThat(exactlyTenMetres.getTieInCandidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.getTargetId()).isEqualTo("net");
                    assertThat(candidate.isNewChamberRequired()).isTrue();
                    assertThat(candidate.hasFixedTieIn()).isTrue();
                });

        TopologyAnalysis beyondTenMetres = analyzer.analyze(List.of(
                feature("source", "src", "POINT (0 0)", "{}"),
                feature("heat_network", "net", "LINESTRING (0 0, 100 0)", upstream("src")),
                feature("heat_chamber", "ch", "POINT (39.9 0)", upstream("net")),
                feature("oks_connection_point", "cp", "POINT (50 20)", "{\"oks_id\":\"oks\"}")));

        assertThat(beyondTenMetres.getTieInCandidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.getTargetId()).isEqualTo("net");
            assertThat(candidate.getTargetType()).isEqualTo("heat_network");
            assertThat(candidate.isNewChamberRequired()).isTrue();
        });
    }

    @Test
    void splitsInternalTieInWithoutLosingOrDuplicatingLength() throws Exception {
        LineString segment = (LineString) wktReader.read("LINESTRING (0 0, 40 0, 100 0)");
        Point requested = (Point) wktReader.read("POINT (35 12)");

        SegmentSplit split = splitter.split(segment, requested);

        assertThat(split.getTieInPoint().getX()).isEqualTo(35.0);
        assertThat(split.getTieInPoint().getY()).isZero();
        assertThat(split.getFirstPart().getLength()).isEqualTo(35.0);
        assertThat(split.getSecondPart().getLength()).isEqualTo(65.0);
        assertThat(split.getFirstPart().getLength() + split.getSecondPart().getLength())
                .isEqualTo(segment.getLength());
    }

    private ImportedOfficialFeature feature(
            String objectType, String id, String wkt, String attributes) throws Exception {
        JsonNode node = objectMapper.readTree(attributes);
        try {
            return new ImportedOfficialFeature(id, objectType, node, wktReader.read(wkt));
        } catch (ParseException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private String upstream(String id) {
        return "{\"upstream_object_id\":\"" + id + "\"}";
    }
}
