package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class TerminalSuffixGeometryTest {
    @Test
    void replacesTheObtuseSuffixJoinByTwoRightAngles() {
        List<Coordinate> source = List.of(new Coordinate(900, 50), new Coordinate(50, 54));
        List<Coordinate> result = TerminalSuffixGeometry.append(source, new Coordinate(50, 50));
        assertThat(result).containsExactly(new Coordinate(900, 50), new Coordinate(900, 54),
                new Coordinate(50, 54), new Coordinate(50, 50));
        assertThat(source).hasSize(2);
        assertValid(result);
    }

    @Test
    void preservesLegalApproachesWithoutAddingBends() {
        List<Coordinate> source = List.of(new Coordinate(50, 100), new Coordinate(50, 54));
        assertThat(TerminalSuffixGeometry.append(source, new Coordinate(50, 50)))
                .containsExactly(source.get(0), source.get(1), new Coordinate(50, 50));
        assertThat(TerminalSuffixGeometry.append(source, new Coordinate(50, 54))).containsExactlyElementsOf(source);
    }

    @Test
    void isIndependentOfMapRotationAndTranslation() {
        for (double angle : new double[] {0, 0.17, 1.2, -2.1, Math.PI}) {
            List<Coordinate> source = List.of(move(900, 50, angle), move(50, 54, angle));
            List<Coordinate> actual = TerminalSuffixGeometry.append(source, move(50, 50, angle));
            assertThat(actual).hasSize(4);
            assertThat(actual.get(1).distance(move(900, 54, angle))).isLessThan(1e-6);
            assertValid(actual);
        }
    }

    @Test
    void doesNotHideEarlierIllegalTurnsOrDegenerateApproaches() {
        assertThat(TerminalSuffixGeometry.append(List.of(new Coordinate(0, 0)), new Coordinate(0, 1))).isEmpty();
        assertThat(TerminalSuffixGeometry.append(List.of(new Coordinate(0, 0), new Coordinate(10, 0),
                new Coordinate(1, 1), new Coordinate(50, 54)), new Coordinate(50, 50))).isEmpty();
        assertThat(TerminalSuffixGeometry.append(List.of(new Coordinate(0, 0), new Coordinate(0, 4)),
                new Coordinate(0, 0))).isEmpty();
    }

    private Coordinate move(double x, double y, double angle) {
        return new Coordinate(400_000 + x * Math.cos(angle) - y * Math.sin(angle),
                6_100_000 + x * Math.sin(angle) + y * Math.cos(angle));
    }

    private void assertValid(List<Coordinate> points) {
        assertThat(OfficialRouteDeflectionRules.validatePolyline("test", points.stream()
                .map(point -> new RouteCoordinate(point.x, point.y)).collect(Collectors.toList())).getIssues()).isEmpty();
    }
}
