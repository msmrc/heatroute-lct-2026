package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PreparedIncomingDirectionTest {
    private static final double RIGHT_ANGLE = Math.PI / 2.0;
    private static final double MINIMUM_TURN = Math.PI / 3.0;
    private static final double VECTOR_ROUNDING_ERROR_M = Math.sqrt(2.0) * 0.001;
    private static final double MAX_ROUNDING_TOLERANCE = Math.toRadians(0.1);
    private static final double COLLINEAR_TOLERANCE = Math.toRadians(0.5);
    private static final double FLOATING_POINT_TOLERANCE = 1e-12;

    @Test
    void widerRawConesKeepPriorDecisionAtRatioAndScaleBoundaries() {
        for (double scaleEdge : new double[] {0x1.0p-256, 1.0, 0x1.0p256}) {
            for (double scale : new double[] {Math.nextDown(scaleEdge), scaleEdge, Math.nextUp(scaleEdge)}) {
                for (double ratio : new double[] {1.0 / 32.0, 1.5, 2.0, 16.0}) {
                    for (double adjacent : new double[] {Math.nextDown(ratio), ratio, Math.nextUp(ratio)}) {
                        for (double xSign : new double[] {-1.0, 1.0}) {
                            for (double ySign : new double[] {-1.0, 1.0}) {
                                assertPreparedMatches(1, 0, xSign * scale, ySign * adjacent * scale);
                                assertPreparedMatches(1, 1, (xSign - ySign * adjacent) * scale,
                                        (xSign + ySign * adjacent) * scale);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void rawAcceptAndObtuseRejectKeepPriorDecisionAtDotFloor() {
        for (double scale : new double[] {0x1.0p-256, 0.001, 1.0, 0x1.0p256}) {
            double floor = scale / 16.0;
            for (double dot : new double[] {Math.nextDown(floor), floor, Math.nextUp(floor)}) {
                assertPreparedMatches(1, 0, dot, scale);
                assertPreparedMatches(1, 0, -dot, scale);
                assertPreparedMatches(0, 1, scale, dot);
                assertPreparedMatches(0, 1, scale, -dot);
            }
        }
    }

    @Test
    void widerRawConesKeepPriorDecisionAfterRotationAndExtremeScaling() {
        for (double boundary : new double[] {Math.atan(1.5), Math.atan(2.0), Math.PI - Math.atan(16.0)}) {
            for (double angle : new double[] {Math.nextDown(boundary), boundary, Math.nextUp(boundary)}) {
                for (double rotation : new double[] {0.0, 0.371, Math.PI / 2.0, Math.PI, -2.17}) {
                    for (double scale : new double[] {Double.MIN_VALUE, Double.MIN_NORMAL,
                            0x1.0p-256, 0.001, 1.0, 0x1.0p256, Double.MAX_VALUE}) {
                        assertPreparedMatches(Math.cos(rotation) * scale, Math.sin(rotation) * scale,
                                Math.cos(rotation + angle) * scale, Math.sin(rotation + angle) * scale);
                    }
                }
            }
        }
    }

    @Test
    void rawRejectKeepsPriorDecisionAtScaleGuardAndCancellationBoundaries() {
        for (double scaleBoundary : new double[] {0x1.0p-256, 0x1.0p256}) {
            for (double scale : new double[] {Math.nextDown(scaleBoundary), scaleBoundary,
                    Math.nextUp(scaleBoundary)}) {
                for (double ratio : new double[] {0.0, 1.0 / 32.0, 1.0 / 16.0, 1.0, 2.0}) {
                    for (double adjacent : new double[] {Math.nextDown(ratio), ratio, Math.nextUp(ratio)}) {
                        assertPreparedMatches(1.0, 0.0, scale, adjacent * scale);
                        assertPreparedMatches(1.0, 0.0, -scale, adjacent * scale);
                        assertPreparedMatches(1.0, 1.0, scale, (-1.0 + adjacent) * scale);
                        assertPreparedMatches(1.0, -1.0, scale, (1.0 - adjacent) * scale);
                    }
                }
            }
        }
    }

    @Test
    void rawRejectKeepsPriorDecisionForMixedScaleComponents() {
        double[] scales = {Double.MIN_VALUE, Double.MIN_NORMAL, 0x1.0p-512,
                0x1.0p-256, 1.0, 0x1.0p256, 0x1.0p512, Double.MAX_VALUE};
        for (double major : scales) for (double minor : scales) {
            for (double sign : new double[] {-1.0, 1.0}) {
                assertPreparedMatches(major, minor, sign * minor, major);
                assertPreparedMatches(minor, major, major, sign * minor);
                assertPreparedMatches(1.0, sign * Double.MIN_VALUE, major, minor);
                assertPreparedMatches(Double.MIN_VALUE, sign, minor, major);
            }
        }
    }

    @Test
    void internalConesKeepPriorDecisionAtRatioBoundariesAndExtremeScales() {
        for (double ratio : new double[] {1.0 / 32.0, 1.0, 2.0}) {
            for (double adjacent : new double[] {Math.nextDown(ratio), ratio, Math.nextUp(ratio)}) {
                for (double scale : new double[] {Double.MIN_VALUE, Double.MIN_NORMAL,
                        0.001, 1.0, 1e150, Double.MAX_VALUE / 4.0}) {
                    for (double xSign : new double[] {-1.0, 1.0}) {
                        for (double ySign : new double[] {-1.0, 1.0}) {
                            assertPreparedMatches(scale, 0, xSign * scale, ySign * adjacent * scale);
                        }
                    }
                }
            }
        }
    }

    @Test
    void internalConesKeepPriorDecisionForSpecialComponents() {
        double[] special = {0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MIN_NORMAL, -Double.MIN_NORMAL, 1.0, -1.0,
                Double.MAX_VALUE, -Double.MAX_VALUE, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double inX : special) for (double inY : special) {
            for (double outX : special) for (double outY : special) {
                assertPreparedMatches(inX, inY, outX, outY);
            }
        }
    }

    @Test
    void internalConesKeepPriorDecisionForSeededRawBitsAndMillimetreVectors() {
        Random random = new Random(0xC0AE12L);
        for (int index = 0; index < 4096; index++) {
            assertPreparedMatches(Double.longBitsToDouble(random.nextLong()),
                    Double.longBitsToDouble(random.nextLong()),
                    Double.longBitsToDouble(random.nextLong()),
                    Double.longBitsToDouble(random.nextLong()));
            assertPreparedMatches((random.nextInt(2000001) - 1000000) / 1000.0,
                    (random.nextInt(2000001) - 1000000) / 1000.0,
                    (random.nextInt(2000001) - 1000000) / 1000.0,
                    (random.nextInt(2000001) - 1000000) / 1000.0);
        }
    }

    @Test
    void preparedDirectionMatchesPriorPredicateAtEveryAngularBoundaryUlp() {
        for (double boundary : new double[] {Math.toRadians(0.5), MINIMUM_TURN, RIGHT_ANGLE}) {
            for (double angle : new double[] {Math.nextDown(boundary), boundary, Math.nextUp(boundary)}) {
                for (double length : new double[] {0.01, 1.0, 1000.0}) {
                    double rotation = 0.371;
                    assertPreparedMatches(length * Math.cos(rotation), length * Math.sin(rotation),
                            length * Math.cos(rotation + angle), length * Math.sin(rotation + angle));
                }
            }
        }
    }

    @Test
    void preparedDirectionMatchesRoundedUtmVectors() {
        for (double rotation : new double[] {0.0, 0.37, 1.42}) {
            for (double angle : new double[] {0.0, Math.toRadians(0.5), MINIMUM_TURN, RIGHT_ANGLE}) {
                double baseX = 414_000.123, baseY = 6_173_500.789;
                double length = 37.0;
                RouteCoordinate at = new RouteCoordinate(baseX, baseY);
                RouteCoordinate previous = new RouteCoordinate(baseX - length * Math.cos(rotation),
                        baseY - length * Math.sin(rotation));
                RouteCoordinate next = new RouteCoordinate(baseX + length * Math.cos(rotation + angle),
                        baseY + length * Math.sin(rotation + angle));
                double inX = (at.getXM().movePointRight(3).doubleValue()
                        - previous.getXM().movePointRight(3).doubleValue()) / 1000.0;
                double inY = (at.getYM().movePointRight(3).doubleValue()
                        - previous.getYM().movePointRight(3).doubleValue()) / 1000.0;
                double outX = (next.getXM().movePointRight(3).doubleValue()
                        - at.getXM().movePointRight(3).doubleValue()) / 1000.0;
                double outY = (next.getYM().movePointRight(3).doubleValue()
                        - at.getYM().movePointRight(3).doubleValue()) / 1000.0;
                assertPreparedMatches(inX, inY, outX, outY);
            }
        }
    }

    @Test
    void preparedDirectionMatchesAtToleranceAdjustedDecisionBoundaries() {
        for (double inLength : new double[] {0.001, 1.0, 1000.0}) {
            for (double outLength : new double[] {0.007, 37.0, 1200.0}) {
                double tolerance = Math.min(MAX_ROUNDING_TOLERANCE,
                        Math.asin(Math.min(1.0, VECTOR_ROUNDING_ERROR_M / inLength))
                                + Math.asin(Math.min(1.0, VECTOR_ROUNDING_ERROR_M / outLength)))
                        + FLOATING_POINT_TOLERANCE;
                for (double boundary : new double[] {COLLINEAR_TOLERANCE + FLOATING_POINT_TOLERANCE,
                        MINIMUM_TURN - tolerance, RIGHT_ANGLE + tolerance}) {
                    for (double angle : new double[] {Math.nextDown(boundary), boundary, Math.nextUp(boundary)}) {
                        for (double sign : new double[] {-1.0, 1.0}) {
                            assertPreparedMatches(inLength, 0, outLength * Math.cos(angle),
                                    sign * outLength * Math.sin(angle));
                        }
                    }
                }
            }
        }
    }

    @Test
    void preparedDirectionKeepsPriorInvalidAndExtremeBehavior() {
        for (double[] values : new double[][] {
                {0.0, 0.0, 1.0, 0.0}, {-0.0, 0.0, 1.0, 0.0},
                {1.0, -0.0, -0.0, 0.0}, {-0.0, -1.0, -0.0, 1.0},
                {Double.MIN_VALUE, 0.0, 0.0, Double.MIN_VALUE},
                {Math.scalb(1.0, -1022), Math.scalb(1.0, -1022), 1.0, 0.0},
                {Double.NaN, 0.0, 1.0, 0.0}, {1.0, 0.0, Double.NaN, 0.0},
                {Double.POSITIVE_INFINITY, 0.0, 1.0, 0.0}, {1.0, 0.0, Double.NEGATIVE_INFINITY, 0.0},
                {Double.MAX_VALUE, Double.MAX_VALUE, 1.0, 0.0},
                {1.0, 0.0, Double.MAX_VALUE, Double.MAX_VALUE},
                {1.0e200, -1.0e200, -1.0e200, 1.0e200}}) {
            assertPreparedMatches(values[0], values[1], values[2], values[3]);
        }
    }

    @Test
    void onePreparedIncomingCanBeAppliedToManyOutgoingDirections() {
        double inX = 12.345, inY = -67.89;
        OfficialRouteDeflectionRules.PreparedDirection prepared =
                OfficialRouteDeflectionRules.prepareDirection(inX, inY);
        for (double angle : new double[] {0.0, Math.nextUp(Math.toRadians(0.5)), MINIMUM_TURN,
                RIGHT_ANGLE, Math.nextUp(RIGHT_ANGLE), Math.PI}) {
            double outX = 91.0 * Math.cos(angle), outY = 91.0 * Math.sin(angle);
            boolean expected = priorAllowsTurn(inX, inY, outX, outY, true);
            assertThat(OfficialRouteDeflectionRules.allowsTurn(prepared, outX, outY)).isEqualTo(expected);
            assertThat(OfficialRouteDeflectionRules.allowsTurn(inX, inY, outX, outY)).isEqualTo(expected);
        }
    }

    @Test
    void seededMagnitudeAndRotationCombinationsMatchPriorPredicate() {
        Random random = new Random(0xD1EC710AL);
        for (int index = 0; index < 384; index++) {
            double incomingMagnitude = Math.scalb(1.0, -1070 + random.nextInt(2071));
            double outgoingMagnitude = Math.scalb(1.0, -1070 + random.nextInt(2071));
            double rotation = random.nextDouble() * 2.0 * Math.PI - Math.PI;
            double angle = index % 4 == 0 ? Math.nextUp(Math.toRadians(0.5))
                    : index % 4 == 1 ? Math.nextDown(MINIMUM_TURN)
                    : index % 4 == 2 ? Math.nextUp(RIGHT_ANGLE) : random.nextDouble() * Math.PI;
            assertPreparedMatches(incomingMagnitude * Math.cos(rotation), incomingMagnitude * Math.sin(rotation),
                    outgoingMagnitude * Math.cos(rotation + angle), outgoingMagnitude * Math.sin(rotation + angle));
        }
    }

    @Test
    void legacyAndJunctionPredicatesStillMatchTheirPriorModes() {
        for (double angle : new double[] {0.0, Math.nextDown(Math.toRadians(0.5)), Math.toRadians(0.5),
                Math.nextUp(Math.toRadians(0.5)), Math.nextDown(MINIMUM_TURN), MINIMUM_TURN,
                RIGHT_ANGLE, Math.nextUp(RIGHT_ANGLE)}) {
            double outX = 100.0 * Math.cos(angle), outY = 100.0 * Math.sin(angle);
            assertThat(OfficialRouteDeflectionRules.allowsTurn(100.0, 0.0, outX, outY))
                    .isEqualTo(priorAllowsTurn(100.0, 0.0, outX, outY, true));
            assertThat(OfficialRouteDeflectionRules.allowsJunctionContinuation(100.0, 0.0, outX, outY))
                    .isEqualTo(priorAllowsTurn(100.0, 0.0, outX, outY, false));
        }
    }

    private static void assertPreparedMatches(double inX, double inY, double outX, double outY) {
        boolean expected = priorAllowsTurn(inX, inY, outX, outY, true);
        OfficialRouteDeflectionRules.PreparedDirection prepared =
                OfficialRouteDeflectionRules.prepareDirection(inX, inY);
        assertThat(OfficialRouteDeflectionRules.allowsTurn(prepared, outX, outY)).isEqualTo(expected);
        assertThat(OfficialRouteDeflectionRules.allowsTurn(inX, inY, outX, outY)).isEqualTo(expected);
    }

    // Independent copy of the pre-preparation digitized-axis predicate.
    private static boolean priorAllowsTurn(double inX, double inY, double outX, double outY,
            boolean digitizedAxisTolerance) {
        if (!Double.isFinite(inX) || !Double.isFinite(inY) || !Double.isFinite(outX) || !Double.isFinite(outY)
                || inX == 0 && inY == 0 || outX == 0 && outY == 0) return false;
        double inLength = Math.hypot(inX, inY), outLength = Math.hypot(outX, outY);
        if (!Double.isFinite(inLength) || !Double.isFinite(outLength)) return false;
        double ax = inX / inLength, ay = inY / inLength, bx = outX / outLength, by = outY / outLength;
        double angle = Math.atan2(Math.abs(ax * by - ay * bx), ax * bx + ay * by);
        double rounding = Math.asin(Math.min(1.0, VECTOR_ROUNDING_ERROR_M / inLength))
                + Math.asin(Math.min(1.0, VECTOR_ROUNDING_ERROR_M / outLength));
        double tolerance = Math.min(MAX_ROUNDING_TOLERANCE, rounding) + FLOATING_POINT_TOLERANCE;
        double collinearTolerance = digitizedAxisTolerance
                ? COLLINEAR_TOLERANCE + FLOATING_POINT_TOLERANCE : tolerance;
        return angle <= collinearTolerance
                || angle + tolerance >= MINIMUM_TURN && angle <= RIGHT_ANGLE + tolerance;
    }

}
