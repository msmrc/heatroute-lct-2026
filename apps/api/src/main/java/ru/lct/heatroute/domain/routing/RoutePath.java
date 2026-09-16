package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;

final class RoutePath {
    private final List<Coordinate> coordinates;
    private final List<RouteSection> sections;
    private final double lengthM;

    RoutePath(List<Coordinate> coordinates, List<RouteSection> sections, double lengthM) {
        this.coordinates = Collections.unmodifiableList(coordinates.stream()
                .map(Coordinate::new)
                .collect(Collectors.toList()));
        this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
        this.lengthM = lengthM;
    }

    List<Coordinate> coordinates() { return coordinates; }
    List<RouteSection> sections() { return sections; }
    double lengthM() { return lengthM; }

    RoutePath reversed() {
        List<Coordinate> reversedCoordinates = new ArrayList<>(coordinates);
        Collections.reverse(reversedCoordinates);
        List<RouteSection> reversedSections = new ArrayList<>();
        for (int index = sections.size() - 1; index >= 0; index--) {
            RouteSection source = sections.get(index);
            List<RouteCoordinate> coordinates = new ArrayList<>(source.getCoordinates());
            Collections.reverse(coordinates);
            reversedSections.add(new RouteSection(
                    source.getKind(),
                    source.getRestrictionType(),
                    source.getRestrictionId(),
                    coordinates,
                    source.getLengthM().doubleValue(),
                    source.getCrossingAngleDegrees() == null
                            ? null
                            : source.getCrossingAngleDegrees().doubleValue()));
        }
        return new RoutePath(reversedCoordinates, reversedSections, lengthM);
    }
}
