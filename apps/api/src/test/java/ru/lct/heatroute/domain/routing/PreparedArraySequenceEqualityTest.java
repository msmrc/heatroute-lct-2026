package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;

class PreparedArraySequenceEqualityTest {
    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 32637);

    @Test
    void exactArraySequencesMatchFrozenPredicateForXyXyzXymAndXyzm() {
        assertFrozenMatch(line(new Coordinate[] {new org.locationtech.jts.geom.CoordinateXY(1, 2),
                new org.locationtech.jts.geom.CoordinateXY(3, 4)}, 2, 0),
                line(new Coordinate[] {new org.locationtech.jts.geom.CoordinateXY(1, 2),
                        new org.locationtech.jts.geom.CoordinateXY(3, 4)}, 2, 0));
        assertFrozenMatch(line(new Coordinate[] {new Coordinate(1, 2, 3), new Coordinate(4, 5, 6)}, 3, 0),
                line(new Coordinate[] {new Coordinate(1, 2, 3), new Coordinate(4, 5, 9)}, 3, 0));
        assertFrozenMatch(line(new Coordinate[] {new org.locationtech.jts.geom.CoordinateXYM(1, 2, 3),
                new org.locationtech.jts.geom.CoordinateXYM(4, 5, 6)}, 3, 1),
                line(new Coordinate[] {new org.locationtech.jts.geom.CoordinateXYM(1, 2, 3),
                        new org.locationtech.jts.geom.CoordinateXYM(4, 5, 9)}, 3, 1));

        double nanOne = Double.longBitsToDouble(0x7ff8000000000001L);
        double nanTwo = Double.longBitsToDouble(0x7ff8000000000002L);
        assertFrozenMatch(xyzm(0.0, 2, nanOne, 4, 5, 6, 7, 8),
                xyzm(-0.0, 2, nanOne, 4, 5, 6, 7, 8));
        assertFrozenMatch(xyzm(1, 2, nanOne, 4, 5, 6, 7, 8),
                xyzm(1, 2, nanTwo, 4, 5, 6, 7, 8));
        assertFrozenMatch(xyzm(1, 2, 3, nanOne, 5, 6, 7, 8),
                xyzm(1, 2, 3, nanTwo, 5, 6, 7, 8));
        assertFrozenMatch(xyzm(1, 2, nanOne, 4, 5, 6, 7, 8),
                xyzm(1, 2, nanOne, 4, 5, 6, 7, 8));
    }

    @Test
    void mixedCoordinateArraysAndDeclaredLayoutsKeepFrozenBehavior() {
        Coordinate[] mixedLeft = new Coordinate[] {
                new org.locationtech.jts.geom.CoordinateXY(1, 2),
                new org.locationtech.jts.geom.CoordinateXYZM(3, 4, 5, 6)};
        Coordinate[] mixedRight = new Coordinate[] {
                new org.locationtech.jts.geom.CoordinateXY(1, 2),
                new org.locationtech.jts.geom.CoordinateXYZM(3, 4, 5, 9)};
        assertFrozenMatch(line(mixedLeft, 4, 1), line(mixedRight, 4, 1));

        Coordinate[] ordinary = new Coordinate[] {new Coordinate(1, 2, 3), new Coordinate(4, 5, 6)};
        assertFrozenMatch(line(ordinary, 1, 0), line(ordinary, 1, 0));
        assertFrozenMatch(line(ordinary, 4, 0), line(ordinary, 4, 0));
        assertFrozenMatch(line(ordinary, 3, 1), line(ordinary, 3, 1));

        CoordinateArraySequence mismatchedMeasures = new CoordinateArraySequence(ordinary, 4, 1);
        assertFrozenMatch(line(mismatchedMeasures), line(new CoordinateArraySequence(ordinary, 4, 0)));
    }

    @Test
    void arrayAndPackedSequencesRemainDifferentiallyEquivalent() {
        Coordinate[] xyzm = new Coordinate[] {
                new org.locationtech.jts.geom.CoordinateXYZM(1, 2, 3, 4),
                new org.locationtech.jts.geom.CoordinateXYZM(5, 6, 7, 8)};
        assertFrozenMatch(line(new CoordinateArraySequence(xyzm, 4, 1)),
                line(new PackedCoordinateSequence.Double(xyzm, 4, 1)));
        assertFrozenMatch(line(new CoordinateArraySequence(new Coordinate[] {
                        new Coordinate(1, 2), new Coordinate(3, 4)}, 2, 0)),
                line(new PackedCoordinateSequence.Float(new Coordinate[] {
                        new Coordinate(1, 2), new Coordinate(3, 4)}, 2, 0)));
        assertFrozenMatch(line(new PackedCoordinateSequence.Double(xyzm, 4, 1)),
                line(new PackedCoordinateSequence.Double(new Coordinate[] {
                        new org.locationtech.jts.geom.CoordinateXYZM(1, 2, 3, 4),
                        new org.locationtech.jts.geom.CoordinateXYZM(5, 6, 7, 9)}, 4, 1)));
    }

    @Test
    void customCoordinatesAndSequencesFallBackWithTheSameResultsAndExceptions() {
        LineString tolerantLeft = line(new Coordinate[] {
                new AlwaysEqualCoordinate(1, 2), new AlwaysEqualCoordinate(3, 4)}, 2, 0);
        LineString tolerantRight = line(new Coordinate[] {
                new AlwaysEqualCoordinate(10, 2), new AlwaysEqualCoordinate(30, 4)}, 2, 0);
        assertFrozenMatch(tolerantLeft, tolerantRight);

        Coordinate[] ordinary = new Coordinate[] {new Coordinate(1, 2, 3), new Coordinate(4, 5, 6)};
        CoordinateArraySequence altered = new CoordinateArraySequence(ordinary, 3, 0) {
            @Override
            public double getOrdinate(int index, int ordinate) {
                double value = super.getOrdinate(index, ordinate);
                return index == 1 && ordinate == Coordinate.Z ? value + 0.5 : value;
            }
        };
        assertFrozenMatch(line(new CoordinateArraySequence(ordinary, 3, 0)), line(altered));

        CoordinateArraySequence throwing = new CoordinateArraySequence(ordinary, 3, 0) {
            @Override
            public double getOrdinate(int index, int ordinate) {
                if (index == 1 && ordinate == Coordinate.Z) throw new IllegalStateException("frozen ordinate");
                return super.getOrdinate(index, ordinate);
            }
        };
        CoordinateArraySequence throwingRight = new CoordinateArraySequence(ordinary, 3, 0) {
            @Override
            public double getOrdinate(int index, int ordinate) {
                if (index == 1 && ordinate == Coordinate.Z) throw new IllegalStateException("frozen ordinate");
                return super.getOrdinate(index, ordinate);
            }
        };
        assertFrozenMatch(line(throwing), line(throwingRight));
    }

    private static LineString xyzm(double x0, double y0, double z0, double m0,
            double x1, double y1, double z1, double m1) {
        return line(new Coordinate[] {
                new org.locationtech.jts.geom.CoordinateXYZM(x0, y0, z0, m0),
                new org.locationtech.jts.geom.CoordinateXYZM(x1, y1, z1, m1)}, 4, 1);
    }

    private static LineString line(Coordinate[] coordinates, int dimension, int measures) {
        return line(new CoordinateArraySequence(coordinates, dimension, measures));
    }

    private static LineString line(CoordinateSequence sequence) {
        return FACTORY.createLineString(sequence);
    }

    private static void assertFrozenMatch(Geometry left, Geometry right) {
        Outcome expected = Outcome.capture(() -> frozenSameGeometry(left, right));
        Outcome actual = Outcome.capture(() -> PreparedRoutingConstraints.sameGeometry(left, right));
        assertThat(actual.error == null).isEqualTo(expected.error == null);
        if (expected.error != null) {
            assertThat(actual.error.getClass()).isEqualTo(expected.error.getClass());
            assertThat(actual.error.getMessage()).isEqualTo(expected.error.getMessage());
        } else {
            assertThat(actual.value).isEqualTo(expected.value);
        }
    }

    // Frozen R10 contract, before CoordinateArraySequence started reading each Coordinate once.
    private static boolean frozenSameGeometry(Geometry left, Geometry right) {
        int comparison = frozenCompareStandardGeometry(left, right);
        return comparison < 0 ? frozenSameGeometryLegacy(left, right) : comparison == 1;
    }

    private static int frozenCompareStandardGeometry(Geometry left, Geometry right) {
        if (!sameMetadata(left, right)) return 0;
        Class<?> type = left.getClass();
        if (type == Point.class) {
            return frozenCompareStandardSequence(((Point) left).getCoordinateSequence(),
                    ((Point) right).getCoordinateSequence());
        }
        if (type == LineString.class || type == LinearRing.class) {
            return frozenCompareStandardSequence(((LineString) left).getCoordinateSequence(),
                    ((LineString) right).getCoordinateSequence());
        }
        if (type == Polygon.class) {
            Polygon first = (Polygon) left, second = (Polygon) right;
            if (first.getNumInteriorRing() != second.getNumInteriorRing()) return 0;
            int shell = frozenCompareStandardGeometry(first.getExteriorRing(), second.getExteriorRing());
            if (shell != 1) return shell;
            for (int index = 0; index < first.getNumInteriorRing(); index++) {
                int hole = frozenCompareStandardGeometry(first.getInteriorRingN(index), second.getInteriorRingN(index));
                if (hole != 1) return hole;
            }
            return 1;
        }
        if (type == GeometryCollection.class || type == MultiPoint.class
                || type == MultiLineString.class || type == MultiPolygon.class) {
            if (left.getNumGeometries() != right.getNumGeometries()) return 0;
            for (int index = 0; index < left.getNumGeometries(); index++) {
                int child = frozenCompareStandardGeometry(left.getGeometryN(index), right.getGeometryN(index));
                if (child != 1) return child;
            }
            return 1;
        }
        return -1;
    }

    private static int frozenCompareStandardSequence(CoordinateSequence left, CoordinateSequence right) {
        if (!standardSequenceClass(left.getClass()) || !standardSequenceClass(right.getClass())) return -1;
        if (left.size() != right.size() || left.getDimension() != right.getDimension()
                || left.getMeasures() != right.getMeasures()) return 0;
        int dimension = left.getDimension();
        if (dimension < 2) return -1;
        for (int index = 0; index < left.size(); index++) {
            if ((left.getClass() == CoordinateArraySequence.class
                    && !standardCoordinateClass(left.getCoordinate(index).getClass()))
                    || (right.getClass() == CoordinateArraySequence.class
                    && !standardCoordinateClass(right.getCoordinate(index).getClass()))) return -1;
            for (int ordinate = 0; ordinate < dimension; ordinate++) {
                double first = left.getOrdinate(index, ordinate);
                double second = right.getOrdinate(index, ordinate);
                if (ordinate < 2 && (!Double.isFinite(first) || !Double.isFinite(second))) return -1;
                if (Double.doubleToRawLongBits(first) != Double.doubleToRawLongBits(second)) return 0;
            }
        }
        return 1;
    }

    private static boolean standardSequenceClass(Class<?> type) {
        return type == CoordinateArraySequence.class || type == PackedCoordinateSequence.Double.class
                || type == PackedCoordinateSequence.Float.class;
    }

    private static boolean standardCoordinateClass(Class<?> type) {
        return type == Coordinate.class || type == org.locationtech.jts.geom.CoordinateXY.class
                || type == org.locationtech.jts.geom.CoordinateXYM.class
                || type == org.locationtech.jts.geom.CoordinateXYZM.class;
    }

    private static boolean frozenSameGeometryLegacy(Geometry left, Geometry right) {
        if (!sameMetadata(left, right) || !left.equalsExact(right)) return false;
        if (left instanceof Point) {
            return frozenSequence(((Point) left).getCoordinateSequence(), ((Point) right).getCoordinateSequence());
        }
        if (left instanceof LineString) {
            return frozenSequence(((LineString) left).getCoordinateSequence(),
                    ((LineString) right).getCoordinateSequence());
        }
        if (left instanceof Polygon) {
            Polygon first = (Polygon) left, second = (Polygon) right;
            if (!frozenSameGeometryLegacy(first.getExteriorRing(), second.getExteriorRing())) return false;
            for (int index = 0; index < first.getNumInteriorRing(); index++) {
                if (!frozenSameGeometryLegacy(first.getInteriorRingN(index), second.getInteriorRingN(index))) return false;
            }
            return true;
        }
        if (left instanceof GeometryCollection) {
            for (int index = 0; index < left.getNumGeometries(); index++) {
                if (!frozenSameGeometryLegacy(left.getGeometryN(index), right.getGeometryN(index))) return false;
            }
            return true;
        }
        return false;
    }

    private static boolean frozenSequence(CoordinateSequence left, CoordinateSequence right) {
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

    private static boolean sameMetadata(Geometry left, Geometry right) {
        return left.getClass() == right.getClass()
                && left.getSRID() == right.getSRID()
                && left.getFactory().getSRID() == right.getFactory().getSRID()
                && left.getFactory().getClass() == right.getFactory().getClass()
                && left.getFactory().getCoordinateSequenceFactory() == right.getFactory().getCoordinateSequenceFactory()
                && left.getPrecisionModel().equals(right.getPrecisionModel());
    }

    private static final class Outcome {
        private final Boolean value;
        private final Throwable error;

        private Outcome(Boolean value, Throwable error) {
            this.value = value;
            this.error = error;
        }

        private static Outcome capture(BooleanSupplier predicate) {
            try {
                return new Outcome(predicate.getAsBoolean(), null);
            } catch (Throwable error) {
                return new Outcome(null, error);
            }
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
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
