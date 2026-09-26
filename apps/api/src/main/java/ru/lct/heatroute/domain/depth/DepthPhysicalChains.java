package ru.lct.heatroute.domain.depth;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.routing.RouteEdge;

/** Вычислительные цепи степени два; исходные рёбра и направления DTO не изменяются. */
final class DepthPhysicalChains {
    static List<Chain> build(List<RouteEdge> edges, Set<String> boundaries) {
        Map<String, List<RouteEdge>> incident = new TreeMap<>();
        Map<String, RouteEdge> ids = new HashMap<>();
        for (RouteEdge e : edges) {
            if (ids.put(e.getId(), e) != null) throw new IllegalArgumentException("duplicate depth edge id");
            incident.computeIfAbsent(e.getUpstreamNodeId(), ignored -> new ArrayList<>()).add(e);
            incident.computeIfAbsent(e.getDownstreamNodeId(), ignored -> new ArrayList<>()).add(e);
        }
        incident.values().forEach(list -> list.sort(Comparator.comparing(RouteEdge::getId)));
        Set<String> joins = new HashSet<>();
        incident.forEach((id, local) -> { if (!boundaries.contains(id) && joins(local, id)) joins.add(id); });
        Set<String> used = new HashSet<>();
        List<Chain> result = new ArrayList<>();
        for (String node : incident.keySet()) {
            if (joins.contains(node)) continue;
            for (RouteEdge edge : incident.get(node)) if (!used.contains(edge.getId()))
                result.add(walk(node, edge, incident, joins, used, result.size()));
        }
        for (String node : incident.keySet()) for (RouteEdge edge : incident.get(node))
            if (!used.contains(edge.getId())) result.add(walk(node, edge, incident, joins, used, result.size()));
        return result;
    }

    private static Chain walk(String start, RouteEdge first, Map<String, List<RouteEdge>> incident,
            Set<String> joins, Set<String> used, int index) {
        List<Member> members = new ArrayList<>();
        List<RouteCoordinate> coordinates = new ArrayList<>();
        BigDecimal length = BigDecimal.ZERO;
        String node = start;
        RouteEdge edge = first;
        while (edge != null && used.add(edge.getId())) {
            boolean forward = node.equals(edge.getUpstreamNodeId());
            members.add(new Member(edge, forward, length));
            List<RouteCoordinate> points = new ArrayList<>(edge.getCoordinates());
            if (!forward) Collections.reverse(points);
            for (int i = coordinates.isEmpty() ? 0 : 1; i < points.size(); i++) coordinates.add(points.get(i));
            length = length.add(edge.getLengthM());
            node = forward ? edge.getDownstreamNodeId() : edge.getUpstreamNodeId();
            if (!joins.contains(node)) break;
            RouteEdge previous = edge;
            edge = incident.get(node).stream().filter(candidate -> !candidate.getId().equals(previous.getId()))
                    .findFirst().orElse(null);
        }
        RouteEdge virtual = new RouteEdge("depth-chain-" + index, start, node, length.doubleValue(), coordinates,
                List.of(), first.getFlowTph(), first.getDiameter());
        return new Chain(virtual, members);
    }

    private static boolean joins(List<RouteEdge> edges, String node) {
        if (edges.size() != 2 || edges.get(0) == edges.get(1)
                || edges.get(0).getDiameter() == null || !edges.get(0).getDiameter().equals(edges.get(1).getDiameter())) return false;
        List<RouteCoordinate> a = outward(edges.get(0), node), b = outward(edges.get(1), node);
        if (a.size() < 2 || b.size() < 2 || !same(a.get(0), b.get(0))) return false;
        double[] av = ray(a), bv = ray(b);
        if (av == null || bv == null) return false;
        // A reversal makes both outward rays coincide; it is not a continuous physical corridor.
        return (av[0] * bv[0] + av[1] * bv[1]) / (Math.hypot(av[0], av[1]) * Math.hypot(bv[0], bv[1])) < 0.999999;
    }

    private static List<RouteCoordinate> outward(RouteEdge edge, String node) {
        List<RouteCoordinate> points = new ArrayList<>(edge.getCoordinates());
        if (node.equals(edge.getDownstreamNodeId())) Collections.reverse(points);
        return points;
    }
    private static double[] ray(List<RouteCoordinate> p) {
        for (int i = 1; i < p.size(); i++) if (!same(p.get(0), p.get(i))) return new double[]{
                p.get(i).getXM().subtract(p.get(0).getXM()).doubleValue(), p.get(i).getYM().subtract(p.get(0).getYM()).doubleValue()};
        return null;
    }
    private static boolean same(RouteCoordinate a, RouteCoordinate b) {
        return a.getXM().compareTo(b.getXM()) == 0 && a.getYM().compareTo(b.getYM()) == 0;
    }

    static final class Chain {
        final RouteEdge edge;
        final List<Member> members;
        Chain(RouteEdge edge, List<Member> members) { this.edge = edge; this.members = List.copyOf(members); }
        DepthStationMetric metric() {
            List<Integer> stored = new ArrayList<>(List.of(0));
            List<BigDecimal> physical = new ArrayList<>(List.of(BigDecimal.ZERO));
            BigDecimal total = BigDecimal.ZERO;
            for (Member member : members) {
                List<RouteCoordinate> points = member.edge.getCoordinates();
                for (int i = 1; i < points.size(); i++) {
                    BigDecimal dx = points.get(i).getXM().subtract(points.get(i - 1).getXM());
                    BigDecimal dy = points.get(i).getYM().subtract(points.get(i - 1).getYM());
                    total = total.add(BigDecimal.valueOf(Math.hypot(dx.doubleValue(), dy.doubleValue())).movePointRight(3));
                }
                stored.add(member.end.movePointRight(3).intValueExact());
                physical.add(total);
            }
            return new DepthStationMetric(stored, physical);
        }
        /** Physical XY projection must follow each retained edge's stored station interval. */
        double storedStation(double physicalStation) {
            double physicalStart = 0;
            for (Member member : members) {
                double physicalLength = 0;
                List<RouteCoordinate> coordinates = member.edge.getCoordinates();
                for (int i = 1; i < coordinates.size(); i++)
                    physicalLength += coordinates.get(i - 1).toCoordinate().distance(coordinates.get(i).toCoordinate());
                if (physicalStation <= physicalStart + physicalLength || member == members.get(members.size() - 1)) {
                    double fraction = physicalLength == 0 ? 0 : (physicalStation - physicalStart) / physicalLength;
                    return member.start.doubleValue() + member.edge.getLengthM().doubleValue() * fraction;
                }
                physicalStart += physicalLength;
            }
            return edge.getLengthM().doubleValue();
        }
        List<DepthFloorInterval> stationConstraints(BigDecimal min) {
            List<DepthFloorInterval> result = new ArrayList<>();
            for (int i = 1; i < members.size(); i++) {
                BigDecimal station = members.get(i).start;
                result.add(new DepthFloorInterval("technical-node", station, station, min));
            }
            return result;
        }
    }
    static final class Member {
        final RouteEdge edge;
        final boolean forward;
        final BigDecimal start;
        final BigDecimal end;
        Member(RouteEdge edge, boolean forward, BigDecimal start) {
            this.edge = edge; this.forward = forward; this.start = start; this.end = start.add(edge.getLengthM());
        }
        BigDecimal global(BigDecimal local) { return forward ? start.add(local) : end.subtract(local); }
        BigDecimal local(BigDecimal global) { return forward ? global.subtract(start) : end.subtract(global); }
    }
}
