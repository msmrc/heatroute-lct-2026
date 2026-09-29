package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;

class PreparedGeometryEqualityTest {
    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 32637);

    @Test
    void matchesPriorOracleForStandardKindsAndStructureChanges() {
        Polygon first = polygon(0, 0, 20, 20, ring(2, 2, 5, 5), ring(12, 12, 16, 16));
        Polygon second = polygon(30, 0, 50, 20);
        GeometryCollection nested = FACTORY.createGeometryCollection(new Geometry[] {
                FACTORY.createPoint(c(4, 5)), FACTORY.createGeometryCollection(new Geometry[] {first})});
        for (Geometry geometry : List.of(
                FACTORY.createPoint(c(1, 2)),
                FACTORY.createLineString(new Coordinate[] {c(0, 0), c(4, 5)}),
                ring(0, 0, 4, 4), first,
                FACTORY.createMultiPointFromCoords(new Coordinate[] {c(0, 0), c(1, 1)}),
                FACTORY.createMultiLineString(new LineString[] {
                        FACTORY.createLineString(new Coordinate[] {c(0, 0), c(1, 0)}),
                        FACTORY.createLineString(new Coordinate[] {c(2, 0), c(3, 0)})}),
                FACTORY.createMultiPolygon(new Polygon[] {first, second}), nested,
                FACTORY.createPoint(), FACTORY.createLineString(), FACTORY.createLinearRing(),
                FACTORY.createPolygon(), FACTORY.createMultiPoint(), FACTORY.createMultiLineString(),
                FACTORY.createMultiPolygon(), FACTORY.createGeometryCollection())) {
            assertComparison(geometry, geometry.copy(), true);
        }

        assertComparison(first, polygon(0, 0, 20, 20, ring(12, 12, 16, 16), ring(2, 2, 5, 5)), false);
        assertComparison(first, polygon(0, 0, 20, 20, ring(2, 2, 5, 5)), false);
        assertComparison(FACTORY.createLineString(ring(0, 0, 4, 4).getCoordinates()), ring(0, 0, 4, 4), false);
        assertComparison(FACTORY.createMultiPolygon(new Polygon[] {first}),
                FACTORY.createMultiPolygon(new Polygon[] {first, second}), false);
        assertComparison(FACTORY.createMultiPolygon(new Polygon[] {first, second}),
                FACTORY.createMultiPolygon(new Polygon[] {second, first}), false);
        assertComparison(nested, FACTORY.createGeometryCollection(new Geometry[] {
                FACTORY.createGeometryCollection(new Geometry[] {first}), FACTORY.createPoint(c(4, 5))}), false);
    }

    @Test
    void matchesPriorOracleForRawOrdinatesAndNonFiniteXy() {
        LineString xyzm = xyzmLine(0.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
        assertComparison(xyzm, xyzm.copy(), true);

        LineString zChanged = (LineString) xyzm.copy();
        zChanged.getCoordinateSequence().setOrdinate(1, Coordinate.Z, 50.0);
        zChanged.geometryChanged();
        assertComparison(xyzm, zChanged, false);

        LineString mChanged = (LineString) xyzm.copy();
        mChanged.getCoordinateSequence().setOrdinate(1, Coordinate.M, 60.0);
        mChanged.geometryChanged();
        assertComparison(xyzm, mChanged, false);

        LineString positiveZero = xyzmLine(0.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
        LineString negativeZero = xyzmLine(-0.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
        assertComparison(positiveZero, negativeZero, false);

        LineString xyChanged = (LineString) xyzm.copy();
        xyChanged.getCoordinateSequence().setOrdinate(1, Coordinate.X, 30.0);
        xyChanged.geometryChanged();
        assertComparison(xyzm, xyChanged, false);

        double nanOne = Double.longBitsToDouble(0x7ff8000000000001L);
        double nanTwo = Double.longBitsToDouble(0x7ff8000000000002L);
        LineString selfNan = xyzmLine(nanOne, 2, 3, 4, 5, 6, 7, 8);
        assertComparison(selfNan, selfNan, true);
        GeometryCollection sharedNan = FACTORY.createGeometryCollection(new Geometry[] {selfNan});
        assertComparison(sharedNan, sharedNan, true);
        assertComparison(sharedNan, FACTORY.createGeometryCollection(new Geometry[] {selfNan}), false);
        assertComparison(xyzmLine(nanOne, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0),
                xyzmLine(nanOne, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0), false);
        assertComparison(xyzmLine(nanOne, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0),
                xyzmLine(nanTwo, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0), false);
        assertComparison(xyzmLine(Double.POSITIVE_INFINITY, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0),
                xyzmLine(Double.POSITIVE_INFINITY, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0), true);
        assertComparison(xyzmLine(Double.POSITIVE_INFINITY, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0),
                xyzmLine(Double.NEGATIVE_INFINITY, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0), false);
        assertComparison(xyzmLine(1.0, 2.0, nanOne, 4.0, 5.0, 6.0, 7.0, 8.0),
                xyzmLine(1.0, 2.0, nanOne, 4.0, 5.0, 6.0, 7.0, 8.0), true);
        assertComparison(xyzmLine(1.0, 2.0, nanOne, 4.0, 5.0, 6.0, 7.0, 8.0),
                xyzmLine(1.0, 2.0, nanTwo, 4.0, 5.0, 6.0, 7.0, 8.0), false);
    }

    @Test
    void matchesPriorOracleForMetadataAndCoordinateLayouts() {
        LineString standard = FACTORY.createLineString(new Coordinate[] {c(0, 0), c(10, 0)});
        Geometry differentSrid = standard.copy();
        differentSrid.setSRID(4326);
        assertComparison(standard, differentSrid, false);

        GeometryFactory otherSridFactory = new GeometryFactory(new PrecisionModel(), 4326);
        LineString sameGeometryOtherFactorySrid = otherSridFactory.createLineString(new Coordinate[] {c(0, 0), c(10, 0)});
        sameGeometryOtherFactorySrid.setSRID(32637);
        assertComparison(standard, sameGeometryOtherFactorySrid, false);
        assertComparison(standard, new GeometryFactory(new PrecisionModel(10), 32637)
                .createLineString(new Coordinate[] {c(0, 0), c(10, 0)}), false);
        assertComparison(standard, new GeometryFactory(new PrecisionModel(), 32637) {
        }.createLineString(new Coordinate[] {c(0, 0), c(10, 0)}), false);

        GeometryFactory packed = new GeometryFactory(new PrecisionModel(), 32637,
                PackedCoordinateSequenceFactory.DOUBLE_FACTORY);
        assertComparison(standard, packed.createLineString(new Coordinate[] {c(0, 0), c(10, 0)}), false);
        LineString packedLine = packed.createLineString(new Coordinate[] {c(0, 0), c(10, 0)});
        assertComparison(packedLine, packedLine.copy(), true);
        GeometryFactory packedFloat = new GeometryFactory(new PrecisionModel(), 32637,
                PackedCoordinateSequenceFactory.FLOAT_FACTORY);
        LineString floatLine = packedFloat.createLineString(new Coordinate[] {c(0, 0), c(10, 0)});
        assertComparison(floatLine, floatLine.copy(), true);

        Coordinate[] coordinates = new Coordinate[] {c(0, 0), c(10, 0)};
        LineString twoDimensional = FACTORY.createLineString(new CoordinateArraySequence(coordinates, 2));
        LineString threeDimensional = FACTORY.createLineString(new CoordinateArraySequence(coordinates, 3));
        assertComparison(twoDimensional, threeDimensional, false);

        CoordinateArraySequence deceptive = new CoordinateArraySequence(coordinates, 2) {
            @Override
            public double getOrdinate(int index, int ordinate) {
                double value = super.getOrdinate(index, ordinate);
                return index == 1 && ordinate == Coordinate.X ? value + 0.25 : value;
            }
        };
        LineString customSequence = FACTORY.createLineString(deceptive);
        assertThat(twoDimensional.equalsExact(customSequence)).isTrue();
        assertComparison(twoDimensional, customSequence, false);
    }

    @Test
    void retainsCustomEqualsExactAndCoordinateSemantics() {
        CoordinateSequence sequence = new CoordinateArraySequence(new Coordinate[] {c(0, 0), c(10, 0)});
        LineString rejectingLeft = new RejectingLineString(sequence, FACTORY);
        LineString rejectingRight = new RejectingLineString(sequence.copy(), FACTORY);
        assertThat(rejectingLeft.equalsExact(rejectingRight)).isFalse();
        assertComparison(rejectingLeft, rejectingRight, false);

        LineString tolerantLeft = FACTORY.createLineString(new Coordinate[] {
                new AlwaysEqualCoordinate(0, 0), new AlwaysEqualCoordinate(10, 0)});
        LineString tolerantRight = FACTORY.createLineString(new Coordinate[] {
                new AlwaysEqualCoordinate(100, 0), new AlwaysEqualCoordinate(110, 0)});
        assertThat(tolerantLeft.equalsExact(tolerantRight)).isTrue();
        assertComparison(tolerantLeft, tolerantRight, false);
    }

    private static void assertComparison(Geometry left, Geometry right, boolean expected) {
        boolean oracle = priorOracle(left, right);
        assertThat(oracle).as("prior oracle: %s vs %s", left, right).isEqualTo(expected);
        assertThat(PreparedRoutingConstraints.sameGeometry(left, right))
                .as("sameGeometry: %s vs %s", left, right)
                .isEqualTo(oracle);
    }

    // Deliberately independent copy of the pre-optimization contract.
    private static boolean priorOracle(Geometry left, Geometry right) {
        if (left.getClass() != right.getClass()
                || left.getSRID() != right.getSRID()
                || left.getFactory().getSRID() != right.getFactory().getSRID()
                || left.getFactory().getClass() != right.getFactory().getClass()
                || left.getFactory().getCoordinateSequenceFactory()
                        != right.getFactory().getCoordinateSequenceFactory()
                || !left.getPrecisionModel().equals(right.getPrecisionModel())
                || !left.equalsExact(right)) {
            return false;
        }
        if (left instanceof Point) {
            return priorSequence(((Point) left).getCoordinateSequence(), ((Point) right).getCoordinateSequence());
        }
        if (left instanceof LineString) {
            return priorSequence(((LineString) left).getCoordinateSequence(),
                    ((LineString) right).getCoordinateSequence());
        }
        if (left instanceof Polygon) {
            Polygon leftPolygon = (Polygon) left;
            Polygon rightPolygon = (Polygon) right;
            if (!priorOracle(leftPolygon.getExteriorRing(), rightPolygon.getExteriorRing())) return false;
            for (int index = 0; index < leftPolygon.getNumInteriorRing(); index++) {
                if (!priorOracle(leftPolygon.getInteriorRingN(index), rightPolygon.getInteriorRingN(index))) return false;
            }
            return true;
        }
        if (left instanceof GeometryCollection) {
            for (int index = 0; index < left.getNumGeometries(); index++) {
                if (!priorOracle(left.getGeometryN(index), right.getGeometryN(index))) return false;
            }
            return true;
        }
        return false;
    }

    private static boolean priorSequence(CoordinateSequence left, CoordinateSequence right) {
        if (left.size() != right.size() || left.getDimension() != right.getDimension()
                || left.getMeasures() != right.getMeasures()) return false;
        for (int index = 0; index < left.size(); index++) {
            for (int ordinate = 0; ordinate < left.getDimension(); ordinate++) {
                if (Double.doubleToRawLongBits(left.getOrdinate(index, ordinate))
                        != Double.doubleToRawLongBits(right.getOrdinate(index, ordinate))) return false;
            }
        }
        return true;
    }

    private static Polygon polygon(double minX, double minY, double maxX, double maxY, LinearRing... holes) {
        return FACTORY.createPolygon(ring(minX, minY, maxX, maxY), holes);
    }

    private static LinearRing ring(double minX, double minY, double maxX, double maxY) {
        return FACTORY.createLinearRing(new Coordinate[] {
                c(minX, minY), c(maxX, minY), c(maxX, maxY), c(minX, maxY), c(minX, minY)});
    }

    private static LineString xyzmLine(double x0, double y0, double z0, double m0,
            double x1, double y1, double z1, double m1) {
        return FACTORY.createLineString(new Coordinate[] {
                new org.locationtech.jts.geom.CoordinateXYZM(x0, y0, z0, m0),
                new org.locationtech.jts.geom.CoordinateXYZM(x1, y1, z1, m1)});
    }

    private static Coordinate c(double x, double y) {
        return new Coordinate(x, y);
    }

    private static final class RejectingLineString extends LineString {
        private RejectingLineString(CoordinateSequence points, GeometryFactory factory) {
            super(points, factory);
        }

        @Override
        public boolean equalsExact(Geometry other, double tolerance) {
            return false;
        }
    }

    private static final class AlwaysEqualCoordinate extends Coordinate {
        private AlwaysEqualCoordinate(double x, double y) {
            super(x, y);
        }

        @Override
        public boolean equals2D(Coordinate other) {
            return true;
        }

        @Override
        public boolean equals2D(Coordinate other, double tolerance) {
            return true;
        }
    }
}
