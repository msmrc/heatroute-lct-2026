package ru.lct.heatroute.domain.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;

/**
 * Оценивает повороты по актуальным правилам: внутренний угол трубы 90–120°.
 * Точный минимум между изгибами по фактическому ДУ повторно проверяет итоговый валидатор.
 * Предпочтения лучей камер считаются отдельно и не меняют {@link Evaluation#isCompliant()}.
 * Официальные отступы/пересечения принадлежат каталогу; это не проверка всех требований эксперта или СП.
 */
final class EngineeringRouteEvaluator {
    static final double MIN_INTERNAL_ANGLE_DEGREES = 90.0;
    static final double MAX_INTERNAL_ANGLE_DEGREES = 120.0;
    // Нижняя граница таблицы; полный порог по ДУ принадлежит итоговому валидатору.
    static final double MIN_BEND_SPACING_M = 2.0;
    static final double ANGLE_EPSILON_DEGREES = 0.5;
    private static final double LENGTH_EPSILON_M = 0.01;

    Evaluation evaluate(List<RouteEdge> edges) {
        int bendCount = 0;
        int invalidAngleCount = 0;
        double totalAngleDeviation = 0.0;
        double preferredAngleDeviation = 0.0;
        int irregularJunctionAngleCount = 0;
        double totalJunctionAngleDeviation = 0.0;
        Map<String, Double> minimumJunctionAngles = new LinkedHashMap<>();
        Map<String, JunctionPreference> junctionPreferences = new LinkedHashMap<>();
        Set<String> nonCompliantEdgeIds = new LinkedHashSet<>();
        Map<String, List<IncidentDirection>> directionsByNode = new LinkedHashMap<>();

        for (RouteEdge edge : edges) {
            List<RouteCoordinate> coordinates = edge.getCoordinates();
            addIncidentDirections(directionsByNode, edge, coordinates);
            for (int index = 1; index + 1 < coordinates.size(); index++) {
                Coordinate before = coordinates.get(index - 1).toCoordinate();
                Coordinate at = coordinates.get(index).toCoordinate();
                Coordinate after = coordinates.get(index + 1).toCoordinate();
                double internalAngle = internalAngleDegrees(before, at, after);
                if (isStraight(internalAngle)) {
                    continue;
                }
                bendCount++;
                double deviation = angleDeviation(internalAngle);
                totalAngleDeviation += deviation;
                preferredAngleDeviation += preferredBendAngleDeviation(internalAngle);
                if (deviation > ANGLE_EPSILON_DEGREES) {
                    invalidAngleCount++;
                    nonCompliantEdgeIds.add(edge.getId());
                }
            }
        }

        for (Map.Entry<String, List<IncidentDirection>> entry : directionsByNode.entrySet()) {
            List<IncidentDirection> directions = entry.getValue();
            if (directions.size() == 2) {
                double internalAngle = angleBetween(directions.get(0), directions.get(1));
                if (isStraight(internalAngle)) {
                    continue;
                }
                bendCount++;
                double deviation = angleDeviation(internalAngle);
                totalAngleDeviation += deviation;
                preferredAngleDeviation += preferredBendAngleDeviation(internalAngle);
                if (deviation > ANGLE_EPSILON_DEGREES) {
                    invalidAngleCount++;
                    nonCompliantEdgeIds.add(directions.get(0).edgeId);
                    nonCompliantEdgeIds.add(directions.get(1).edgeId);
                }
                continue;
            }
            if (directions.size() < 3) {
                continue;
            }
            int nodeIrregularAngleCount = 0;
            double nodeExcessDeviation = 0.0;
            for (int left = 0; left < directions.size(); left++) {
                for (int right = left + 1; right < directions.size(); right++) {
                    double angle = angleBetween(directions.get(left), directions.get(right));
                    minimumJunctionAngles.merge(entry.getKey(), angle, Math::min);
                    double deviation = preferredJunctionAngleDeviation(angle);
                    totalJunctionAngleDeviation += deviation;
                    nodeExcessDeviation += Math.max(0.0, deviation - ANGLE_EPSILON_DEGREES);
                    if (deviation > ANGLE_EPSILON_DEGREES) {
                        irregularJunctionAngleCount++;
                        nodeIrregularAngleCount++;
                    }
                }
            }
            junctionPreferences.put(entry.getKey(), new JunctionPreference(nodeIrregularAngleCount, nodeExcessDeviation));
        }

        int insufficientSpacingCount = evaluateSpacing(edges, directionsByNode);
        return new Evaluation(
                bendCount,
                invalidAngleCount,
                insufficientSpacingCount,
                totalAngleDeviation,
                preferredAngleDeviation,
                irregularJunctionAngleCount,
                totalJunctionAngleDeviation,
                minimumJunctionAngles,
                junctionPreferences,
                nonCompliantEdgeIds);
    }

    /** Считает короткие звенья для предпочтения; камеры степени >= 3 обрывают цепочку. */
    private int evaluateSpacing(
            List<RouteEdge> edges,
            Map<String, List<IncidentDirection>> directionsByNode) {
        Map<String, List<RouteEdge>> edgesByNode = new LinkedHashMap<>();
        for (RouteEdge edge : edges) {
            edgesByNode.computeIfAbsent(edge.getUpstreamNodeId(), ignored -> new ArrayList<>()).add(edge);
            edgesByNode.computeIfAbsent(edge.getDownstreamNodeId(), ignored -> new ArrayList<>()).add(edge);
        }
        Set<RouteEdge> visited = new LinkedHashSet<>();
        int violations = 0;
        for (Map.Entry<String, List<RouteEdge>> entry : edgesByNode.entrySet()) {
            if (entry.getValue().size() == 2) {
                continue;
            }
            for (RouteEdge edge : entry.getValue()) {
                if (!visited.contains(edge)) {
                    violations += evaluateSpacingChain(edge, entry.getKey(), edgesByNode,
                            directionsByNode, visited);
                }
            }
        }
        // В замкнутом компоненте все узлы могут иметь степень 2: проверяем и пару через начало обхода.
        for (RouteEdge edge : edges) {
            if (!visited.contains(edge)) {
                violations += evaluateSpacingChain(edge, edge.getUpstreamNodeId(), edgesByNode,
                        directionsByNode, visited);
            }
        }
        return violations;
    }

    private int evaluateSpacingChain(
            RouteEdge edge,
            String startNodeId,
            Map<String, List<RouteEdge>> edgesByNode,
            Map<String, List<IncidentDirection>> directionsByNode,
            Set<RouteEdge> visited) {
        int edgeCount = 0;
        List<Coordinate> bends = new ArrayList<>();
        String nodeId = startNodeId;
        boolean closed = false;
        while (visited.add(edge)) {
            List<RouteCoordinate> coordinates = edge.getCoordinates();
            if (coordinates.size() < 2) {
                break;
            }
            boolean forward = edge.getUpstreamNodeId().equals(nodeId);
            if (edgeCount > 0 && isSpacingBendNode(nodeId, directionsByNode)) {
                int endpoint = forward ? 0 : coordinates.size() - 1;
                bends.add(coordinates.get(endpoint).toCoordinate());
            }
            edgeCount++;
            for (int offset = 1; offset + 1 < coordinates.size(); offset++) {
                int index = forward ? offset : coordinates.size() - 1 - offset;
                Coordinate at = coordinates.get(index).toCoordinate();
                if (!isStraight(internalAngleDegrees(
                        coordinates.get(index - 1).toCoordinate(), at, coordinates.get(index + 1).toCoordinate()))) {
                    bends.add(at);
                }
            }
            nodeId = forward ? edge.getDownstreamNodeId() : edge.getUpstreamNodeId();
            List<RouteEdge> incident = edgesByNode.get(nodeId);
            if (incident.size() != 2) {
                break;
            }
            if (nodeId.equals(startNodeId)) {
                closed = true;
                if (isSpacingBendNode(nodeId, directionsByNode)) {
                    int endpoint = forward ? coordinates.size() - 1 : 0;
                    bends.add(coordinates.get(endpoint).toCoordinate());
                }
                break;
            }
            edge = incident.get(0) == edge ? incident.get(1) : incident.get(0);
        }
        int violations = 0;
        for (int index = 1; index < bends.size(); index++) {
            violations += checkSpacing(bends.get(index - 1), bends.get(index));
        }
        if (closed && bends.size() > 1) {
            violations += checkSpacing(bends.get(bends.size() - 1), bends.get(0));
        }
        return violations;
    }

    private boolean isSpacingBendNode(String nodeId, Map<String, List<IncidentDirection>> directionsByNode) {
        List<IncidentDirection> directions = directionsByNode.get(nodeId);
        return directions != null && directions.size() == 2
                && !isStraight(angleBetween(directions.get(0), directions.get(1)));
    }

    private int checkSpacing(Coordinate previous, Coordinate current) {
        return previous.distance(current) + LENGTH_EPSILON_M < MIN_BEND_SPACING_M ? 1 : 0;
    }

    private void addIncidentDirections(
            Map<String, List<IncidentDirection>> directionsByNode,
            RouteEdge edge,
            List<RouteCoordinate> coordinates) {
        if (coordinates.size() < 2) {
            return;
        }
        Coordinate upstream = coordinates.get(0).toCoordinate();
        Coordinate upstreamNext = coordinates.get(1).toCoordinate();
        Coordinate downstream = coordinates.get(coordinates.size() - 1).toCoordinate();
        Coordinate downstreamPrevious = coordinates.get(coordinates.size() - 2).toCoordinate();
        addIncidentDirection(directionsByNode, edge.getUpstreamNodeId(), edge.getId(), upstream, upstreamNext);
        addIncidentDirection(
                directionsByNode, edge.getDownstreamNodeId(), edge.getId(), downstream, downstreamPrevious);
    }

    private void addIncidentDirection(
            Map<String, List<IncidentDirection>> directionsByNode,
            String nodeId,
            String edgeId,
            Coordinate node,
            Coordinate adjacent) {
        double dx = adjacent.x - node.x;
        double dy = adjacent.y - node.y;
        double length = Math.hypot(dx, dy);
        // Координаты уже округлены до миллиметров. Положительный короткий участок всё ещё
        // задаёт направление; сантиметровый допуск расстояния не должен скрывать его изгиб.
        if (length == 0.0) {
            return;
        }
        directionsByNode.computeIfAbsent(nodeId, ignored -> new ArrayList<>())
                .add(new IncidentDirection(edgeId, dx / length, dy / length));
    }

    private double angleBetween(IncidentDirection left, IncidentDirection right) {
        double cosine = Math.max(-1.0, Math.min(1.0, left.dx * right.dx + left.dy * right.dy));
        return Math.toDegrees(Math.acos(cosine));
    }

    private double internalAngleDegrees(Coordinate before, Coordinate at, Coordinate after) {
        double ax = before.x - at.x;
        double ay = before.y - at.y;
        double bx = after.x - at.x;
        double by = after.y - at.y;
        double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        // Произведение длин имеет размерность м²: сравнивать его с допуском длины нельзя.
        // Как и на стыке рёбер, исключаем только совпадающие точки, а не короткие звенья.
        if (denominator == 0.0) {
            return 180.0;
        }
        double cosine = Math.max(-1.0, Math.min(1.0, (ax * bx + ay * by) / denominator));
        return Math.toDegrees(Math.acos(cosine));
    }

    private boolean isStraight(double internalAngle) {
        return Math.abs(180.0 - internalAngle) <= ANGLE_EPSILON_DEGREES;
    }

    private double angleDeviation(double internalAngle) {
        if (internalAngle + ANGLE_EPSILON_DEGREES >= MIN_INTERNAL_ANGLE_DEGREES
                && internalAngle <= MAX_INTERNAL_ANGLE_DEGREES + ANGLE_EPSILON_DEGREES) {
            return 0.0;
        }
        return Math.min(
                Math.abs(internalAngle - MIN_INTERNAL_ANGLE_DEGREES),
                Math.abs(internalAngle - MAX_INTERNAL_ANGLE_DEGREES));
    }

    private double preferredBendAngleDeviation(double internalAngle) {
        return Math.min(
                Math.abs(internalAngle - MIN_INTERNAL_ANGLE_DEGREES),
                Math.abs(internalAngle - MAX_INTERNAL_ANGLE_DEGREES));
    }

    /**
     * Трактуем перпендикулярное присоединение (правила.docx, стр. 3) как Т/крест:
     * соседние лучи 90°, противоположные 180°. Это preference узла, не новый hard constraint;
     * внутренние углы трубы и isCompliant остаются независимыми.
     */
    private double preferredJunctionAngleDeviation(double angle) {
        return Math.min(Math.abs(angle - 90.0), Math.abs(angle - 180.0));
    }

    private static final class IncidentDirection {
        private final String edgeId;
        private final double dx;
        private final double dy;

        private IncidentDirection(String edgeId, double dx, double dy) {
            this.edgeId = edgeId;
            this.dx = dx;
            this.dy = dy;
        }
    }

    private static final class JunctionPreference {
        private final int irregularAngleCount;
        private final double excessDeviation;

        private JunctionPreference(int irregularAngleCount, double excessDeviation) {
            this.irregularAngleCount = irregularAngleCount;
            this.excessDeviation = excessDeviation;
        }
    }

    static final class Evaluation {
        private final int bendCount;
        private final int invalidAngleCount;
        private final int insufficientSpacingCount;
        private final double totalAngleDeviation;
        private final double preferredAngleDeviation;
        private final int irregularJunctionAngleCount;
        private final double totalJunctionAngleDeviation;
        private final Map<String, Double> minimumJunctionAngles;
        private final Map<String, JunctionPreference> junctionPreferences;
        private final Set<String> nonCompliantEdgeIds;

        private Evaluation(
                int bendCount,
                int invalidAngleCount,
                int insufficientSpacingCount,
                double totalAngleDeviation,
                double preferredAngleDeviation,
                int irregularJunctionAngleCount,
                double totalJunctionAngleDeviation,
                Map<String, Double> minimumJunctionAngles,
                Map<String, JunctionPreference> junctionPreferences,
                Set<String> nonCompliantEdgeIds) {
            this.bendCount = bendCount;
            this.invalidAngleCount = invalidAngleCount;
            this.insufficientSpacingCount = insufficientSpacingCount;
            this.totalAngleDeviation = totalAngleDeviation;
            this.preferredAngleDeviation = preferredAngleDeviation;
            this.irregularJunctionAngleCount = irregularJunctionAngleCount;
            this.totalJunctionAngleDeviation = totalJunctionAngleDeviation;
            this.minimumJunctionAngles = Collections.unmodifiableMap(new LinkedHashMap<>(minimumJunctionAngles));
            this.junctionPreferences = Collections.unmodifiableMap(new LinkedHashMap<>(junctionPreferences));
            this.nonCompliantEdgeIds = Collections.unmodifiableSet(new LinkedHashSet<>(nonCompliantEdgeIds));
        }

        int bendCount() { return bendCount; }
        int invalidAngleCount() { return invalidAngleCount; }
        /** Быстрая поисковая метрика по нижней границе; итоговая проверка использует фактический ДУ. */
        int insufficientSpacingCount() { return insufficientSpacingCount; }
        double totalAngleDeviation() { return totalAngleDeviation; }
        double preferredAngleDeviation() { return preferredAngleDeviation; }
        int irregularJunctionAngleCount() { return irregularJunctionAngleCount; }
        double totalJunctionAngleDeviation() { return totalJunctionAngleDeviation; }
        /** Для предпочтения кандидатов не покупаем точность внутри допуска округления. */
        double excessJunctionAngleDeviation() {
            return junctionPreferences.values().stream().mapToDouble(value -> value.excessDeviation).sum();
        }
        /**
         * Сохраняем минимум, число нерегулярных пар и сумму превышений допуска по каждому узлу:
         * улучшение другой камеры не компенсирует локальную регрессию. Превышение max(0, deviation − 0,5°)
         * не штрафует штатное округление регулярных лучей; агрегатная raw-проверка остаётся прежней.
         */
        boolean preservesJunctionQualityOf(Evaluation before) {
            if (irregularJunctionAngleCount > before.irregularJunctionAngleCount
                    || totalJunctionAngleDeviation > before.totalJunctionAngleDeviation + 1e-7) return false;
            for (Map.Entry<String, Double> entry : before.minimumJunctionAngles.entrySet()) {
                Double current = minimumJunctionAngles.get(entry.getKey());
                // Разделение перегруженной камеры может снизить её степень ниже трёх. У такого
                // узла больше нет парного предпочтения лучей, что и является целью ремонта.
                if (current == null) continue;
                if (!Double.isFinite(current)
                        || current + ANGLE_EPSILON_DEGREES < entry.getValue()) return false;
                JunctionPreference previousPreference = before.junctionPreferences.get(entry.getKey());
                JunctionPreference currentPreference = junctionPreferences.get(entry.getKey());
                if (currentPreference == null || !Double.isFinite(currentPreference.excessDeviation)
                        || currentPreference.irregularAngleCount > previousPreference.irregularAngleCount
                        || currentPreference.excessDeviation > previousPreference.excessDeviation + 1e-7) return false;
            }
            return true;
        }
        Set<String> nonCompliantEdgeIds() { return nonCompliantEdgeIds; }
        boolean isCompliant() {
            return invalidAngleCount == 0;
        }
    }
}
