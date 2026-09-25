package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.routing.OfficialRouteGeometryRules.Constraint;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Проверяет отступы без льготы корня, локальный контакт теплосети и округлённую геометрию перед сборкой сети. */
class PreparedCorridorTest {
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @Test
    void shortRootApproachAndRemoteEdgesBothKeepTheWholeBuildingSetback() throws Exception {
        Coordinate root = new Coordinate(-2, 10);
        PreparedCorridor corridor = buildingCorridor(root);
        Coordinate exit = new Coordinate(-8, 10);
        assertThat(corridor.pointAllowed(root)).isFalse();
        assertSymmetric(corridor, root, exit, false);
        Coordinate remoteA = new Coordinate(-2, 15), remoteB = new Coordinate(-2, 19);
        assertThat(corridor.pointAllowed(remoteA)).isFalse();
        assertThat(corridor.edgeAllowed(remoteA, remoteB)).isFalse();
        assertThat(corridor.path(List.of(root, exit, new Coordinate(-8, 18), remoteB))).isNull();
        Coordinate boundary = new Coordinate(-5.255, 10);
        PreparedCorridor legal = buildingCorridor(boundary);
        assertThat(legal.pointAllowed(boundary)).isTrue();
        assertSymmetric(legal, boundary, exit, true);
        assertThat(legal.edgeAllowed(remoteA, remoteB)).isFalse();
    }

    @Test
    void rootApproachCannotFollowTheWholeFacadeInsideItsSetback() throws Exception {
        Coordinate root = new Coordinate(-2, 2);
        PreparedCorridor corridor = buildingCorridor(root);
        // Весь фасад, включая корневой конец, защищён полным осевым отступом ДУ100.
        assertThat(corridor.edgeAllowed(root, new Coordinate(-2, 30))).isFalse();
        assertThat(corridor.path(List.of(root, new Coordinate(-2, 30)))).isNull();
    }

    @Test
    void rootApproachNeverAllowsCrossingTheBuildingFootprint() throws Exception {
        Coordinate root = new Coordinate(-2, 10);
        PreparedCorridor corridor = buildingCorridor(root);
        assertThat(corridor.edgeAllowed(root, new Coordinate(28, 10))).isFalse();
        assertThat(corridor.path(List.of(root, new Coordinate(28, 10)))).isNull();
    }

    @Test
    void aGenuinelyForbiddenRootCannotAcquireAnyEscapingEdge() throws Exception {
        for (String type : List.of("oks", "park", "water", "prohibited_site")) {
            ImportedOfficialFeature obstacle = feature("obstacle", type, "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
            Coordinate root = new Coordinate(10, 10);
            PreparedCorridor corridor = new PreparedCorridor(rules, rules.baseConstraints(List.of(obstacle), 100), root, "existing-root");
            assertThat(corridor.pointAllowed(root)).as(type).isFalse();
            for (Coordinate outside : List.of(new Coordinate(-20, 10), new Coordinate(40, 10), new Coordinate(10, 40))) {
                assertThat(corridor.edgeAllowed(root, outside)).as(type).isFalse();
                assertThat(corridor.edgeAllowed(outside, root)).as(type).isFalse();
                assertThat(corridor.path(List.of(root, outside))).as(type).isNull();
            }
        }
    }

    @Test
    void aMatchingTargetIdMustNotEraseAGenuinelyForbiddenConstraint() throws Exception {
        for (String type : List.of("oks", "park", "water", "prohibited_site")) {
            ImportedOfficialFeature obstacle = feature("same-id", type, "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
            Coordinate root = new Coordinate(10, 10);
            PreparedCorridor corridor = new PreparedCorridor(rules, rules.baseConstraints(List.of(obstacle), 100), root, "same-id");
            assertThat(corridor.pointAllowed(root)).as(type).isFalse();
            assertSymmetric(corridor, root, new Coordinate(-20, 10), false);
        }
    }

    @Test
    void heatNetworkTargetDoesNotExemptAForbiddenObjectWithTheSameId() throws Exception {
        ImportedOfficialFeature obstacle = feature("duplicate", "park", "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        ImportedOfficialFeature network = new ImportedOfficialFeature("duplicate", "heat_network", new ObjectMapper().createObjectNode(),
                new WKTReader().read("LINESTRING (10 -20,10 30)"));
        Coordinate root = new Coordinate(10, 10);
        for (List<ImportedOfficialFeature> features : List.of(List.of(obstacle, network), List.of(network, obstacle))) {
            PreparedCorridor corridor = new PreparedCorridor(rules, rules.baseConstraints(features, 100), root, "duplicate");
            assertSymmetric(corridor, root, new Coordinate(-20, 10), false);
        }
    }

    @Test
    void exemptsOnlyTheSelectedHeatNetworkAndKeepsOtherCrossingSections() throws Exception {
        ImportedOfficialFeature network = new ImportedOfficialFeature("target", "heat_network", new ObjectMapper().createObjectNode(),
                new WKTReader().read("LINESTRING (0 -20,0 20)"));
        ImportedOfficialFeature road = feature("target", "road", "POLYGON ((9 -20,11 -20,11 20,9 20,9 -20))");
        Coordinate root = new Coordinate(0, 0), end = new Coordinate(20, 0);
        PreparedCorridor corridor = new PreparedCorridor(rules, rules.baseConstraints(List.of(network, road), 100), root, "target");
        assertSymmetric(corridor, root, end, true);
        RoutePath path = corridor.path(List.of(root, end));
        assertThat(path.sections()).anyMatch(section -> "road".equals(section.getRestrictionType()));
        assertThat(path.sections()).noneMatch(section -> "heat_network".equals(section.getRestrictionType()));
    }

    @Test
    void endpointSetbackRejectionRotatesAndTranslatesWithTheFootprint() throws Exception {
        Geometry original = new WKTReader().read("POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        for (double degrees : new double[] {13, 71, 117, 203, 289}) {
            double angle = Math.toRadians(degrees), c = Math.cos(angle), s = Math.sin(angle);
            AffineTransformation transform = new AffineTransformation(c, -s, 8200.5, s, c, -4100.25);
            ImportedOfficialFeature building = new ImportedOfficialFeature("rotated", "restriction",
                    new ObjectMapper().createObjectNode().put("restriction_type", "oks"), transform.transform(original));
            List<Constraint> constraints = rules.baseConstraints(List.of(building), 100);
            Coordinate wallRoot = transform.transform(new Coordinate(-2, 10), new Coordinate());
            PreparedCorridor wall = new PreparedCorridor(rules, constraints, wallRoot, null);
            assertSymmetric(wall, wallRoot, transform.transform(new Coordinate(-8, 10), new Coordinate()), false);
            assertSymmetric(wall, wallRoot, transform.transform(new Coordinate(-2, 30), new Coordinate()), false);
            Coordinate cornerRoot = transform.transform(new Coordinate(-2, -2), new Coordinate());
            PreparedCorridor corner = new PreparedCorridor(rules, constraints, cornerRoot, null);
            assertSymmetric(corner, cornerRoot, transform.transform(new Coordinate(-8, -8), new Coordinate()), false);
            assertSymmetric(corner, cornerRoot, transform.transform(new Coordinate(-8, 4), new Coordinate()), false);
            // 1 мм снаружи границы оставляет место для округления произвольно повёрнутых XY.
            Coordinate legalRoot = transform.transform(new Coordinate(-5.256, 10), new Coordinate());
            PreparedCorridor legal = new PreparedCorridor(rules, constraints, legalRoot, null);
            assertSymmetric(legal, legalRoot, transform.transform(new Coordinate(-8, 10), new Coordinate()), true);
            Coordinate legalCorner = transform.transform(new Coordinate(-5.256, -5.256), new Coordinate());
            assertSymmetric(new PreparedCorridor(rules, constraints, legalCorner, null), legalCorner,
                    transform.transform(new Coordinate(-8, -8), new Coordinate()), true);
        }
    }

    @Test
    void outwardAngleAndLengthCannotExemptARootInsideTheSetback() throws Exception {
        Coordinate root = new Coordinate(-2, 10);
        PreparedCorridor corridor = buildingCorridor(root);
        assertSymmetric(corridor, root, new Coordinate(-8, 10), false);
        assertSymmetric(corridor, root, new Coordinate(-8, 16), false);
        assertSymmetric(corridor, root, new Coordinate(-3, 30), false);
        assertSymmetric(corridor, root, new Coordinate(-200, 10), false);
        assertSymmetric(corridor, root, new Coordinate(-3, 10), false);
        Coordinate boundary = new Coordinate(-5.255, 10);
        PreparedCorridor legal = buildingCorridor(boundary);
        for (Coordinate end : List.of(new Coordinate(-8, 10), new Coordinate(-11.255, 16),
                new Coordinate(-6.255, 30), new Coordinate(-200, 10))) {
            assertSymmetric(legal, boundary, end, true);
        }
        assertSymmetric(legal, boundary, new Coordinate(-3, 10), false);
    }

    @Test
    void cornerEndpointsRequireFullClearanceForOrthogonalAndDiagonalExits() throws Exception {
        Coordinate root = new Coordinate(-2, -2);
        PreparedCorridor corridor = buildingCorridor(root);
        assertSymmetric(corridor, root, new Coordinate(-8, -2), false);
        assertSymmetric(corridor, root, new Coordinate(-2, -8), false);
        assertSymmetric(corridor, root, new Coordinate(-8, -8), false);
        assertSymmetric(corridor, root, new Coordinate(-8, 4), false);
        // 3–4–5: расстояние от угла до корня ровно 5,255 м.
        Coordinate boundary = new Coordinate(-3.153, -4.204);
        PreparedCorridor legal = buildingCorridor(boundary);
        assertSymmetric(legal, boundary, new Coordinate(-8, -4.204), true);
        assertSymmetric(legal, boundary, new Coordinate(-3.153, -8), true);
        assertSymmetric(legal, boundary, new Coordinate(-8, -8), true);
        assertSymmetric(legal, boundary, new Coordinate(-8, 4), false);
    }

    @Test
    void roundedCornerEndpointsKeepIllegalAndLegalExitsSeparateInBothDirections() throws Exception {
        Coordinate root = new Coordinate(-2.00049, -2.00049);
        PreparedCorridor corridor = buildingCorridor(root);
        assertSymmetric(corridor, root, new Coordinate(-8.00049, -2.00049), false);
        assertSymmetric(corridor, root, new Coordinate(-2.00049, -8.00049), false);
        assertSymmetric(corridor, root, new Coordinate(-8.00049, 4.00049), false);
        Coordinate legalRoot = new Coordinate(-5.25549, -5.25549);
        PreparedCorridor legal = buildingCorridor(legalRoot);
        assertSymmetric(legal, legalRoot, new Coordinate(-8.00049, -5.25549), true);
        assertSymmetric(legal, legalRoot, new Coordinate(-5.25549, -8.00049), true);
    }

    @Test
    void inwardRoundingOntoTheFootprintCannotReceiveAnException() throws Exception {
        Coordinate root = new Coordinate(-0.00049, 10);
        PreparedCorridor corridor = buildingCorridor(root);
        Coordinate exit = new Coordinate(-8, 10);
        assertThat(root.x).isLessThan(0);
        assertThat(new RouteCoordinate(root.x, root.y).toCoordinate().x).isZero();
        assertThat(corridor.edgeAllowed(root, exit)).isFalse();
        assertThat(corridor.path(List.of(root, exit))).isNull();
        assertThat(corridor.path(List.of(exit, root))).isNull();
    }

    @Test
    void mergedSetbacksBlockRootEscapeAndInteriorTravelWithoutFootprintIntersection() throws Exception {
        ImportedOfficialFeature building = feature("multipart", "oks", "MULTIPOLYGON ("
                + "((0 0,20 0,20 20,0 20,0 0)),((-12 12,-8 12,-8 20,-12 20,-12 12)))");
        Coordinate root = new Coordinate(-2, 10), end = new Coordinate(-30, 10);
        List<Constraint> constraints = rules.baseConstraints(List.of(building), 100);
        assertThat(rules.segmentAllowed(root, end, rules.applicableConstraints(constraints, Set.of(), root, root))).isFalse();
        assertThat(rules.line(List.of(root, end)).intersection(constraints.get(0).blocked()).getNumGeometries()).isEqualTo(1);
        PreparedCorridor corridor = new PreparedCorridor(rules, constraints, root, null);
        assertSymmetric(corridor, root, end, false);
        Coordinate before = new Coordinate(-4, -20), after = new Coordinate(-4, 40);
        assertThat(corridor.pointAllowed(before)).isTrue();
        assertThat(corridor.pointAllowed(after)).isTrue();
        Geometry interior = rules.line(List.of(before, after));
        assertThat(interior.intersects(building.getMetricGeometry())).isFalse();
        assertThat(interior.intersection(constraints.get(0).blocked()).getNumGeometries()).isEqualTo(1);
        assertSymmetric(corridor, before, after, false);
        assertSymmetric(corridor, new Coordinate(-30, -20), new Coordinate(-30, 40), true);
    }

    @Test
    void cannotReenterAnotherPartOfTheSameSetbackAfterLeavingIt() throws Exception {
        ImportedOfficialFeature building = feature("multipart", "oks", "MULTIPOLYGON ("
                + "((0 0,20 0,20 20,0 20,0 0)),((-22 12,-14 12,-14 20,-22 20,-22 12)))");
        Coordinate root = new Coordinate(-2, 10), end = new Coordinate(-40, 10);
        List<Constraint> constraints = rules.baseConstraints(List.of(building), 100);
        assertThat(rules.segmentAllowed(root, end, rules.applicableConstraints(constraints, Set.of(), root, root))).isFalse();
        assertThat(rules.line(List.of(root, end)).intersection(constraints.get(0).blocked()).getNumGeometries()).isGreaterThan(1);
        PreparedCorridor corridor = new PreparedCorridor(rules, constraints, root, null);
        assertSymmetric(corridor, root, end, false);
        Coordinate before = new Coordinate(-40, 22), after = new Coordinate(40, 22);
        assertThat(corridor.pointAllowed(before)).isTrue();
        assertThat(corridor.pointAllowed(after)).isTrue();
        Geometry reentry = rules.line(List.of(before, after));
        assertThat(reentry.intersects(building.getMetricGeometry())).isFalse();
        assertThat(reentry.intersection(constraints.get(0).blocked()).getNumGeometries()).isGreaterThan(1);
        assertSymmetric(corridor, before, after, false);
        assertSymmetric(corridor, new Coordinate(-40, 25.255), new Coordinate(40, 25.255), true);
    }

    @Test
    void overlappingBuildingSetbacksRejectRootRegardlessOfInputOrder() throws Exception {
        ImportedOfficialFeature right = feature("right", "oks", "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        ImportedOfficialFeature left = feature("left", "oks", "POLYGON ((-28 0,-8 0,-8 20,-28 20,-28 0))");
        Coordinate root = new Coordinate(-4, 10);
        for (List<ImportedOfficialFeature> features : List.of(List.of(left, right), List.of(right, left))) {
            PreparedCorridor corridor = new PreparedCorridor(rules, rules.baseConstraints(features, 100), root, null);
            assertSymmetric(corridor, root, new Coordinate(-4, 30), false);
        }
    }

    @Test
    void aLegalRootStillCannotBeRevisitedInTheMiddleOfAPath() throws Exception {
        Coordinate root = new Coordinate(-8, 10), a = new Coordinate(-10, 5), b = new Coordinate(-10, 15);
        PreparedCorridor corridor = buildingCorridor(root);
        assertThat(corridor.edgeAllowed(root, a)).isTrue();
        assertThat(corridor.edgeAllowed(root, b)).isTrue();
        assertThat(corridor.path(List.of(a, root, b))).isNull();
        assertThat(corridor.path(List.of(b, root, a))).isNull();
        assertThat(corridor.path(List.of(a, new Coordinate(-10, 10), b))).isNotNull();
    }

    @Test
    void validatesAfterMillimetreRoundingAndRejectsANewClearanceIntersection() throws Exception {
        ImportedOfficialFeature obstacle = feature("submillimetre-boundary", "oks",
                "POLYGON ((0.0006 0,20.0006 0,20.0006 20,0.0006 20,0.0006 0))");
        PreparedCorridor corridor = new PreparedCorridor(rules, rules.baseConstraints(List.of(obstacle), 100),
                new Coordinate(-20, -20), null);
        // ДУ100: 5 м + половина пары 0,255 м. До округления отступ 5,25505 м;
        // округление X к -5,254 уменьшает его до 5,2546 м и должно быть отклонено.
        Coordinate a = new Coordinate(-5.25445, 3), b = new Coordinate(-5.25445, 17);
        assertThat(corridor.edgeAllowed(a, b)).isTrue();
        assertThat(corridor.pointAllowed(new RouteCoordinate(a.x, a.y).toCoordinate())).isFalse();
        assertThat(corridor.path(List.of(a, b))).isNull();
    }

    @Test
    void roundedAcceptedPathHasConsistentCoordinatesLengthAndSections() {
        PreparedCorridor corridor = new PreparedCorridor(rules, List.of(), new Coordinate(0, 0), null);
        RoutePath path = corridor.path(List.of(new Coordinate(0.0001, 0.0002), new Coordinate(8.12349, 0.0001),
                new Coordinate(8.12349, 6.78949)));
        assertThat(path).isNotNull();
        assertThat(path.coordinates()).hasSize(3);
        assertThat(path.coordinates().get(0).distance(new Coordinate(0, 0))).isZero();
        assertThat(path.coordinates().get(1).distance(new Coordinate(8.123, 0))).isZero();
        assertThat(path.coordinates().get(2).distance(new Coordinate(8.123, 6.789))).isZero();
        assertThat(Math.abs(path.lengthM() - rules.line(path.coordinates()).getLength())).isLessThan(1e-9);
        assertThat(Math.abs(path.sections().stream().mapToDouble(s -> s.getLengthM().doubleValue()).sum() - path.lengthM())).isLessThan(0.001);
        for (int i = 1; i < path.coordinates().size(); i++) {
            assertThat(corridor.edgeAllowed(path.coordinates().get(i - 1), path.coordinates().get(i))).isTrue();
        }
    }

    @Test
    void roundedLegalRootStaysOnBoundaryWhileInsideEndpointsRemainForbidden() throws Exception {
        Coordinate root = new Coordinate(-5.25549, 10.00049);
        PreparedCorridor corridor = buildingCorridor(root);
        RoutePath path = corridor.path(List.of(root, new Coordinate(-8.00049, 10.00049)));
        assertThat(path).isNotNull();
        assertThat(path.coordinates().get(0).distance(new Coordinate(-5.255, 10))).isZero();
        assertThat(corridor.edgeAllowed(new Coordinate(-2, 15), new Coordinate(-2, 18))).isFalse();
        Coordinate illegalRoot = new Coordinate(-5.25449, 10.00049);
        assertSymmetric(buildingCorridor(illegalRoot), illegalRoot, new Coordinate(-8.00049, 10.00049), false);
    }

    @Test
    void rootExitRejectsTheOccupiedOutgoingConeButNotTheFreeOppositeRay() throws Exception {
        Coordinate root = new Coordinate(0, 0);
        ImportedOfficialFeature network = network("incident", "LINESTRING (0 0,20 0)");
        PreparedCorridor corridor = networkCorridor(root, List.of(network), "chamber");
        for (double degrees : new double[] {0, 0.3, -0.3, 30, -30, 44.49, -44.49}) {
            assertSymmetric(corridor, root, ray(root, degrees, 100), false);
        }
        for (double degrees : new double[] {44.51, -44.51, 45, -45, 90, -90, 135, 180}) {
            assertSymmetric(corridor, root, ray(root, degrees, 100), true);
        }
        // Граница относится к фактической геометрии: path отдельно перепроверяет округлённую.
        for (double degrees : new double[] {44.5, -44.5}) {
            Coordinate boundary = ray(root, degrees, 100);
            assertThat(corridor.edgeAllowed(root, boundary)).isTrue();
            assertThat(corridor.edgeAllowed(boundary, root)).isTrue();
        }
    }

    @Test
    void matchingSelectedNetworkIdCannotEraseItsOccupiedRootRay() throws Exception {
        Coordinate root = new Coordinate(0, 0);
        PreparedCorridor corridor = networkCorridor(root,
                List.of(network("selected", "LINESTRING (0 0,20 0)")), "selected");
        assertSymmetric(corridor, root, new Coordinate(20, 0.1), false);
        assertSymmetric(corridor, root, new Coordinate(-20, 0), true);
        assertSymmetric(corridor, root, new Coordinate(0, 20), true);
    }

    @Test
    void anotherIncidentSegmentOccupiesTheOtherwiseFreeOppositeRay() throws Exception {
        Coordinate root = new Coordinate(0, 0);
        ImportedOfficialFeature east = network("east", "LINESTRING (0 0,20 0)");
        ImportedOfficialFeature west = network("west", "LINESTRING (-20 0,0 0)");
        for (List<ImportedOfficialFeature> features : List.of(List.of(east, west), List.of(west, east))) {
            PreparedCorridor corridor = networkCorridor(root, features, "chamber");
            assertSymmetric(corridor, root, new Coordinate(20, 0), false);
            assertSymmetric(corridor, root, new Coordinate(-20, 0), false);
            assertSymmetric(corridor, root, new Coordinate(0, 20), true);
            assertSymmetric(corridor, root, new Coordinate(0, -20), true);
        }
    }

    @Test
    void rootInsideAnExistingSegmentHasTwoOccupiedRays() throws Exception {
        Coordinate root = new Coordinate(0, 0.0004);
        PreparedCorridor corridor = networkCorridor(root,
                List.of(network("through", "LINESTRING (-20 0,20 0)")), null);
        assertSymmetric(corridor, root, new Coordinate(20, 0), false);
        assertSymmetric(corridor, root, new Coordinate(-20, 0), false);
        assertSymmetric(corridor, root, new Coordinate(0, 20), true);
    }

    @Test
    void followsEachLocalMultipartSegmentWithoutInventingAChordOrAnOppositeRay() throws Exception {
        Coordinate root = new Coordinate(0, 0);
        for (String wkt : List.of(
                "MULTILINESTRING ((0 0,0 0,20 0),(0 20,0 0),(-20 -20,-10 -10))",
                "MULTILINESTRING ((-10 -10,-20 -20),(0 0,0 20),(20 0,0 0,0 0))")) {
            PreparedCorridor corridor = networkCorridor(root, List.of(network("multi", wkt)), null);
            assertSymmetric(corridor, root, new Coordinate(20, 0), false);
            assertSymmetric(corridor, root, new Coordinate(0, 20), false);
            assertSymmetric(corridor, root, new Coordinate(-20, 0), true);
            assertSymmetric(corridor, root, new Coordinate(0, -20), true);
        }
    }

    @Test
    void roundedRootAndNetworkEndpointKeepTheSameLocalRayWithoutAFalseOpposite() throws Exception {
        Coordinate root = new Coordinate(0.00049, 0.00049);
        // Проекция корня находится внутри отрезка, но сам endpoint ближе 1 мм: луч лишь один.
        PreparedCorridor corridor = networkCorridor(root,
                List.of(network("incident", "LINESTRING (0 0,20 0)")), null);
        assertSymmetric(corridor, root, new Coordinate(20.00049, 0.10049), false);
        assertSymmetric(corridor, root, new Coordinate(-20.00049, 0.00049), true);
        assertSymmetric(corridor, root, new Coordinate(0.00049, 20.00049), true);
    }

    @Test
    void rootDirectionIsCheckedAgainWhenRoundingMovesItIntoTheOccupiedCone() throws Exception {
        Coordinate root = new Coordinate(0, 0), end = new Coordinate(0.1, 0.09828);
        PreparedCorridor corridor = networkCorridor(root,
                List.of(network("incident", "LINESTRING (0 0,20 0)")), null);
        assertThat(corridor.edgeAllowed(root, end)).isTrue();
        assertThat(corridor.edgeAllowed(end, root)).isTrue();
        assertThat(corridor.path(List.of(root, end))).isNull();
        assertThat(corridor.path(List.of(end, root))).isNull();
    }

    @Test
    void anIncidentSegmentWithinOneMillimetreIsNotConfusedWithARemoteParallelLine() throws Exception {
        Coordinate root = new Coordinate(0, 0);
        for (double offset : new double[] {0.0009, 0.001, 0.0011, 1}) {
            PreparedCorridor corridor = networkCorridor(root,
                    List.of(network("offset", "LINESTRING (0 " + offset + ",20 " + offset + ")")), null);
            assertSymmetric(corridor, root, new Coordinate(20, 0), offset > 0.001);
        }
        // Polygon road с отступом не должна включать теплосетевую эвристику совпадения осей.
        ImportedOfficialFeature road = feature("road", "road", "POLYGON ((0 3,20 3,20 9,0 9,0 3))");
        PreparedCorridor roadOnly = networkCorridor(root,
                List.of(road), null);
        assertSymmetric(roadOnly, root, new Coordinate(-20, 0), true);
        assertThat(roadOnly.edgeAllowed(root, new Coordinate(20, 1))).isEqualTo(
                rules.segmentAllowed(root, new Coordinate(20, 1),
                        rules.baseConstraints(List.of(road), 100)));
    }

    @Test
    void distantSegmentsOfAnIncidentNetworkRemainOrdinaryCrossings() throws Exception {
        Coordinate root = new Coordinate(0, 0);
        ImportedOfficialFeature network = network("loop", "LINESTRING (0 0,10 0,10 10,-10 10)");
        PreparedCorridor corridor = networkCorridor(root, List.of(network), "chamber");
        Coordinate end = new Coordinate(0, 20);
        assertSymmetric(corridor, root, end, true);
        RoutePath path = corridor.path(List.of(root, end));
        assertThat(path.sections()).anyMatch(section -> "loop".equals(section.getRestrictionId())
                && "heat_network".equals(section.getRestrictionType())
                && section.getCrossingAngleDegrees().doubleValue() == 90.0);
        // Фильтр направлений относится только к корню, не ко всем рёбрам рядом с теплосетью.
        assertSymmetric(corridor, new Coordinate(5, -5), new Coordinate(15, 15), true);
    }

    @Test
    void rootRayFilteringRotatesTranslatesAndKeepsInputCoordinatesUnchanged() throws Exception {
        Geometry original = new WKTReader().read("LINESTRING (20 0,0 0,0 20)");
        for (double degrees : new double[] {13, 71, 117, 203, 289}) {
            double angle = Math.toRadians(degrees), c = Math.cos(angle), s = Math.sin(angle);
            AffineTransformation transform = new AffineTransformation(c, -s, 8200.50049, s, c, -4100.25049);
            Geometry source = transform.transform(original), copy = source.copy();
            Coordinate root = transform.transform(new Coordinate(0, 0), new Coordinate());
            Coordinate rootCopy = new Coordinate(root);
            ImportedOfficialFeature network = new ImportedOfficialFeature("rotated", "heat_network",
                    new ObjectMapper().createObjectNode(), source);
            PreparedCorridor corridor = networkCorridor(root, List.of(network), null);
            assertSymmetric(corridor, root, transform.transform(new Coordinate(20, 0), new Coordinate()), false);
            assertSymmetric(corridor, root, transform.transform(new Coordinate(0, 20), new Coordinate()), false);
            assertSymmetric(corridor, root, transform.transform(new Coordinate(-20, 0), new Coordinate()), true);
            assertSymmetric(corridor, root, transform.transform(new Coordinate(0, -20), new Coordinate()), true);
            assertThat(source.equalsExact(copy)).isTrue();
            assertThat(root.equals2D(rootCopy)).isTrue();
        }
    }

    private PreparedCorridor networkCorridor(Coordinate root, List<ImportedOfficialFeature> features, String targetId) {
        return new PreparedCorridor(rules, rules.baseConstraints(features, 100), root, targetId);
    }

    private ImportedOfficialFeature network(String id, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "heat_network", new ObjectMapper().createObjectNode(), new WKTReader().read(wkt));
    }

    private Coordinate ray(Coordinate root, double degrees, double length) {
        double angle = Math.toRadians(degrees);
        return new Coordinate(root.x + length * Math.cos(angle), root.y + length * Math.sin(angle));
    }

    private PreparedCorridor buildingCorridor(Coordinate root) throws Exception {
        ImportedOfficialFeature building = feature("building", "oks", "POLYGON ((0 0,20 0,20 20,0 20,0 0))");
        return new PreparedCorridor(rules, rules.baseConstraints(List.of(building), 100), root, null);
    }

    private void assertSymmetric(PreparedCorridor corridor, Coordinate root, Coordinate other, boolean allowed) {
        assertThat(corridor.edgeAllowed(root, other)).as("outward %s -> %s", root, other).isEqualTo(allowed);
        assertThat(corridor.edgeAllowed(other, root)).as("reverse %s -> %s", other, root).isEqualTo(allowed);
        assertThat(corridor.path(List.of(root, other)) != null).as("rounded outward path").isEqualTo(allowed);
        assertThat(corridor.path(List.of(other, root)) != null).as("rounded reverse path").isEqualTo(allowed);
    }

    private ImportedOfficialFeature feature(String id, String type, String wkt) throws Exception {
        return new ImportedOfficialFeature(id, "restriction", new ObjectMapper().createObjectNode().put("restriction_type", type),
                new WKTReader().read(wkt));
    }
}
