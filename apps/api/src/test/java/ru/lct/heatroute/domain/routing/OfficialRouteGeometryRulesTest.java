package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

class OfficialRouteGeometryRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfficialRouteGeometryRules rules = new OfficialRouteGeometryRules(
            new OfficialConstraintCatalog(), new OfficialCrossingGeometry());

    @Test
    void preservesDeterministicNearestLegalNormalsAcrossShapeAndInputPermutations() throws Exception {
        List<EgressCase> corpus = new ArrayList<>(List.of(
                egressCase("equal-walls", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))",
                        new Coordinate(10, 10), new Coordinate(0, 10), new Coordinate(10, 0),
                        new Coordinate(10, 20), new Coordinate(20, 10)),
                egressCase("nearest-wall", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))",
                        new Coordinate(2, 8), new Coordinate(0, 8)),
                egressCase("slanted-wall", "POLYGON ((0 0, 20 0, 30 20, 10 20, 0 0))",
                        new Coordinate(6, 8), new Coordinate(4.4, 8.8)),
                // Ближайшая внутренняя стена ведёт в другую часть собственного здания.
                egressCase("concave", "POLYGON ((0 0, 20 0, 20 20, 8 20, 8 4, 4 4, 4 20, 0 20, 0 0))",
                        new Coordinate(3, 10), new Coordinate(0, 10)),
                egressCase("courtyard", "POLYGON ((0 0, 30 0, 30 30, 0 30, 0 0),"
                                + " (10 10, 10 20, 20 20, 20 10, 10 10))",
                        new Coordinate(8, 15), new Coordinate(0, 15)),
                egressCase("multipart", "MULTIPOLYGON (((0 0, 5 0, 5 20, 0 20, 0 0)),"
                                + " ((8 0, 15 0, 15 20, 8 20, 8 0)))",
                        new Coordinate(4, 10), new Coordinate(0, 10))));
        EgressCase blockedNearest = egressCase("blocked-nearest",
                "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))", new Coordinate(2, 8), new Coordinate(2, 0));
        blockedNearest.features.add(feature("foreign-park", "restriction", "park",
                "POLYGON ((-4 6, -2 6, -2 10, -4 10, -4 6))"));
        corpus.add(blockedNearest);
        EgressCase setbackOnly = egressCase("blocked-by-setback",
                "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))", new Coordinate(2, 8), new Coordinate(2, 0));
        // Просвет от оси 1,1 м проходит ошибочный R-only, но нарушает 1 + W/2 даже при ДУ50.
        setbackOnly.features.add(feature("foreign-park", "restriction", "park",
                "POLYGON ((-4 9.1, -2 9.1, -2 10.1, -4 10.1, -4 9.1))"));
        corpus.add(setbackOnly);

        EgressCase rotated = egressCase("rotated-utm", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))",
                new Coordinate(2, 8), new Coordinate(0, 8));
        AffineTransformation transform = AffineTransformation.rotationInstance(0.413);
        transform.translate(400_000, 6_170_000);
        List<ImportedOfficialFeature> rotatedFeatures = new ArrayList<>();
        for (ImportedOfficialFeature item : rotated.features) {
            rotatedFeatures.add(new ImportedOfficialFeature(item.getFeatureId(), item.getObjectType(),
                    item.getAttributes(), transform.transform(item.getMetricGeometry())));
        }
        corpus.add(new EgressCase(rotatedFeatures, transform.transform(rotated.start, new Coordinate()),
                List.of(transform.transform(rotated.walls.get(0), new Coordinate()))));

        for (EgressCase sample : corpus) {
            List<List<ImportedOfficialFeature>> permutations = featurePermutations(sample.features);
            for (int diameter : List.of(50, 500, 900)) {
                Optional<OfficialRouteGeometryRules.NormalEgress> first = rules.normalEgress(
                        sample.features, diameter, sample.start);
                assertNormalInvariant(sample, diameter, first.orElseThrow());
                for (List<ImportedOfficialFeature> permutation : permutations) {
                    assertSameEgress(first, rules.normalEgress(permutation, diameter, sample.start));
                }
                for (Coordinate target : List.of(new Coordinate(sample.start.x - 80, sample.start.y + 43),
                        new Coordinate(sample.start.x + 100, sample.start.y - 31))) {
                    List<OfficialRouteGeometryRules.NormalEgress> expected = rules.normalEgressCandidates(
                            sample.features, diameter, sample.start, target, 0);
                    assertThat(expected).as(sample.name() + " nearest walls, DU " + diameter)
                            .hasSize(sample.walls.size());
                    expected.forEach(egress -> assertNormalInvariant(sample, diameter, egress));
                    for (Coordinate wall : sample.walls) {
                        assertThat(expected).anySatisfy(egress -> assertThat(egress.exit().distance(
                                expectedExit(sample.start, wall, exteriorApproachM(diameter)))).isLessThan(1e-7));
                    }
                    assertThat(expected).extracting(egress -> egress.exit().distance(target)).isSorted();
                    for (List<ImportedOfficialFeature> permutation : permutations) {
                        assertSameEgress(Optional.of(expected.get(0)),
                                rules.normalEgressTowards(permutation, diameter, sample.start, target));
                        for (double extraM : List.of(0.0, 60.0)) {
                            List<OfficialRouteGeometryRules.NormalEgress> actual = rules.normalEgressCandidates(
                                    permutation, diameter, sample.start, target, extraM);
                            assertThat(actual).hasSize(expected.size());
                            for (int i = 0; i < expected.size(); i++) {
                                assertSameEgress(Optional.of(expected.get(i)), Optional.of(actual.get(i)));
                            }
                            assertSameEgress(Optional.of(expected.get(0)), rules.normalEgressTowards(
                                    permutation, diameter, sample.start, target, extraM));
                        }
                    }
                }
            }
        }
    }

    @Test
    void usesSourceFootprintsWithoutBufferingAnyFeatureForEgress() throws Exception {
        CountingPolygon own = countedSquare(0);
        CountingPolygon remote = countedSquare(200);
        List<ImportedOfficialFeature> features = List.of(
                new ImportedOfficialFeature("own", "oks_existing", mapper.createObjectNode(), own),
                new ImportedOfficialFeature("remote", "oks_existing", mapper.createObjectNode(), remote));
        Coordinate connection = new Coordinate(10, 10);
        Coordinate target = new Coordinate(-50, 10);

        assertThat(rules.normalEgress(features, 50, connection)).isPresent();
        assertThat(rules.normalEgressTowards(features, 500, connection, target)).isPresent();
        assertThat(rules.normalEgressCandidates(features, 900, connection, target, 60)).isNotEmpty();

        assertThat(own.bufferCalls).isZero();
        assertThat(remote.bufferCalls).isZero();
    }

    @Test
    void distinctOverlappingOksCannotWaiveEachOtherRegardlessOfLexicalOrInputOrder() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("z", "oks_existing", null, "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"),
                feature("a", "restriction", "oks", "POLYGON ((0.005 0, 20.005 0, 20.005 20, 0.005 20, 0.005 0))"));
        Coordinate point = new Coordinate(10, 10);
        for (List<ImportedOfficialFeature> permutation : featurePermutations(features)) {
            for (int diameter : List.of(50, 900)) {
                assertNoEgress(permutation, diameter, point);
            }
        }
    }

    @Test
    void acceptsWallAndNearWallConnectionsWithFullExteriorApproachButRejectsOutsidePoints() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("own", "oks_existing", null, "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        for (int diameter : List.of(50, 500, 900)) {
            assertNoEgress(features, diameter, new Coordinate(-0.001, 10));
            for (double x : List.of(0.0, 0.005, 0.01, 0.0101)) {
                Coordinate start = new Coordinate(x, 10);
                OfficialRouteGeometryRules.NormalEgress egress = rules.normalEgress(features, diameter, start)
                        .orElseThrow();
                assertThat(egress.start().distance(start)).isZero();
                assertThat(egress.exit().x).isCloseTo(-exteriorApproachM(diameter), offset(1e-9));
                assertThat(egress.exit().y).isCloseTo(10, offset(1e-9));
                assertSameEgress(Optional.of(egress), rules.normalEgressTowards(
                        features, diameter, start, new Coordinate(100, 90)));
            }
        }
    }

    @Test
    void returnsNoEgressForPointsInCourtyardsAndForFullyBlockedLegs() throws Exception {
        ImportedOfficialFeature courtyard = feature("courtyard", "oks_existing", null,
                "POLYGON ((0 0, 30 0, 30 30, 0 30, 0 0), (10 10, 10 20, 20 20, 20 10, 10 10))");
        List<ImportedOfficialFeature> blocked = List.of(
                feature("own", "oks_existing", null, "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"),
                feature("park", "restriction", "park", "POLYGON ((-20 -20, 40 -20, 40 40, -20 40, -20 -20))"));
        for (int diameter : List.of(50, 900)) {
            assertNoEgress(List.of(courtyard), diameter, new Coordinate(15, 15));
            for (List<ImportedOfficialFeature> permutation : featurePermutations(blocked)) {
                assertNoEgress(permutation, diameter, new Coordinate(2, 8));
            }
        }
    }

    @Test
    void extendsTheNormalBuildingExitAcrossOnlyItsContainingSocialArea() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("own", "oks_existing", null,
                        "POLYGON ((40 40, 60 40, 60 60, 40 60, 40 40))"),
                feature("kindergarten", "restriction", "social_area",
                        "POLYGON ((20 20, 80 20, 80 80, 20 80, 20 20))"),
                feature("foreign", "restriction", "social_area",
                        "POLYGON ((-80 20, -20 20, -20 80, -80 80, -80 20))"));

        OfficialRouteGeometryRules.NormalEgress egress = rules.normalEgressTowards(
                features, 50, new Coordinate(50, 50), new Coordinate(0, 50)).orElseThrow();

        assertThat(egress.exit().x).isCloseTo(20 - (1.0 + 0.200 + 0.25), offset(1e-9));
        assertThat(egress.exit().y).isEqualTo(50.0);
        assertThat(egress.terminalExemptionIds()).containsExactlyInAnyOrder("own", "kindergarten");
    }

    @Test
    void stillRejectsUnsupportedDiameterWhenAnEligibleOksExists() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("remote", "oks_existing", null, "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"));
        assertThatThrownBy(() -> rules.normalEgress(features, 51, new Coordinate(100, 100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void excludesUnknownFutureNullAndEmptyGeometriesFromOwnOksSelection() throws Exception {
        List<ImportedOfficialFeature> features = List.of(
                feature("future", "oks_future", null, "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"),
                feature("unknown", "restriction", "unknown", "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))"),
                feature("empty", "oks_existing", null, "POLYGON EMPTY"),
                new ImportedOfficialFeature("null", "oks_existing", mapper.createObjectNode(), null));
        Coordinate connection = new Coordinate(10, 10);
        Coordinate target = new Coordinate(-50, 10);
        assertThat(rules.normalEgress(features, 50, connection)).isEmpty();
        assertThat(rules.normalEgressTowards(features, 50, connection, target)).isEmpty();
        assertThat(rules.normalEgressCandidates(features, 50, connection, target, 60)).isEmpty();
    }

    private void assertNoEgress(List<ImportedOfficialFeature> features, int diameter, Coordinate start) {
        assertThat(rules.normalEgress(features, diameter, start)).isEmpty();
        Coordinate target = new Coordinate(start.x + 80, start.y + 43);
        assertThat(rules.normalEgressTowards(features, diameter, start, target)).isEmpty();
        for (double extraM : List.of(0.0, 60.0)) {
            assertThat(rules.normalEgressTowards(features, diameter, start, target, extraM)).isEmpty();
            assertThat(rules.normalEgressCandidates(features, diameter, start, target, extraM)).isEmpty();
        }
    }

    private void assertSameEgress(Optional<OfficialRouteGeometryRules.NormalEgress> expected,
            Optional<OfficialRouteGeometryRules.NormalEgress> actual) {
        assertThat(actual.isPresent()).isEqualTo(expected.isPresent());
        if (expected.isEmpty()) return;
        assertThat(actual.orElseThrow().oksId()).isEqualTo(expected.orElseThrow().oksId());
        assertThat(actual.orElseThrow().start().distance(expected.orElseThrow().start())).isLessThan(1e-7);
        assertThat(actual.orElseThrow().exit().distance(expected.orElseThrow().exit())).isLessThan(1e-7);
        assertThat(actual.orElseThrow().terminalExemptionIds())
                .containsExactlyInAnyOrderElementsOf(expected.orElseThrow().terminalExemptionIds());
    }

    /** Оракул использует реальные стены и явно заданные допустимые проекции, а не алгоритм выходов. */
    private void assertNormalInvariant(EgressCase sample, int diameter, OfficialRouteGeometryRules.NormalEgress egress) {
        Geometry footprint = sample.features.get(0).getMetricGeometry();
        assertThat(egress.oksId()).as(sample.name()).isEqualTo(sample.name());
        assertThat(egress.terminalExemptionIds()).containsExactly(sample.name());
        assertThat(egress.start().distance(sample.start)).isLessThan(1e-7);
        LineString leg = rules.line(List.of(egress.start(), egress.exit()));
        Coordinate[] crossings = leg.intersection(footprint.getBoundary()).getCoordinates();
        assertThat(crossings).as(sample.name() + " single wall crossing").hasSize(1);
        Coordinate wall = crossings[0];
        assertThat(sample.walls).as(sample.name() + " nearest permitted wall")
                .anyMatch(expected -> wall.distance(expected) < 1e-7);
        Geometry boundary = footprint.getBoundary();
        boolean perpendicular = false;
        for (int ring = 0; ring < boundary.getNumGeometries(); ring++) {
            Coordinate[] vertices = boundary.getGeometryN(ring).getCoordinates();
            for (int i = 1; i < vertices.length; i++) {
                LineSegment segment = new LineSegment(vertices[i - 1], vertices[i]);
                if (segment.getLength() == 0 || segment.distance(wall) > 1e-7) continue;
                double dot = (vertices[i].x - vertices[i - 1].x) * (egress.exit().x - sample.start.x)
                        + (vertices[i].y - vertices[i - 1].y) * (egress.exit().y - sample.start.y);
                if (Math.abs(dot) / (segment.getLength() * leg.getLength()) < 1e-7) perpendicular = true;
            }
        }
        assertThat(perpendicular).as(sample.name() + " perpendicular to an actual wall").isTrue();
        assertThat(egress.exit().distance(expectedExit(sample.start, wall, exteriorApproachM(diameter))))
                .as(sample.name() + " full exterior approach").isLessThan(1e-7);
        assertThat(footprint.distance(footprint.getFactory().createPoint(egress.exit())))
                .isCloseTo(exteriorApproachM(diameter), offset(1e-7));
        assertThat(rules.line(List.of(wall, egress.exit())).intersection(footprint).getLength())
                .as(sample.name() + " no reentry into another wing or component").isLessThan(1e-7);
        for (ImportedOfficialFeature foreign : sample.features) {
            if (!"restriction".equals(foreign.getObjectType())) continue;
            // В корпусе чужие запреты — парки; проверяем весь отрезок, а не только его конец.
            assertThat(leg.disjoint(foreign.getMetricGeometry())).as(foreign.getFeatureId()).isTrue();
            assertThat(leg.distance(foreign.getMetricGeometry())).as(foreign.getFeatureId())
                    .isGreaterThanOrEqualTo(parkAxisClearanceM(diameter));
        }
    }

    private Coordinate expectedExit(Coordinate start, Coordinate wall, double approach) {
        double distance = start.distance(wall);
        return new Coordinate(wall.x + (wall.x - start.x) * approach / distance,
                wall.y + (wall.y - start.y) * approach / distance);
    }

    private double exteriorApproachM(int diameter) {
        // Опубликованные R + W/2 и 0,25 м наружного запаса; ожидания не читаются из production helper.
        switch (diameter) {
            case 50: return 5.0 + 0.200 + 0.25;
            case 500: return 7.0 + 0.835 + 0.25;
            case 900: return 9.0 + 1.225 + 0.25;
            default: throw new IllegalArgumentException("No corpus expectation for DU " + diameter);
        }
    }

    private double parkAxisClearanceM(int diameter) {
        // Просвет 1 м плюс половина табличной ширины пары; запас ввода 0,25 м сюда не добавляется.
        switch (diameter) {
            case 50: return 1.200;
            case 500: return 1.835;
            case 900: return 2.225;
            default: throw new IllegalArgumentException("No corpus expectation for DU " + diameter);
        }
    }

    private EgressCase egressCase(String name, String wkt, Coordinate start, Coordinate... nearestWalls)
            throws Exception {
        return new EgressCase(new ArrayList<>(List.of(
                feature(name, "oks_existing", null, wkt),
                feature("ignored-future", "oks_future", null,
                        "POLYGON ((-100 -100, 200 -100, 200 100, -100 100, -100 -100))"),
                feature("remote-park", "restriction", "park",
                        "POLYGON ((200 0, 220 0, 220 20, 200 20, 200 0))"))), start, List.of(nearestWalls));
    }

    private List<List<ImportedOfficialFeature>> featurePermutations(List<ImportedOfficialFeature> features) {
        List<ImportedOfficialFeature> reversed = new ArrayList<>(features);
        Collections.reverse(reversed);
        List<ImportedOfficialFeature> reversedRings = new ArrayList<>();
        List<ImportedOfficialFeature> reorderedRings = new ArrayList<>();
        for (ImportedOfficialFeature feature : reversed) {
            Geometry geometry = feature.getMetricGeometry().reverse();
            reversedRings.add(new ImportedOfficialFeature(feature.getFeatureId(), feature.getObjectType(),
                    feature.getAttributes(), geometry));
            Geometry normalized = geometry.copy();
            normalized.normalize();
            reorderedRings.add(new ImportedOfficialFeature(feature.getFeatureId(), feature.getObjectType(),
                    feature.getAttributes(), normalized));
        }
        return List.of(features, reversed, reversedRings, reorderedRings);
    }

    private static final class EgressCase {
        private final List<ImportedOfficialFeature> features;
        private final Coordinate start;
        private final List<Coordinate> walls;

        private EgressCase(List<ImportedOfficialFeature> features, Coordinate start, List<Coordinate> walls) {
            this.features = features;
            this.start = start;
            this.walls = walls;
        }

        private String name() { return features.get(0).getFeatureId(); }
    }

    private ImportedOfficialFeature feature(String id, String objectType, String restrictionType, String wkt)
            throws Exception {
        var attributes = mapper.createObjectNode();
        if (restrictionType != null) {
            attributes.put("restriction_type", restrictionType);
        }
        return new ImportedOfficialFeature(id, objectType, attributes, new WKTReader().read(wkt));
    }

    private CountingPolygon countedSquare(double offset) throws Exception {
        Polygon polygon = (Polygon) new WKTReader().read("POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))");
        polygon.apply((org.locationtech.jts.geom.CoordinateFilter) coordinate -> coordinate.x += offset);
        polygon.geometryChanged();
        return new CountingPolygon(polygon);
    }

    private static final class CountingPolygon extends Polygon {
        private int bufferCalls;

        private CountingPolygon(Polygon source) {
            super(source.getExteriorRing(), null, source.getFactory());
        }

        @Override
        public Geometry buffer(double distance, int quadrantSegments) {
            bufferCalls++;
            return super.buffer(distance, quadrantSegments);
        }
    }
}
