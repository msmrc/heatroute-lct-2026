package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;

final class RoutePath {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
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

    Split splitAt(Coordinate requestedCut) {
        LineString line = line(coordinates);
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double cutIndex = indexed.project(requestedCut);
        if (cutIndex <= OfficialRouteGeometryRules.EPSILON_M
                || line.getLength() - cutIndex <= OfficialRouteGeometryRules.EPSILON_M) {
            return null;
        }
        Coordinate cut = indexed.extractPoint(cutIndex);
        List<Coordinate> upstreamCoordinates = coordinates(indexed.extractLine(0.0, cutIndex));
        List<Coordinate> downstreamCoordinates = coordinates(indexed.extractLine(cutIndex, line.getLength()));
        if (upstreamCoordinates.size() < 2 || downstreamCoordinates.size() < 2) {
            return null;
        }
        List<RouteSection> upstreamSections = new ArrayList<>();
        List<RouteSection> downstreamSections = new ArrayList<>();
        double sectionStart = 0.0;
        for (RouteSection section : sections) {
            List<Coordinate> sourceCoordinates = section.getCoordinates().stream()
                    .map(RouteCoordinate::toCoordinate)
                    .collect(Collectors.toList());
            if (sourceCoordinates.size() < 2) {
                continue;
            }
            LineString sectionLine = line(sourceCoordinates);
            double sectionEnd = sectionStart + sectionLine.getLength();
            if (cutIndex >= sectionEnd - OfficialRouteGeometryRules.EPSILON_M) {
                upstreamSections.add(copySection(section, sourceCoordinates));
            } else if (cutIndex <= sectionStart + OfficialRouteGeometryRules.EPSILON_M) {
                downstreamSections.add(copySection(section, sourceCoordinates));
            } else {
                double localCut = Math.max(0.0, Math.min(sectionLine.getLength(), cutIndex - sectionStart));
                LengthIndexedLine sectionIndexed = new LengthIndexedLine(sectionLine);
                List<Coordinate> before = coordinates(sectionIndexed.extractLine(0.0, localCut));
                List<Coordinate> after = coordinates(sectionIndexed.extractLine(localCut, sectionLine.getLength()));
                if (before.size() >= 2 && line(before).getLength() > OfficialRouteGeometryRules.EPSILON_M) {
                    upstreamSections.add(copySection(section, before));
                }
                if (after.size() >= 2 && line(after).getLength() > OfficialRouteGeometryRules.EPSILON_M) {
                    downstreamSections.add(copySection(section, after));
                }
            }
            sectionStart = sectionEnd;
        }
        return new Split(
                cut,
                new RoutePath(upstreamCoordinates, upstreamSections, line(upstreamCoordinates).getLength()),
                new RoutePath(downstreamCoordinates, downstreamSections, line(downstreamCoordinates).getLength()));
    }

    private static RouteSection copySection(RouteSection source, List<Coordinate> coordinates) {
        double length = line(coordinates).getLength();
        return new RouteSection(
                source.getKind(),
                source.getRestrictionType(),
                source.getRestrictionId(),
                coordinates.stream()
                        .map(coordinate -> new RouteCoordinate(coordinate.x, coordinate.y))
                        .collect(Collectors.toList()),
                length,
                source.getCrossingAngleDegrees() == null
                        ? null
                        : source.getCrossingAngleDegrees().doubleValue());
    }

    private static LineString line(List<Coordinate> coordinates) {
        return GEOMETRY_FACTORY.createLineString(coordinates.toArray(new Coordinate[0]));
    }

    private static List<Coordinate> coordinates(Geometry geometry) {
        List<Coordinate> result = new ArrayList<>();
        for (Coordinate coordinate : geometry.getCoordinates()) {
            if (result.isEmpty()
                    || result.get(result.size() - 1).distance(coordinate)
                            > OfficialRouteGeometryRules.EPSILON_M) {
                result.add(new Coordinate(coordinate));
            }
        }
        return result;
    }

    static final class Split {
        private final Coordinate coordinate;
        private final RoutePath upstream;
        private final RoutePath downstream;

        private Split(Coordinate coordinate, RoutePath upstream, RoutePath downstream) {
            this.coordinate = new Coordinate(coordinate);
            this.upstream = upstream;
            this.downstream = downstream;
        }

        Coordinate coordinate() { return new Coordinate(coordinate); }
        RoutePath upstream() { return upstream; }
        RoutePath downstream() { return downstream; }
    }

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

    RoutePath withMandatoryPrefix(Coordinate start) {
        if (coordinates.isEmpty()) {
            return this;
        }
        Coordinate first = coordinates.get(0);
        if (start.distance(first) <= OfficialRouteGeometryRules.EPSILON_M) {
            return this;
        }
        List<Coordinate> prefixedCoordinates = new ArrayList<>();
        prefixedCoordinates.add(new Coordinate(start));
        prefixedCoordinates.addAll(coordinates);
        List<RouteSection> prefixedSections = new ArrayList<>();
        prefixedSections.add(new RouteSection(
                "base",
                null,
                null,
                List.of(new RouteCoordinate(start.x, start.y), new RouteCoordinate(first.x, first.y)),
                start.distance(first),
                null));
        prefixedSections.addAll(sections);
        return new RoutePath(prefixedCoordinates, prefixedSections, lengthM + start.distance(first));
    }

    RoutePath withMandatorySuffix(Coordinate end) {
        if (coordinates.isEmpty()) {
            return this;
        }
        Coordinate last = coordinates.get(coordinates.size() - 1);
        if (last.distance(end) <= OfficialRouteGeometryRules.EPSILON_M) {
            return this;
        }
        List<Coordinate> suffixedCoordinates = new ArrayList<>(coordinates);
        suffixedCoordinates.add(new Coordinate(end));
        List<RouteSection> suffixedSections = new ArrayList<>(sections);
        suffixedSections.add(new RouteSection(
                "base",
                null,
                null,
                List.of(new RouteCoordinate(last.x, last.y), new RouteCoordinate(end.x, end.y)),
                last.distance(end),
                null));
        return new RoutePath(suffixedCoordinates, suffixedSections, lengthM + last.distance(end));
    }
}
