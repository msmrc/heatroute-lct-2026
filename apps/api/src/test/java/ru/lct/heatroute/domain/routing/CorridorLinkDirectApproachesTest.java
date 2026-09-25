package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Прямой хвост сохраняет точный конец и проходит те же проверки, что и L-подходы. */
class CorridorLinkDirectApproachesTest {
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());
    private final OfficialObstacleRouter router = new OfficialObstacleRouter(rules);

    @ParameterizedTest(name = "outerUpstream={0}, angle={1}, offset={2}")
    @MethodSource("millimetreCases")
    void keepsCheckedStraightRayToTheExactRoundedEndpoint(boolean outerUpstream, double angle, double offset) {
        Coordinate outer = point(-100, 0, angle), oldJoint = point(0, 0, angle);
        Coordinate junction = point(0, offset, angle);
        RouteEdge source = source(outerUpstream, List.of(outer, point(-10, 0, angle), oldJoint));
        List<RouteCoordinate> before = List.copyOf(source.getCoordinates());
        List<RoutePath> paths = build(source, outer, junction, angle, List.of());

        assertThat(paths).as("a checked direct ray must not depend on the projected millimetre elbow")
                .anySatisfy(path -> assertThat(path.coordinates()).containsExactly(rounded(outer), rounded(junction)));
        assertValid(paths, outer, junction, outerUpstream, List.of());
        assertThat(source.getCoordinates()).containsExactlyElementsOf(before);
    }

    private static Stream<Arguments> millimetreCases() {
        List<Arguments> cases = new ArrayList<>();
        for (boolean upstream : new boolean[] {true, false}) {
            for (double angle : new double[] {0, 0.37, Math.PI / 2}) {
                for (double offset : new double[] {0, 0.0004, 0.001, 0.0012, 0.002, -0.0012}) {
                    cases.add(Arguments.of(upstream, angle, offset));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @CsvSource({"true,0", "false,0", "true,0.0004", "false,0.0004"})
    void junctionRoundingOntoTheCutDoesNotAddAZeroLengthEndLeg(boolean outerUpstream, double offset) {
        Coordinate outer = new Coordinate(0, 0), cut = new Coordinate(10, 0);
        Coordinate junction = new Coordinate(10, offset);
        List<RoutePath> paths = build(source(outerUpstream,
                List.of(outer, cut, new Coordinate(20, 0))), outer, junction, 0, List.of());
        assertThat(paths).anySatisfy(path -> assertThat(path.coordinates()).containsExactly(outer, cut));
        for (RoutePath path : paths) {
            for (int i = 1; i < path.coordinates().size(); i++) {
                assertThat(path.coordinates().get(i)).isNotEqualTo(path.coordinates().get(i - 1));
            }
        }
        assertValid(paths, outer, junction, outerUpstream, List.of());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retainsThePrefixWhenTheOuterToJunctionShortcutIsForbidden(boolean outerUpstream) {
        Coordinate outer = new Coordinate(0, 0), corner = new Coordinate(0, 100);
        Coordinate junction = new Coordinate(100, 100.002);
        List<ImportedOfficialFeature> features = List.of(building(30, 30, 40, 40));
        List<RoutePath> paths = build(source(outerUpstream,
                List.of(outer, corner, new Coordinate(100, 100))), outer, junction, 0, features);

        assertThat(paths).anySatisfy(path -> assertThat(path.coordinates()).containsExactly(outer, corner, junction));
        assertThat(paths).noneSatisfy(path -> assertThat(path.coordinates()).containsExactly(outer, junction));
        assertValid(paths, outer, junction, outerUpstream, features);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void aDirectTailCannotBorrowTheRootExceptionForEitherNewChamber(boolean outerUpstream, boolean atOuter) {
        Coordinate outer = new Coordinate(0, 0), junction = new Coordinate(-30, 0.002);
        List<ImportedOfficialFeature> features = List.of(atOuter
                ? building(1, -1, 3, 1) : building(-29, -1, -27, 1));
        RouteEdge source = source(outerUpstream, List.of(outer, new Coordinate(-20, 0)));
        assertThat(build(source, outer, junction, 0, List.of())).isNotEmpty();
        assertThat(build(source, outer, junction, 0, features)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"true,true,0", "true,false,0", "false,true,0", "false,false,0",
            "true,true,0.37", "true,false,0.37", "false,true,0.37", "false,false,0.37"})
    void directTailChecksThePhysicalRoadEntryAndSections(boolean outerUpstream, boolean goodDirection, double angle) {
        ImportedOfficialFeature road = new ImportedOfficialFeature("asymmetric-road", "restriction",
                new ObjectMapper().createObjectNode().put("restriction_type", "road"),
                GEOMETRY.createPolygon(new Coordinate[] {point(0, 0, angle), point(100, 0, angle),
                        point(107.150521556, 6, angle), point(0, 6, angle), point(0, 0, angle)}));
        Coordinate left = point(-10, 3, angle), right = point(120, 3, angle);
        Coordinate upstream = goodDirection ? left : right, downstream = goodDirection ? right : left;
        Coordinate outer = outerUpstream ? upstream : downstream;
        Coordinate junction = outerUpstream ? downstream : upstream;
        // The grid is slightly tilted: this new candidate must actually be a direct tail,
        // not the old exact-axis two-point control covered by CorridorLinkDirectionTest.
        List<RoutePath> paths = build(source(outerUpstream, List.of(outer, junction)),
                outer, junction, angle + 0.000002, List.of(road));
        boolean direct = paths.stream().anyMatch(path -> path.coordinates().equals(List.of(rounded(outer), rounded(junction))));
        assertThat(direct).isEqualTo(goodDirection);
        assertValid(paths, outer, junction, outerUpstream, List.of(road));
        if (goodDirection) {
            RoutePath selected = paths.stream().filter(path -> path.coordinates().size() == 2).findFirst().orElseThrow();
            assertThat(selected.sections()).anySatisfy(section -> assertThat(section.getRestrictionType()).isEqualTo("road"));
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 0.37})
    void missingDirectRayOtherwisePreventsAFourBranchAssignment(double angle) {
        Coordinate outer = point(-100, 0, angle), junction = rounded(point(0, 0.002, angle));
        List<RoutePath> alternatives = build(source(true, List.of(outer, point(0, 0, angle))),
                outer, junction, angle, List.of());
        List<List<RoutePath>> branches = new ArrayList<>();
        branches.add(alternatives);
        for (double bearing : new double[] {0, Math.PI / 2, -Math.PI / 2}) {
            Coordinate endpoint = rounded(new Coordinate(junction.x + 20 * Math.cos(angle + bearing),
                    junction.y + 20 * Math.sin(angle + bearing)));
            branches.add(List.of(path(List.of(endpoint, junction))));
        }
        List<RoutePath> chosen = CorridorJunctionAssignment.choose(junction, branches);
        assertThat(chosen).as("east, north and south are already occupied; the west ray is necessary").isNotNull();
        assertThat(chosen.get(0).coordinates()).containsExactly(rounded(outer), junction);
        List<RouteNode> nodes = new ArrayList<>(List.of(node("joint", junction, false)));
        List<RouteEdge> edges = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            RoutePath incoming = chosen.get(i);
            nodes.add(node("outside-" + i, incoming.coordinates().get(0), i == 0));
            RoutePath actual = i == 0 ? incoming : incoming.reversed();
            edges.add(edge("branch-" + i, i == 0 ? "outside-0" : "joint",
                    i == 0 ? "joint" : "outside-" + i, actual));
        }
        assertThat(new OfficialRouteValidator(rules).validate(nodes, edges, List.of())).isEmpty();
        assertThat(new EngineeringRouteEvaluator().evaluate(edges).isCompliant()).isTrue();
    }

    @Test
    void obliqueDirectTailIsReplacedByTheFourNormalRayFamilies() {
        Coordinate outer = new Coordinate(0, 0), junction = new Coordinate(25, 20);
        RouteEdge source = source(true, List.of(outer, new Coordinate(20, 0), new Coordinate(20, 20)));
        List<RoutePath> paths = build(source, outer, junction, 0, List.of());
        assertThat(paths).hasSizeBetween(4, 8);
        assertThat(paths).noneSatisfy(p -> assertThat(p.coordinates()).containsExactly(outer, junction));
        // Все четыре нормальных луча доступны; краткая диагональ не получает допуск камеры.
        for (Coordinate ray : List.of(new Coordinate(1, 0), new Coordinate(-1, 0),
                new Coordinate(0, 1), new Coordinate(0, -1))) {
            assertThat(paths.stream().anyMatch(p -> matchesRay(p, ray))).isTrue();
        }
        assertThat(paths).anySatisfy(p -> assertThat(p.coordinates())
                .containsExactly(outer, new Coordinate(20, 0), new Coordinate(20, 20), junction));
        assertValid(paths, outer, junction, true, List.of());
    }

    private List<RoutePath> build(RouteEdge source, Coordinate outer, Coordinate junction, double angle,
            List<ImportedOfficialFeature> features) {
        // No root exemption for the new outer chamber, including physically reversed edges.
        return CorridorLinkApproaches.build(source, node("outer", outer, false), junction,
                angle, router, router.prepare(features));
    }

    private void assertValid(List<RoutePath> paths, Coordinate outer, Coordinate junction,
            boolean outerUpstream, List<ImportedOfficialFeature> features) {
        assertThat(paths).hasSizeLessThanOrEqualTo(8);
        List<RouteNode> nodes = List.of(node("outer", outer, outerUpstream), node("joint", junction, !outerUpstream));
        for (RoutePath stored : paths) {
            RoutePath physical = outerUpstream ? stored : stored.reversed();
            RouteEdge actual = edge("candidate", outerUpstream ? "outer" : "joint",
                    outerUpstream ? "joint" : "outer", physical);
            assertThat(new OfficialRouteValidator(rules).validate(nodes, List.of(actual), features))
                    .extracting(RouteValidationIssue::getCode).isEmpty();
            assertThat(new EngineeringRouteEvaluator().evaluate(List.of(actual)).isCompliant()).isTrue();
            assertThat(stored.lengthM()).isEqualTo(line(stored.coordinates()).getLength());
            assertThat(stored.sections().stream().mapToDouble(section -> section.getLengthM().doubleValue()).sum())
                    .isCloseTo(stored.lengthM(), org.assertj.core.data.Offset.offset(0.01));
        }
    }

    private static boolean matchesRay(RoutePath path, Coordinate ray) {
        List<Coordinate> ps = path.coordinates();
        Coordinate end = ps.get(ps.size() - 1), previous = ps.get(ps.size() - 2);
        double cosine = ((previous.x - end.x) * ray.x + (previous.y - end.y) * ray.y) / previous.distance(end);
        return cosine > Math.cos(Math.toRadians(1));
    }

    private static RouteEdge source(boolean outerUpstream, List<Coordinate> points) {
        List<Coordinate> stored = new ArrayList<>(points);
        if (!outerUpstream) Collections.reverse(stored);
        return edge("source", outerUpstream ? "outer" : "joint", outerUpstream ? "joint" : "outer", path(stored));
    }

    private static RouteEdge edge(String id, String from, String to, RoutePath path) {
        return new RouteEdge(id, from, to, path.lengthM(), path.coordinates().stream()
                .map(p -> new RouteCoordinate(p.x, p.y)).collect(Collectors.toList()), path.sections(), BigDecimal.ONE, 100);
    }

    private static RouteNode node(String id, Coordinate at, boolean root) {
        return new RouteNode(id, "new_branch_chamber", new RouteCoordinate(at.x, at.y), true, root, 0, null);
    }

    private static RoutePath path(List<Coordinate> points) { return new RoutePath(points, List.of(), line(points).getLength()); }
    private static LineString line(List<Coordinate> points) { return GEOMETRY.createLineString(points.toArray(new Coordinate[0])); }
    private static Coordinate rounded(Coordinate point) { return new RouteCoordinate(point.x, point.y).toCoordinate(); }

    private static Coordinate point(double x, double y, double angle) {
        double originX = angle == 0 ? 0 : 500000, originY = angle == 0 ? 0 : 6170000;
        return new Coordinate(originX + x * Math.cos(angle) - y * Math.sin(angle),
                originY + x * Math.sin(angle) + y * Math.cos(angle));
    }

    private static ImportedOfficialFeature building(double minX, double minY, double maxX, double maxY) {
        return new ImportedOfficialFeature("foreign", "oks_existing", new ObjectMapper().createObjectNode(),
                GEOMETRY.createPolygon(new Coordinate[] {new Coordinate(minX, minY), new Coordinate(maxX, minY),
                        new Coordinate(maxX, maxY), new Coordinate(minX, maxY), new Coordinate(minX, minY)}));
    }
}
