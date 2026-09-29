package ru.lct.heatroute.domain.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.index.ItemVisitor;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.constraints.OfficialConstraintCatalog;
import ru.lct.heatroute.domain.constraints.OfficialAxisClearance;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;
import ru.lct.heatroute.domain.constraints.OfficialCrossingGeometry;
import ru.lct.heatroute.domain.constraints.SpatialConstraintRule;
import ru.lct.heatroute.domain.constraints.RoadCrossingClearance;
import ru.lct.heatroute.domain.constraints.PreparedRoadCrossings;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

@Component
public class OfficialRouteGeometryRules {
    static final double EPSILON_M = 0.01;
    static final double NORMAL_EGRESS_MARGIN_M = 0.25;
    private static final double CLEARANCE_BOUNDARY_EPSILON_M = 1e-6;
    private static final double TIE_IN_CONTACT_M = 0.05;
    private static final double ROUTE_AVOIDANCE_BUFFER_M = 0.20 - CLEARANCE_BOUNDARY_EPSILON_M;
    // The ordinary search admits at most 16 alternatives downstream, so evaluate that many
    // evenly distributed rays. Only the no-normal fallback keeps the dense 5-degree sweep.
    private static final int STRAIGHT_EGRESS_DIRECTION_COUNT = 16;
    private static final int STRAIGHT_EGRESS_FALLBACK_DIRECTION_COUNT = 72;
    private static final int MAX_ALTERNATIVE_STRAIGHT_EGRESSES = 16;
    private static final Set<String> UTILITY_TYPES = Set.of(
            "gas_pipeline", "power_cable", "heat_network");
    private static final Comparator<Constraint> CONSTRAINT_ORDER = Comparator
            .comparing((Constraint item) -> item.type)
            .thenComparing(item -> item.id);

    private final OfficialConstraintCatalog catalog;
    private final OfficialCrossingGeometry crossingGeometry;
    private final OfficialAxisClearance axisClearance;
    private final RoadCrossingClearance roadCrossings = new RoadCrossingClearance();
    private final BuildingWallNormals wallNormals = new BuildingWallNormals();
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public OfficialRouteGeometryRules(
            OfficialConstraintCatalog catalog,
            OfficialCrossingGeometry crossingGeometry) {
        this.catalog = catalog;
        this.crossingGeometry = crossingGeometry;
        this.axisClearance = new OfficialAxisClearance(new OfficialPipeCatalog(), catalog);
    }

    List<Constraint> constraints(
            List<ImportedOfficialFeature> features,
            int diameter,
            Set<String> exemptFeatureIds,
            Coordinate start,
            Coordinate end) {
        return applicableConstraints(
                baseConstraints(features, diameter), exemptFeatureIds, start, end);
    }

    List<Constraint> baseConstraints(List<ImportedOfficialFeature> features, int diameter) {
        List<Constraint> result = new ArrayList<>();
        for (ImportedOfficialFeature feature : features) {
            String type = constraintType(feature);
            if (type == null) {
                continue;
            }
            SpatialConstraintRule rule = catalog.find(type).orElse(null);
            Geometry source = feature.getMetricGeometry();
            if (rule == null || source == null || source.isEmpty()) {
                continue;
            }
            Geometry blocked = null;
            BigDecimal clearance = preparationClearanceM(feature, type, diameter);
            if (clearance != null) {
                // Equality with the published minimum clearance is legal. Shrinking only by a
                // numerical epsilon keeps the prepared-geometry fast path and excludes a pure
                // tangential touch from the blocked region.
                double blockedClearance = Math.max(
                        0.0, clearance.doubleValue() - CLEARANCE_BOUNDARY_EPSILON_M);
                blocked = source.buffer(blockedClearance, 4);
            }
            result.add(new Constraint(feature.getFeatureId(), type, source, blocked, rule,
                    clearance == null ? 0 : clearance.doubleValue()));
        }
        sortConstraints(result);
        return result;
    }

    /** Только ограничения, используемые разметкой секций и полным пересечением дороги/трамвая. */
    List<Constraint> crossingConstraints(List<ImportedOfficialFeature> features, int diameter) {
        List<ImportedOfficialFeature> crossingFeatures = features.stream()
                .filter(feature -> catalog.find(constraintType(feature))
                        .map(rule -> !rule.isForbidden()).orElse(false))
                .collect(Collectors.toList());
        return baseConstraints(crossingFeatures, diameter);
    }

    /** Called only after an input geometry has passed the null/empty checks. */
    BigDecimal preparationClearanceM(String type, int diameter) {
        SpatialConstraintRule rule = catalog.find(type).orElse(null);
        if (rule == null || (!rule.isForbidden() && !RoadCrossingClearance.supports(type))) {
            return null;
        }
        return axisClearance.axisClearanceM(type, diameter, null);
    }

    /** Осевой отступ линейной коммуникации зависит от фактического ДУ существующей теплосети. */
    BigDecimal preparationClearanceM(ImportedOfficialFeature feature, int diameter) {
        String type = constraintType(feature);
        return type == null ? null : preparationClearanceM(feature, type, diameter);
    }

    private BigDecimal preparationClearanceM(
            ImportedOfficialFeature feature, String type, int diameter) {
        BigDecimal ordinary = preparationClearanceM(type, diameter);
        if (ordinary != null || !UTILITY_TYPES.contains(type)) return ordinary;
        Integer existingHeatDu = null;
        if ("heat_network".equals(type)) {
            if (!feature.getAttributes().path("diameter").isIntegralNumber()) {
                // Старые внутренние фикстуры без инженерных атрибутов не становятся
                // маршрутизируемыми данными; строгая финальная проверка их всё равно отклоняет.
                return null;
            }
            existingHeatDu = feature.getAttributes().path("diameter").intValue();
        }
        return axisClearance.axisClearanceM(type, diameter, existingHeatDu);
    }

    /** Пространственная подготовка не предполагает неизменность пользовательских реализаций правил. */
    boolean hasStandardPreparationRules() {
        return getClass() == OfficialRouteGeometryRules.class
                && catalog != null && catalog.getClass() == OfficialConstraintCatalog.class
                && crossingGeometry != null && crossingGeometry.getClass() == OfficialCrossingGeometry.class;
    }

    boolean hasConstraintRule(String type) {
        return catalog.find(type).isPresent();
    }

    void sortConstraints(List<Constraint> constraints) {
        constraints.sort(CONSTRAINT_ORDER);
    }

    ConstraintIndex index(List<Constraint> constraints) {
        return new ConstraintIndex(constraints, RouteTraversal.AS_GIVEN);
    }

    /** Направление относится к road/tram-проверкам запроса, а не к общим исходным ограничениям. */
    ConstraintIndex index(List<Constraint> constraints, RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        return traversal == RouteTraversal.AS_GIVEN ? index(constraints) : new ConstraintIndex(constraints, traversal);
    }

    /**
     * Применяет только явно переданные исключения. Само положение конца в отступе ОКС
     * не снимает буфер (§2.2 и разъяснение3); собственный финальный ввод проверяется отдельно.
     */
    List<Constraint> applicableConstraints(
            List<Constraint> base,
            Set<String> exemptFeatureIds,
            Coordinate start,
            Coordinate end) {
        List<Constraint> result = new ArrayList<>();
        for (Constraint constraint : base) {
            if (exemptFeatureIds.contains(constraint.id)) {
                continue;
            }
            result.add(constraint);
        }
        return result;
    }

    /**
     * Существующая теплосеть из списка целей получает только локальный контакт в конечной точке;
     * остальные явно переданные объекты сохраняют прежнее полное исключение.
     */
    List<Constraint> routingConstraints(
            List<Constraint> base,
            Set<String> targetIds,
            Coordinate start,
            Coordinate end) {
        List<Constraint> local = localTieInConstraints(base, targetIds, start, end);
        Set<String> globalExemptions = new HashSet<>(targetIds);
        for (Constraint constraint : base) {
            if ("heat_network".equals(constraint.type)) globalExemptions.remove(constraint.id);
        }
        return applicableConstraints(local, globalExemptions, start, end);
    }

    /**
     * Врезка освобождает только контакт выбранной теплосети у endpoint в пределах допуска
     * координат 1 см. Повторные пересечения той же feature остаются ограничениями и special.
     * Совпадение ID дороги/ОКС с ID врезки не освобождает объект другого типа.
     */
    List<Constraint> localTieInConstraints(List<Constraint> base, Set<String> targetIds,
            Coordinate start, Coordinate end) {
        if (targetIds.isEmpty()) return base;
        Geometry startPoint = geometryFactory.createPoint(start);
        Geometry endPoint = geometryFactory.createPoint(end);
        List<Constraint> result = new ArrayList<>(base.size());
        for (Constraint constraint : base) {
            if (!targetIds.contains(constraint.id) || !"heat_network".equals(constraint.type)
                    || constraint.rule.isForbidden()) {
                result.add(constraint);
                continue;
            }
            Geometry source = constraint.source;
            List<Coordinate> tieIns = new ArrayList<>(constraint.utilityTieIns);
            if (source.distance(startPoint) <= EPSILON_M) {
                tieIns.add(new Coordinate(start));
                source = source.difference(startPoint.buffer(EPSILON_M));
            }
            if (!start.equals2D(end) && source.distance(endPoint) <= EPSILON_M) {
                tieIns.add(new Coordinate(end));
                source = source.difference(endPoint.buffer(EPSILON_M));
            }
            result.add(source == constraint.source ? constraint : new Constraint(constraint.id, constraint.type,
                    source, constraint.blocked, constraint.rule, constraint.clearanceM,
                    constraint.joinedContact, tieIns));
        }
        return result;
    }

    /** Ближайший проверенный прямой выход: нормали первыми, затем ограниченный набор иных лучей. */
    Optional<NormalEgress> normalEgress(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint) {
        return nearestLegalNormalEgresses(features, diameter, connectionPoint, RouteTraversal.AS_GIVEN).stream()
                .map(egress -> withNavigationMargin(egress, features, diameter, RouteTraversal.AS_GIVEN)).findFirst();
    }

    /** Геометрия нормали остаётся наружной; REVERSED проверяет физический ввод к подключению. */
    Optional<NormalEgress> normalEgress(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint, RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        if (traversal == RouteTraversal.AS_GIVEN) return normalEgress(features, diameter, connectionPoint);
        return nearestLegalNormalEgresses(features, diameter, connectionPoint, traversal).stream()
                .map(egress -> withNavigationMargin(egress, features, diameter, traversal)).findFirst();
    }

    /** Цель разрешает только равенство расстояний до стен, не подменяя нормаль лучом на камеру. */
    Optional<NormalEgress> normalEgressTowards(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint, Coordinate target) {
        return normalEgressCandidates(features, diameter, connectionPoint, target, 0).stream().findFirst();
    }

    Optional<NormalEgress> normalEgressTowards(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            Coordinate target, RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        if (traversal == RouteTraversal.AS_GIVEN) return normalEgressTowards(features, diameter, connectionPoint, target);
        return normalEgressCandidates(features, diameter, connectionPoint, target, 0, traversal).stream().findFirst();
    }

    Optional<NormalEgress> normalEgressTowards(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            Coordinate target, double maximumAlternativeEgressExtraM) {
        return normalEgressTowards(features, diameter, connectionPoint, target);
    }

    Optional<NormalEgress> normalEgressTowards(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            Coordinate target, double maximumAlternativeEgressExtraM, RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        if (traversal == RouteTraversal.AS_GIVEN) return normalEgressTowards(
                features, diameter, connectionPoint, target, maximumAlternativeEgressExtraM);
        return normalEgressTowards(features, diameter, connectionPoint, target, traversal);
    }

    /** Дальняя стена доступна только если все более близкие полные вводы перекрыты препятствиями. */
    List<NormalEgress> normalEgressCandidates(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            Coordinate target, double maximumAlternativeEgressExtraM) {
        return directedNormalEgressCandidates(features, diameter, connectionPoint, target, RouteTraversal.AS_GIVEN);
    }

    List<NormalEgress> normalEgressCandidates(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            Coordinate target, double maximumAlternativeEgressExtraM, RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        if (traversal == RouteTraversal.AS_GIVEN) return normalEgressCandidates(
                features, diameter, connectionPoint, target, maximumAlternativeEgressExtraM);
        return directedNormalEgressCandidates(features, diameter, connectionPoint, target, traversal);
    }

    private List<NormalEgress> directedNormalEgressCandidates(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            Coordinate target, RouteTraversal traversal) {
        return sortNormalEgressesForTarget(
                prepareNormalEgresses(features, diameter, connectionPoint, traversal), target);
    }

    /** Полная target-независимая подготовка допустимых нормалей; порядок до target-sort не является контрактом. */
    List<NormalEgress> prepareNormalEgresses(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint,
            RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        return padPreparedNormalEgresses(features, diameter, traversal,
                nearestLegalNormalEgresses(features, diameter, connectionPoint, traversal));
    }

    /** Сохраняет ближайшие вводы первыми и добавляет до 16 проверенных альтернатив разных направлений. */
    List<NormalEgress> padPreparedNormalEgresses(
            List<ImportedOfficialFeature> features, int diameter, RouteTraversal traversal,
            List<NormalEgress> nearest) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        List<NormalEgress> result = nearest.stream()
                .map(egress -> withNavigationMargin(egress, features, diameter, traversal))
                .collect(Collectors.toCollection(ArrayList::new));
        if (nearest.isEmpty()) return result;
        Coordinate point = nearest.get(0).start();
        double clearance = axisClearance.axisClearanceM("oks", diameter, null).doubleValue();
        List<NormalEgress> alternatives = straightBoundaryEgresses(
                containingOksFeatures(features, diameter, point), features, diameter, point, clearance, traversal, false);
        // Разные стороны здания важнее множества почти одинаковых лучей у одной ближайшей стены.
        alternatives.sort(Comparator.comparingDouble((NormalEgress exit) -> Math.atan2(
                        exit.exit.y - point.y, exit.exit.x - point.x))
                .thenComparing(NormalEgress::oksId)
                .thenComparingDouble(exit -> exit.exit.x).thenComparingDouble(exit -> exit.exit.y));
        int count = Math.min(MAX_ALTERNATIVE_STRAIGHT_EGRESSES, alternatives.size());
        for (int index = 0; index < count; index++) {
            NormalEgress candidate = alternatives.get(index * alternatives.size() / count);
            NormalEgress alternative = new NormalEgress(candidate.oksId, candidate.start, candidate.exit, true);
            addDistinctEgress(result, withNavigationMargin(alternative, features, diameter, traversal));
        }
        return result;
    }

    /** Применяет единственную target-зависимую часть исходного упорядочивания к полной подготовке. */
    List<NormalEgress> sortNormalEgressesForTarget(List<NormalEgress> prepared, Coordinate target) {
        List<NormalEgress> result = new ArrayList<>(prepared);
        result.sort(Comparator.comparingInt((NormalEgress exit) -> exit.alternative ? 1 : 0)
                .thenComparingDouble(exit -> exit.exit().distance(target))
                .thenComparing(NormalEgress::oksId)
                .thenComparingDouble(exit -> exit.exit().x).thenComparingDouble(exit -> exit.exit().y));
        return result;
    }

    /**
     * Письменное уточнение 29.09 не требует 90° к стене. Проверяем конечный набор лучей:
     * ближайший слой нужен для первого выбора, полный набор — для поисковых альтернатив.
     * Исчерпание лучей не доказывает no-route.
     */
    private List<NormalEgress> straightBoundaryEgresses(List<ImportedOfficialFeature> containing,
            List<ImportedOfficialFeature> features, int diameter, Coordinate point,
            double clearance, RouteTraversal traversal, boolean nearestOnly) {
        List<WallEgress> candidates = new ArrayList<>();
        int directionCount = nearestOnly
                ? STRAIGHT_EGRESS_FALLBACK_DIRECTION_COUNT
                : STRAIGHT_EGRESS_DIRECTION_COUNT;
        for (ImportedOfficialFeature feature : containing) {
            Geometry footprint = feature.getMetricGeometry();
            Geometry boundary = footprint.getBoundary();
            Envelope bounds = footprint.getEnvelopeInternal();
            double reach = Math.hypot(bounds.getWidth(), bounds.getHeight()) + 4 * clearance + 1;
            for (int direction = 0; direction < directionCount; direction++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("Straight egress preparation cancelled");
                }
                double angle = 2 * Math.PI * direction / directionCount;
                Coordinate towards = new Coordinate(point.x + reach * Math.cos(angle),
                        point.y + reach * Math.sin(angle));
                Coordinate wall = null;
                for (Coordinate intersection : boundary.intersection(line(List.of(point, towards))).getCoordinates()) {
                    if (wall == null || point.distance(intersection) < point.distance(wall)) wall = intersection;
                }
                if (wall == null) continue;
                Coordinate exit = wallNormals.firstClearancePoint(footprint, wall, towards, clearance);
                if (exit == null) continue;
                NormalEgress candidate = new NormalEgress(feature.getFeatureId(), point, exit);
                if (terminalLegAllowed(candidate, features, diameter, traversal)) {
                    candidates.add(new WallEgress(candidate, point.distance(wall)));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((WallEgress exit) -> exit.wallDistanceM)
                .thenComparing(exit -> exit.egress.oksId())
                .thenComparingDouble(exit -> exit.egress.exit().x)
                .thenComparingDouble(exit -> exit.egress.exit().y));
        List<NormalEgress> result = new ArrayList<>();
        if (candidates.isEmpty()) return result;
        double nearest = candidates.get(0).wallDistanceM;
        for (WallEgress candidate : candidates) {
            if (nearestOnly && candidate.wallDistanceM > nearest + EPSILON_M) break;
            addDistinctEgress(result, candidate.egress);
        }
        return result;
    }

    /** Исходные ближайшие допустимые поисковые вводы, без navigation margin. */
    List<NormalEgress> prepareNearestLegalNormalEgresses(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint, RouteTraversal traversal) {
        return nearestLegalNormalEgresses(features, diameter, connectionPoint, traversal);
    }

    private List<NormalEgress> nearestLegalNormalEgresses(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint, RouteTraversal traversal) {
        List<ImportedOfficialFeature> containing = containingOksFeatures(features, diameter, connectionPoint);
        if (containing.isEmpty()) return List.of();
        double clearance = axisClearance.axisClearanceM("oks", diameter, null).doubleValue();
        List<WallEgress> candidates = new ArrayList<>();
        for (ImportedOfficialFeature feature : containing) {
            for (BuildingWallNormals.Exit exit : wallNormals.candidates(
                    feature.getMetricGeometry(), connectionPoint, clearance)) {
                candidates.add(new WallEgress(new NormalEgress(feature.getFeatureId(), connectionPoint, exit.point()),
                        exit.wallDistanceM()));
            }
        }
        candidates.sort(Comparator.comparingDouble((WallEgress exit) -> exit.wallDistanceM)
                .thenComparing(exit -> exit.egress.oksId())
                .thenComparingDouble(exit -> exit.egress.exit().x)
                .thenComparingDouble(exit -> exit.egress.exit().y));
        List<NormalEgress> result = new ArrayList<>();
        double nearestLegalDistance = Double.POSITIVE_INFINITY;
        for (WallEgress wall : candidates) {
            if (wall.wallDistanceM > nearestLegalDistance + EPSILON_M) break;
            if (!terminalLegAllowed(wall.egress, features, diameter, traversal)) continue;
            nearestLegalDistance = Math.min(nearestLegalDistance, wall.wallDistanceM);
            addDistinctEgress(result, wall.egress);
        }
        return result.isEmpty()
                ? straightBoundaryEgresses(containing, features, diameter, connectionPoint, clearance, traversal, true)
                : result;
    }

    /** Запас помогает поиску и округлению, но не отменяет допустимую ближайшую стену. */
    private NormalEgress withNavigationMargin(
            NormalEgress required, List<ImportedOfficialFeature> features, int diameter, RouteTraversal traversal) {
        double length = required.start.distance(required.exit);
        double factor = (length + NORMAL_EGRESS_MARGIN_M) / length;
        Coordinate exit = new Coordinate(
                required.start.x + (required.exit.x - required.start.x) * factor,
                required.start.y + (required.exit.y - required.start.y) * factor);
        NormalEgress preferred = new NormalEgress(required.oksId, required.start, exit, required.alternative);
        return terminalLegAllowed(preferred, features, diameter, traversal) ? preferred : required;
    }

    /** Проверяет весь ввод без полигональной аппроксимации чужих запрещённых отступов. */
    private boolean terminalLegAllowed(NormalEgress egress, List<ImportedOfficialFeature> features,
            int diameter, RouteTraversal traversal) {
        LineString leg = line(List.of(egress.start(), egress.exit()));
        for (ImportedOfficialFeature feature : features) {
            String type = constraintType(feature);
            SpatialConstraintRule rule = type == null ? null : catalog.find(type).orElse(null);
            Geometry source = feature.getMetricGeometry();
            if (rule == null || source == null || source.isEmpty()) continue;
            BigDecimal preparedClearance = preparationClearanceM(feature, type, diameter);
            Constraint constraint = new Constraint(feature.getFeatureId(), type, source, null, rule,
                    preparedClearance == null ? 0 : preparedClearance.doubleValue());
            if (egress.exempts(constraint)) {
                if ("oks".equals(type) && !ownApproachAllowed(egress, leg, source, diameter)) return false;
                continue;
            }
            if (rule.isForbidden()) {
                double clearance = preparationClearanceM(feature, type, diameter).doubleValue();
                org.locationtech.jts.geom.Envelope bounds = new org.locationtech.jts.geom.Envelope(
                        source.getEnvelopeInternal());
                bounds.expandBy(clearance);
                if (bounds.intersects(leg.getEnvelopeInternal())
                        && (leg.intersects(source)
                            || leg.distance(source) < clearance - CLEARANCE_BOUNDARY_EPSILON_M)) return false;
            } else if (RoadCrossingClearance.supports(type)) {
                // Подключение фиксировано, наружный порт открыт: входящий ввод является суффиксом.
                double angle = rule.getMinimumCrossingAngleDegrees().doubleValue();
                double extension = rule.getSpecialExtensionM().doubleValue();
                boolean allowed = traversal == RouteTraversal.AS_GIVEN
                        ? roadCrossings.terminalPrefixAllowed(leg, source, constraint.clearanceM, angle, extension)
                        : roadCrossings.terminalSuffixAllowed(
                                (LineString) leg.reverse(), source, constraint.clearanceM, angle, extension);
                if (!allowed) return false;
            } else if (!lineAllowed(leg, index(List.of(constraint)))) {
                return false;
            }
        }
        return true;
    }

    /** Льгота своего ОКС заканчивается на первом полном выходе, а не на конце произвольного луча. */
    private boolean ownApproachAllowed(NormalEgress expected, LineString leg, Geometry footprint, int diameter) {
        Coordinate endpoint = leg.getCoordinateN(0);
        Coordinate adjacent = leg.getCoordinateN(1);
        Coordinate farthestIntersection = endpoint;
        for (Coordinate intersection : leg.intersection(footprint).getCoordinates()) {
            if (intersection.distance(endpoint) > farthestIntersection.distance(endpoint)) {
                farthestIntersection = intersection;
            }
        }
        // Проверяется фактический участок, включая возможное возвращение в другой компонент.
        double roundingAllowance = Math.min(EPSILON_M, endpoint.distance(expected.start));
        if (line(List.of(endpoint, farthestIntersection)).difference(footprint).getLength()
                > roundingAllowance + CLEARANCE_BOUNDARY_EPSILON_M) return false;
        double length = leg.getLength();
        if (length <= CLEARANCE_BOUNDARY_EPSILON_M) return false;
        double clearance = axisClearance.axisClearanceM("oks", diameter, null).doubleValue();
        Coordinate exteriorStart = wallNormals.firstClearancePoint(
                footprint, farthestIntersection, adjacent, clearance);
        if (exteriorStart == null || endpoint.distance(exteriorStart) > length + CLEARANCE_BOUNDARY_EPSILON_M) {
            return false;
        }
        LineString extension = line(List.of(exteriorStart, adjacent));
        return extension.distance(footprint) + CLEARANCE_BOUNDARY_EPSILON_M >= clearance;
    }

    private List<ImportedOfficialFeature> containingOksFeatures(
            List<ImportedOfficialFeature> features, int diameter, Coordinate connectionPoint) {
        Geometry point = geometryFactory.createPoint(connectionPoint);
        List<ImportedOfficialFeature> result = new ArrayList<>();
        for (ImportedOfficialFeature feature : features) {
            if (!"oks".equals(constraintType(feature))) continue;
            Geometry source = feature.getMetricGeometry();
            if (source == null || source.isEmpty()) continue;
            catalog.existingBuildingClearanceM(diameter);
            if (source.getEnvelopeInternal().contains(connectionPoint) && source.covers(point)) result.add(feature);
        }
        result.sort(Comparator.comparing(ImportedOfficialFeature::getFeatureId));
        return result;
    }

    private static final class WallEgress {
        private final NormalEgress egress;
        private final double wallDistanceM;

        private WallEgress(NormalEgress egress, double wallDistanceM) {
            this.egress = egress;
            this.wallDistanceM = wallDistanceM;
        }
    }

    private void addDistinctEgress(List<NormalEgress> result, NormalEgress candidate) {
        if (result.stream().noneMatch(existing -> existing.exit().distance(candidate.exit()) <= 0.1)) {
            result.add(candidate);
        }
    }

    List<RouteValidationIssue> validateMandatoryEgress(
            RouteEdge edge,
            LineString route,
            List<ImportedOfficialFeature> features,
            int diameter) {
        return validateMandatoryEgress(edge, route, features, diameter,
                route.getCoordinateN(route.getNumPoints() - 1));
    }

    List<RouteValidationIssue> validateMandatoryEgress(
            RouteEdge edge, LineString route, List<ImportedOfficialFeature> features,
            int diameter, Coordinate connectionPoint) {
        return validateMandatoryEgress(edge, route, features, diameter, connectionPoint, null);
    }

    /** Фактический прямой ввод проверяется независимо от конечного каталога поисковых лучей. */
    List<RouteValidationIssue> validateMandatoryEgress(
            RouteEdge edge, LineString route, List<ImportedOfficialFeature> features,
            int diameter, Coordinate connectionPoint, PreparedNormalEgressMemo normalEgresses) {
        List<RouteValidationIssue> issues = new ArrayList<>();
        // Рёбра направлены от врезки к потребителю. Финальный ввод проверяется отдельно;
        // близость врезки к чужому ОКС не освобождает её от проверки отступа.
        validateEndpointEgress(edge, route, features, diameter, connectionPoint, issues, normalEgresses);
        return issues;
    }

    private void validateEndpointEgress(
            RouteEdge edge,
            LineString route,
            List<ImportedOfficialFeature> features,
            int diameter,
            Coordinate connectionPoint,
            List<RouteValidationIssue> issues, PreparedNormalEgressMemo normalEgresses) {
        Coordinate endpoint = route.getCoordinateN(route.getNumPoints() - 1);
        Coordinate adjacent = route.getCoordinateN(route.getNumPoints() - 2);
        List<ImportedOfficialFeature> containing = containingOksFeatures(features, diameter, connectionPoint);
        if (containing.isEmpty()) return;
        if (checkedTerminalEgress(features, diameter, connectionPoint, endpoint, adjacent).isEmpty()) {
            issues.add(issue(
                    "OKS_NORMAL_EGRESS_VIOLATION",
                    edge.getId(),
                    "Route must use a legal straight terminal leg with the complete exterior approach"));
        }
    }

    /** Льготу своего ОКС выдаёт проверка фактического ввода, а не наличие подходящего search candidate. */
    Optional<NormalEgress> checkedTerminalEgress(List<ImportedOfficialFeature> features, int diameter,
            Coordinate connectionPoint, Coordinate endpoint, Coordinate adjacent) {
        if (endpoint.distance(connectionPoint) > 2 * EPSILON_M) return Optional.empty();
        LineString actualLeg = line(List.of(endpoint, adjacent));
        for (ImportedOfficialFeature feature : containingOksFeatures(features, diameter, connectionPoint)) {
            NormalEgress egress = new NormalEgress(feature.getFeatureId(), connectionPoint, adjacent);
            if (ownApproachAllowed(egress, actualLeg, feature.getMetricGeometry(), diameter)
                    && terminalLegAllowed(egress, features, diameter, RouteTraversal.REVERSED)) {
                return Optional.of(egress);
            }
        }
        return Optional.empty();
    }

    List<Constraint> routeAvoidanceConstraints(List<LineString> routes) {
        SpatialConstraintRule rule = new SpatialConstraintRule(
                "accepted_route", true, "0.20", null, null, null, null, "1.00");
        List<Constraint> result = new ArrayList<>();
        for (int index = 0; index < routes.size(); index++) {
            LineString route = routes.get(index);
            Geometry blocked = route.buffer(ROUTE_AVOIDANCE_BUFFER_M, 2);
            result.add(new Constraint("accepted-route-" + index, "accepted_route", route, blocked, rule));
        }
        return result;
    }

    /** Совпадения координат недостаточно: исключение принадлежит одному общему ID узла. */
    RouteAvoidance routeAvoidance(RouteEdge candidate, List<RouteEdge> accepted, Map<String, RouteNode> nodes) {
        List<LineString> routes = new ArrayList<>();
        List<Constraint> constraints = new ArrayList<>();
        List<JoinedRouteContact> contacts = new ArrayList<>();
        boolean shared = false;
        SpatialConstraintRule rule = new SpatialConstraintRule(
                "accepted_route", true, "0.20", null, null, null, null, "1.00");
        for (RouteEdge edge : accepted) {
            Constraint.ensureIntersectionActive();
            if (edge.getCoordinates().size() < 2) continue;
            LineString route = line(edge.getCoordinates().stream().map(RouteCoordinate::toCoordinate)
                    .collect(Collectors.toList()));
            routes.add(route);
            Set<String> common = new HashSet<>(List.of(candidate.getUpstreamNodeId(), candidate.getDownstreamNodeId()));
            common.retainAll(List.of(edge.getUpstreamNodeId(), edge.getDownstreamNodeId()));
            JoinedRouteContact contact = null;
            if (common.size() == 1) {
                String id = common.iterator().next();
                RouteNode node = nodes.get(id);
                if (node != null && endpointMatches(candidate, id, node) && endpointMatches(edge, id, node)) {
                    contact = JoinedRouteContact.create(route, node.getCoordinate().toCoordinate(), ROUTE_AVOIDANCE_BUFFER_M);
                }
            }
            shared |= contact != null;
            contacts.add(contact);
        }
        // Без общих узлов старые методы сохраняют свои hooks и сами готовят обычные препятствия.
        if (shared) for (int index = 0; index < routes.size(); index++) {
            Constraint.ensureIntersectionActive();
            LineString route = routes.get(index);
            constraints.add(new Constraint("accepted-route-" + index, "accepted_route", route,
                    route.buffer(ROUTE_AVOIDANCE_BUFFER_M, 2), rule, 0, contacts.get(index)));
        }
        return new RouteAvoidance(routes, constraints, shared);
    }

    private boolean endpointMatches(RouteEdge edge, String id, RouteNode node) {
        if (edge.getCoordinates().size() < 2) return false;
        RouteCoordinate endpoint = edge.getCoordinates().get(id.equals(edge.getUpstreamNodeId())
                ? 0 : edge.getCoordinates().size() - 1);
        return endpoint.toCoordinate().equals2D(node.getCoordinate().toCoordinate());
    }

    List<Constraint> depthAvoidanceConstraints(
            List<ImportedOfficialFeature> features,
            Set<String> featureIds) {
        List<Constraint> result = new ArrayList<>();
        for (ImportedOfficialFeature feature : features) {
            if (!featureIds.contains(feature.getFeatureId())) continue;
            String type = constraintType(feature);
            SpatialConstraintRule original = type == null ? null : catalog.find(type).orElse(null);
            Geometry source = feature.getMetricGeometry();
            if (original == null || original.isForbidden() || source == null || source.isEmpty()) continue;
            double clearance = Math.max(
                    0.0,
                    original.getHorizontalClearanceM().doubleValue() - CLEARANCE_BOUNDARY_EPSILON_M);
            Geometry blocked = source.buffer(clearance, 4);
            SpatialConstraintRule avoidance = new SpatialConstraintRule(
                    type,
                    true,
                    original.getHorizontalClearanceM().toPlainString(),
                    null,
                    null,
                    null,
                    null,
                    "1.00");
            result.add(new Constraint(feature.getFeatureId(), type, source, blocked, avoidance));
        }
        result.sort(CONSTRAINT_ORDER);
        return result;
    }

    boolean segmentAllowed(Coordinate start, Coordinate end, List<Constraint> constraints) {
        return segmentAllowed(start, end, index(constraints));
    }

    boolean segmentAllowed(Coordinate start, Coordinate end, ConstraintIndex constraints) {
        return segmentAllowed(start, end, constraints, false);
    }

    private boolean segmentAllowed(Coordinate start, Coordinate end, ConstraintIndex constraints,
            boolean joinedContactsChecked) {
        if (start.distance(end) <= EPSILON_M) {
            return false;
        }
        LineString segment = geometryFactory.createLineString(new Coordinate[] {start, end});
        Envelope segmentBounds = segment.getEnvelopeInternal();
        PreparedSegmentIntersection.Query intersectionQuery = null;
        LineString roadSegment = null;
        for (Constraint constraint : constraints.query(segmentBounds)) {
            if (constraint.rule.isForbidden()) {
                if (joinedContactsChecked && constraint.joinedContact != null) continue;
                if (!segmentBounds.intersects(constraint.blocked.getEnvelopeInternal())) continue;
                // Эта линия принадлежит текущему вызову и не меняется при проверке ограничений.
                if (intersectionQuery == null) intersectionQuery = new PreparedSegmentIntersection.Query(segment);
                if (constraint.intersectsBlocked(segment, intersectionQuery)) {
                    return false;
                }
                continue;
            }
            if (RoadCrossingClearance.supports(constraint.type)) {
                if (roadSegment == null) roadSegment = constraints.traversal() == RouteTraversal.AS_GIVEN
                        ? segment : (LineString) segment.reverse();
                if (!constraint.roadSegmentAllowed(roadSegment, roadCrossings)) return false;
                continue;
            }
            if (UTILITY_TYPES.contains(constraint.type) && constraint.clearanceM > 0) {
                if (!utilitySegmentAllowed(segment, constraint)) return false;
                continue;
            }
            if (constraint.rule.getMinimumCrossingAngleDegrees() != null) {
                if (constraint.source.getDimension() == 2
                        && meetsPolygonCrossingAngle(segment, constraint)) {
                    // A straight segment has one direction. If that direction is already legal,
                    // an exact polygon intersection cannot make the crossing angle worse.
                    continue;
                }
                Coordinate crossing = specialCrossingCoordinate(segment, constraint);
                if (crossing != null && !meetsCrossingAngle(segment, constraint, crossing)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Обычный участок держит полный осевой отступ. Только буквальный special пересечения и
     * проверенный контакт врезки могут начинать подход внутри этого отступа.
     */
    private boolean utilitySegmentAllowed(LineString segment, Constraint constraint) {
        if (meetsUtilityClearance(segment, constraint.source,
                constraint.clearanceM - CLEARANCE_BOUNDARY_EPSILON_M)) {
            return true;
        }
        LengthIndexedLine indexed = new LengthIndexedLine(segment);
        double length = segment.getLength();
        List<double[]> allowed = new ArrayList<>();
        Coordinate crossing = specialCrossingCoordinate(segment, constraint);
        if (crossing != null) {
            if (!meetsCrossingAngle(segment, constraint, crossing)) return false;
            double extension = constraint.rule.getSpecialExtensionM() == null
                    ? 0 : constraint.rule.getSpecialExtensionM().doubleValue();
            LineString special = crossingGeometry.specialSegment(segment, constraint.source, extension);
            double first = indexed.project(special.getCoordinateN(0));
            double last = indexed.project(special.getCoordinateN(special.getNumPoints() - 1));
            allowed.add(new double[] {Math.min(first, last), Math.max(first, last)});
        }
        Coordinate start = segment.getCoordinateN(0);
        Coordinate end = segment.getCoordinateN(segment.getNumPoints() - 1);
        for (Coordinate tieIn : constraint.utilityTieIns) {
            if (start.distance(tieIn) <= EPSILON_M) {
                allowed.add(new double[] {0, Math.min(TIE_IN_CONTACT_M, length)});
            }
            if (end.distance(tieIn) <= EPSILON_M) {
                allowed.add(new double[] {Math.max(0, length - TIE_IN_CONTACT_M), length});
            }
        }
        if (allowed.isEmpty()) return false;
        allowed.sort(Comparator.comparingDouble(interval -> interval[0]));
        List<double[]> merged = new ArrayList<>();
        for (double[] interval : allowed) {
            if (!merged.isEmpty() && interval[0] <= merged.get(merged.size() - 1)[1]) {
                merged.get(merged.size() - 1)[1] = Math.max(
                        merged.get(merged.size() - 1)[1], interval[1]);
            } else {
                merged.add(interval.clone());
            }
        }
        double cursor = 0;
        boolean allowedBefore = false;
        for (double[] interval : merged) {
            if (interval[0] > cursor && !utilityPartAllowed(indexed, cursor, interval[0],
                    allowedBefore, true, constraint)) return false;
            cursor = interval[1];
            allowedBefore = true;
        }
        return cursor >= length || utilityPartAllowed(
                indexed, cursor, length, allowedBefore, false, constraint);
    }

    /**
     * Сохраняет точное сравнение JTS, но прекращает поиск после найденного нарушения.
     * Порог остановки строго ниже границы: равенство остаётся допустимым только после
     * полного поиска. Envelope-shortcut isWithinDistance здесь намеренно не используется:
     * его округление может отличить расстояния на один ULP у границы допуска.
     */
    static boolean meetsUtilityClearance(LineString segment, Geometry source, double minimumDistance) {
        if (!(minimumDistance > 0) || !Double.isFinite(minimumDistance)) {
            return segment.distance(source) >= minimumDistance;
        }
        if (segment.getNumPoints() == 2 && source instanceof LineString && source.getNumPoints() == 2) {
            LineString sourceLine = (LineString) source;
            Coordinate a = segment.getCoordinateN(0);
            Coordinate b = segment.getCoordinateN(1);
            Coordinate c = sourceLine.getCoordinateN(0);
            Coordinate d = sourceLine.getCoordinateN(1);
            if (distanceCoordinateSafe(a) && distanceCoordinateSafe(b)
                    && distanceCoordinateSafe(c) && distanceCoordinateSafe(d)) {
                // Это та же единственная пара сегментов DistanceOp, без временных списков/массивов.
                double distance = org.locationtech.jts.algorithm.Distance.segmentToSegment(a, b, c, d);
                if (Double.isFinite(distance)) return distance >= minimumDistance;
            }
        }
        return new DistanceOp(segment, source, Math.nextDown(minimumDistance)).distance() >= minimumDistance;
    }

    private static boolean distanceCoordinateSafe(Coordinate coordinate) {
        // Запас до overflow произведений JTS; NaN/Infinity и экстремумы остаются на общем пути.
        return Math.abs(coordinate.x) <= 0x1.0p400 && Math.abs(coordinate.y) <= 0x1.0p400;
    }

    private boolean utilityPartAllowed(
            LengthIndexedLine indexed,
            double start,
            double end,
            boolean allowedBefore,
            boolean allowedAfter,
            Constraint constraint) {
        Geometry part = indexed.extractLine(start, end);
        double distance = part.distance(constraint.source);
        if (distance >= constraint.clearanceM - CLEARANCE_BOUNDARY_EPSILON_M) return true;
        Coordinate[] nearest = DistanceOp.nearestPoints(part, constraint.source);
        double station = start + new LengthIndexedLine(part).project(nearest[0]);
        boolean atStart = allowedBefore && Math.abs(station - start) <= CLEARANCE_BOUNDARY_EPSILON_M;
        boolean atEnd = allowedAfter && Math.abs(station - end) <= CLEARANCE_BOUNDARY_EPSILON_M;
        if (!atStart && !atEnd) return false;
        // Не называем протяжённое параллельное сближение граничным минимумом: после границы
        // расстояние обязано расти к другому концу проверяемой части.
        Coordinate far = indexed.extractPoint(atStart ? end : start);
        return constraint.source.getFactory().createPoint(far).distance(constraint.source)
                > distance + CLEARANCE_BOUNDARY_EPSILON_M;
    }

    private boolean meetsPolygonCrossingAngle(LineString segment, Constraint constraint) {
        Coordinate start = segment.getCoordinateN(0);
        Coordinate end = segment.getCoordinateN(segment.getNumPoints() - 1);
        double routeAngle = Math.atan2(end.y - start.y, end.x - start.x);
        double difference = Math.abs(Math.toDegrees(routeAngle - constraint.sourceAxisAngle)) % 180.0;
        double angle = difference > 90.0 ? 180.0 - difference : difference;
        return OfficialCrossingGeometry.satisfiesMinimumAngle(
                angle, constraint.rule.getMinimumCrossingAngleDegrees().doubleValue());
    }

    boolean pointInsideForbiddenClearance(Coordinate coordinate, ConstraintIndex constraints) {
        org.locationtech.jts.geom.Point point = geometryFactory.createPoint(coordinate);
        for (Constraint constraint : constraints.query(point.getEnvelopeInternal())) {
            if (constraint.rule.isForbidden() && constraint.preparedBlocked.covers(point)) {
                if (constraint.joinedContact != null && constraint.joinedContact.permitsPoint(point)) continue;
                return true;
            }
        }
        return false;
    }

    boolean lineAllowed(LineString line, List<Constraint> constraints) {
        return lineAllowed(line, index(constraints));
    }

    boolean lineAllowed(LineString line, ConstraintIndex constraints) {
        return provisionalSegmentsAllowed(line, constraints) && completeRoadCrossingsAllowed(line, constraints);
    }

    /** Стык должен оставаться концом всей трассы, а не только временно выделенной части ввода. */
    boolean joinedContactsAllowed(LineString line, ConstraintIndex constraints) {
        if (!constraints.hasJoinedContacts) return true;
        for (Constraint constraint : constraints.query(line.getEnvelopeInternal())) {
            if (constraint.joinedContact != null && constraint.intersectsBlocked(line)) return false;
        }
        return true;
    }

    /** Только локальная видимость; не допускает готовый маршрут без полной проверки special. */
    boolean provisionalSegmentsAllowed(LineString line, ConstraintIndex constraints) {
        boolean joinedContactsChecked = false;
        if (constraints.hasJoinedContacts) {
            for (Constraint constraint : constraints.query(line.getEnvelopeInternal())) {
                if (constraint.joinedContact != null) {
                    if (constraint.intersectsBlocked(line)) return false;
                    joinedContactsChecked = true;
                }
            }
        }
        for (int index = 0; index < line.getNumPoints() - 1; index++) {
            boolean allowed = joinedContactsChecked
                    ? segmentAllowed(line.getCoordinateN(index), line.getCoordinateN(index + 1), constraints, true)
                    : segmentAllowed(line.getCoordinateN(index), line.getCoordinateN(index + 1), constraints);
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** Проверяет road/tram на всей физической полилинии, включая обязательный ввод. */
    boolean completeRoadCrossingsAllowed(LineString line, ConstraintIndex constraints) {
        LineString physicalLine = null;
        for (Constraint constraint : constraints.query(line.getEnvelopeInternal())) {
            if (!constraint.rule.isForbidden() && RoadCrossingClearance.supports(constraint.type)) {
                if (physicalLine == null) physicalLine = constraints.traversal() == RouteTraversal.AS_GIVEN
                        ? line : (LineString) line.reverse();
                if (!roadAssessment(physicalLine, constraint).isAllowed()) return false;
            }
        }
        return true;
    }

    /** Отдельный поворот графа не может прервать обязательный прямой road/tram special. */
    boolean specialTurnAllowed(Coordinate before, Coordinate at, Coordinate after, ConstraintIndex constraints) {
        for (Constraint constraint : constraints.roads) {
            double extension = constraint.rule.getSpecialExtensionM().doubleValue();
            org.locationtech.jts.geom.Envelope bounds = constraint.source.getEnvelopeInternal();
            if (at.x < bounds.getMinX() - extension || at.x > bounds.getMaxX() + extension
                    || at.y < bounds.getMinY() - extension || at.y > bounds.getMaxY() + extension) continue;
            if (!roadCrossings.turnAllowed(before, at, after, constraint.source, extension)) return false;
        }
        return true;
    }

    private RoadCrossingClearance.Assessment roadAssessment(LineString route, Constraint constraint) {
        return roadCrossings.assess(route, constraint.source, constraint.clearanceM,
                constraint.rule.getMinimumCrossingAngleDegrees().doubleValue(),
                constraint.rule.getSpecialExtensionM().doubleValue());
    }

    /** Возвращает порядок построения, но интервалы и углы берёт из физического направления ребра. */
    List<RouteSection> sections(LineString route, List<Constraint> constraints, RouteTraversal traversal) {
        Objects.requireNonNull(traversal, "Route traversal is required");
        if (traversal == RouteTraversal.AS_GIVEN) return sections(route, constraints);
        List<RouteSection> physicalSections = sections((LineString) route.reverse(), constraints);
        List<RouteSection> result = new ArrayList<>(physicalSections.size());
        for (int index = physicalSections.size() - 1; index >= 0; index--) {
            RouteSection section = physicalSections.get(index);
            List<RouteCoordinate> coordinates = new ArrayList<>(section.getCoordinates());
            Collections.reverse(coordinates);
            result.add(new RouteSection(section.getKind(), section.getRestrictionType(), section.getRestrictionId(),
                    coordinates, section.getLengthM().doubleValue(), section.getCrossingAngleDegrees() == null
                            ? null : section.getCrossingAngleDegrees().doubleValue()));
        }
        return result;
    }

    List<RouteSection> sections(LineString route, List<Constraint> constraints) {
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        List<Span> spans = new ArrayList<>();
        for (Constraint constraint : constraints) {
            if (!constraint.rule.isForbidden() && RoadCrossingClearance.supports(constraint.type)) {
                for (RoadCrossingClearance.Interval interval : roadAssessment(route, constraint).getIntervals()) {
                    spans.add(new Span(interval.getStartM(), interval.getEndM(), constraint, interval.getAngleDegrees()));
                }
                continue;
            }
            if (constraint.rule.isForbidden() || !hasSpecialCrossing(route, constraint)) {
                continue;
            }
            double extension = constraint.rule.getSpecialExtensionM() == null
                    ? 0.0
                    : constraint.rule.getSpecialExtensionM().doubleValue();
            LineString special = crossingGeometry.specialSegment(route, constraint.source, extension);
            double first = indexed.project(special.getCoordinateN(0));
            double last = indexed.project(special.getCoordinateN(special.getNumPoints() - 1));
            double angle = crossingAngle(route, constraint);
            spans.add(new Span(
                    Math.min(first, last),
                    Math.max(first, last),
                    constraint,
                    angle));
        }
        spans.sort(Comparator.comparingDouble((Span span) -> span.start)
                .thenComparing(span -> span.constraint.type)
                .thenComparing(span -> span.constraint.id));

        List<Double> cuts = new ArrayList<>();
        cuts.add(indexed.getStartIndex());
        cuts.add(indexed.getEndIndex());
        // §4: общая часть пересечений — отдельная секция, не весь union с общими атрибутами.
        for (Span span : spans) {
            cuts.add(span.start);
            cuts.add(span.end);
        }
        cuts = cuts.stream().distinct().sorted().collect(Collectors.toList());
        List<RouteSection> result = new ArrayList<>();
        for (int index = 0; index < cuts.size() - 1; index++) {
            double start = cuts.get(index);
            double end = cuts.get(index + 1);
            if (end - start <= CLEARANCE_BOUNDARY_EPSILON_M) {
                continue;
            }
            double middle = (start + end) / 2.0;
            List<Span> active = spans.stream()
                    .filter(span -> middle >= span.start && middle <= span.end)
                    .collect(Collectors.toList());
            Geometry extracted = indexed.extractLine(start, end);
            List<RouteCoordinate> coordinates = routeCoordinates(extracted.getCoordinates());
            if (active.isEmpty()) {
                result.add(new RouteSection("base", null, null, coordinates, extracted.getLength(), null));
            } else {
                String types = active.stream().map(span -> span.constraint.type).distinct()
                        .collect(Collectors.joining("+"));
                String ids = active.stream().map(span -> span.constraint.id).distinct()
                        .collect(Collectors.joining("+"));
                double angle = active.stream().mapToDouble(span -> span.angle).min().orElse(90.0);
                result.add(new RouteSection("special", types, ids, coordinates, extracted.getLength(), angle));
            }
        }
        if (result.isEmpty()) {
            result.add(new RouteSection(
                    "base", null, null, routeCoordinates(route.getCoordinates()), route.getLength(), null));
        }
        return result;
    }

    List<RouteValidationIssue> validate(
            RouteEdge edge,
            LineString route,
            List<Constraint> constraints) {
        List<RouteValidationIssue> issues = new ArrayList<>(validateForbidden(edge, route, constraints));
        for (Constraint constraint : constraints) {
            if (constraint.rule.isForbidden()) {
                continue;
            }
            if (RoadCrossingClearance.supports(constraint.type)) {
                RoadCrossingClearance.Assessment assessment = roadAssessment(route, constraint);
                if (!assessment.isAllowed()) {
                    issues.add(issue(assessment.getFailureCode(), edge.getId(),
                            "Route violates " + constraint.type + " crossing/clearance at " + constraint.id));
                }
                if (!assessment.getIntervals().isEmpty() && edge.getSections().stream()
                        .filter(section -> "special".equals(section.getKind()))
                        .map(RouteSection::getRestrictionId).filter(java.util.Objects::nonNull)
                        .flatMap(value -> java.util.Arrays.stream(value.split("\\+")))
                        .noneMatch(constraint.id::equals)) {
                    issues.add(issue("SPECIAL_CROSSING_SECTION_MISSING", edge.getId(),
                            "Crossing of " + constraint.type + " is not split into a special section"));
                }
                continue;
            }
            if (!constraint.rule.isForbidden() && hasSpecialCrossing(route, constraint)) {
                if (!meetsCrossingAngle(route, constraint)) {
                    issues.add(issue(
                            "SPECIAL_CROSSING_ANGLE_VIOLATION",
                            edge.getId(),
                            "Route crosses " + constraint.type + " below the minimum angle"));
                }
                boolean represented = edge.getSections().stream()
                        .filter(section -> "special".equals(section.getKind()))
                        .map(RouteSection::getRestrictionId)
                        .filter(value -> value != null)
                        .flatMap(value -> java.util.Arrays.stream(value.split("\\+")))
                        .anyMatch(constraint.id::equals);
                if (!represented) {
                    issues.add(issue(
                            "SPECIAL_CROSSING_SECTION_MISSING",
                            edge.getId(),
                            "Crossing of " + constraint.type + " is not split into a special section"));
                }
            }
        }
        return issues;
    }

    List<RouteValidationIssue> validateForbidden(
            RouteEdge edge,
            LineString route,
            List<Constraint> constraints) {
        List<RouteValidationIssue> issues = new ArrayList<>();
        for (Constraint constraint : constraints) {
            if (constraint.rule.isForbidden() && intersectsInterior(route, constraint)) {
                issues.add(issue(
                        "FORBIDDEN_CLEARANCE_VIOLATION",
                        edge.getId(),
                        "Route violates " + constraint.type + " clearance at " + constraint.id));
            }
        }
        return issues;
    }

    List<Constraint> ownOksFootprintConstraint(List<Constraint> constraints, String oksId) {
        return ownTerminalFootprintConstraints(constraints, Set.of(oksId));
    }

    List<Constraint> ownTerminalFootprintConstraints(List<Constraint> constraints, Set<String> featureIds) {
        return constraints.stream()
                .filter(constraint -> featureIds.contains(constraint.id))
                .filter(constraint -> "oks".equals(constraint.type))
                .map(constraint -> new Constraint(
                        constraint.id,
                        constraint.type,
                        constraint.source,
                        constraint.source,
                        constraint.rule))
                .collect(Collectors.toList());
    }

    List<Constraint> ownTerminalFootprintConstraints(List<Constraint> constraints, NormalEgress egress) {
        return constraints.stream()
                .filter(egress::exempts)
                .map(constraint -> new Constraint(
                        constraint.id,
                        constraint.type,
                        constraint.source,
                        constraint.source,
                        constraint.rule))
                .collect(Collectors.toList());
    }

    /** Льгота ввода действует только на последнем прямом звене, не на остальной трассе. */
    List<RouteValidationIssue> validateOwnTerminalClearance(RouteEdge edge, LineString outside,
            List<Constraint> constraints, NormalEgress egress, int diameter) {
        List<RouteValidationIssue> issues = new ArrayList<>();
        for (Constraint constraint : constraints) {
            if (!egress.exempts(constraint)) continue;
            double clearance = "oks".equals(constraint.type)
                    ? axisClearance.axisClearanceM("oks", diameter, null).doubleValue()
                    : preparationClearanceM(constraint.type, diameter).doubleValue();
            // Точное расстояние не пропускает срезание угла полигонального buffer.
            if (outside.distance(constraint.source) < clearance - CLEARANCE_BOUNDARY_EPSILON_M) {
                issues.add(issue("FORBIDDEN_CLEARANCE_VIOLATION", edge.getId(),
                        "Route violates own " + constraint.type + " clearance outside terminal approach at " + constraint.id));
            }
        }
        return issues;
    }

    LineString line(List<Coordinate> coordinates) {
        return geometryFactory.createLineString(coordinates.toArray(new Coordinate[0]));
    }

    boolean isBuildingFeature(ImportedOfficialFeature feature) {
        return "oks".equals(constraintType(feature));
    }

    boolean isSpecialConstraintFeature(ImportedOfficialFeature feature) {
        String type = constraintType(feature);
        return type != null && catalog.find(type)
                .map(rule -> !rule.isForbidden())
                .orElse(false);
    }

    String constraintType(ImportedOfficialFeature feature) {
        if ("restriction".equals(feature.getObjectType())) {
            String type = feature.getAttributes().path("restriction_type").asText();
            return type.isBlank() ? null : type;
        }
        if ("heat_network".equals(feature.getObjectType())) {
            return "heat_network";
        }
        if ("oks_existing".equals(feature.getObjectType())) {
            return "oks";
        }
        return null;
    }

    private boolean intersectsInterior(LineString line, Constraint constraint) {
        if (!line.getEnvelopeInternal().intersects(constraint.blocked.getEnvelopeInternal())) {
            return false;
        }
        return constraint.intersectsBlocked(line);
    }

    private boolean hasSpecialCrossing(LineString route, Constraint constraint) {
        return specialCrossingCoordinate(route, constraint) != null;
    }

    private Coordinate specialCrossingCoordinate(LineString route, Constraint constraint) {
        if (!route.getEnvelopeInternal().intersects(constraint.source.getEnvelopeInternal())) {
            return null;
        }
        if (constraint.preparedSource != null && !constraint.preparedSource.intersects(route)) {
            return null;
        }
        Geometry intersection = route.intersection(constraint.source);
        if (intersection.isEmpty()) {
            return null;
        }
        if (constraint.source.getDimension() == 2) {
            return intersection.getLength() > EPSILON_M ? intersection.getCoordinate() : null;
        }
        Coordinate routeStart = route.getCoordinateN(0);
        Coordinate routeEnd = route.getCoordinateN(route.getNumPoints() - 1);
        boolean interior = java.util.Arrays.stream(intersection.getCoordinates())
                .anyMatch(coordinate -> coordinate.distance(routeStart) > EPSILON_M
                        && coordinate.distance(routeEnd) > EPSILON_M);
        return interior ? intersection.getCoordinate() : null;
    }

    private boolean meetsCrossingAngle(LineString route, Constraint constraint) {
        Coordinate crossing = specialCrossingCoordinate(route, constraint);
        return crossing == null || meetsCrossingAngle(route, constraint, crossing);
    }

    private boolean meetsCrossingAngle(
            LineString route, Constraint constraint, Coordinate crossing) {
        BigDecimal minimum = constraint.rule.getMinimumCrossingAngleDegrees();
        return minimum == null
                || crossingAngle(route, constraint, crossing) + 1e-9 >= minimum.doubleValue();
    }

    private double crossingAngle(LineString route, Constraint constraint) {
        Coordinate crossing = route.intersection(constraint.source).getCoordinate();
        return crossingAngle(route, constraint, crossing);
    }

    private double crossingAngle(
            LineString route, Constraint constraint, Coordinate crossing) {
        double routeAngle = localAngle(route, crossing);
        double objectAngle = constraint.source.getDimension() == 2
                ? constraint.sourceAxisAngle
                : localAngle(constraint.source, crossing);
        double difference = Math.abs(Math.toDegrees(routeAngle - objectAngle)) % 180.0;
        return difference > 90.0 ? 180.0 - difference : difference;
    }

    private static double polygonAxisAngle(Geometry polygonal) {
        Geometry rectangle = new MinimumDiameter(polygonal).getMinimumRectangle();
        Coordinate[] coordinates = rectangle.getCoordinates();
        LineSegment longest = null;
        for (int index = 0; index < coordinates.length - 1; index++) {
            LineSegment candidate = new LineSegment(coordinates[index], coordinates[index + 1]);
            if (longest == null || candidate.getLength() > longest.getLength()) {
                longest = candidate;
            }
        }
        return longest == null ? 0.0 : Math.atan2(longest.p1.y - longest.p0.y, longest.p1.x - longest.p0.x);
    }

    private double localAngle(Geometry lineal, Coordinate crossing) {
        Coordinate[] coordinates = lineal.getCoordinates();
        double bestDistance = Double.POSITIVE_INFINITY;
        double result = 0.0;
        for (int index = 0; index < coordinates.length - 1; index++) {
            if (coordinates[index].equals2D(coordinates[index + 1])) {
                continue;
            }
            LineSegment segment = new LineSegment(coordinates[index], coordinates[index + 1]);
            double distance = segment.distance(crossing);
            if (distance < bestDistance) {
                bestDistance = distance;
                result = Math.atan2(segment.p1.y - segment.p0.y, segment.p1.x - segment.p0.x);
            }
        }
        return result;
    }

    private List<RouteCoordinate> routeCoordinates(Coordinate[] coordinates) {
        List<RouteCoordinate> result = new ArrayList<>();
        for (Coordinate coordinate : coordinates) {
            RouteCoordinate next = new RouteCoordinate(coordinate.x, coordinate.y);
            if (result.isEmpty()
                    || !result.get(result.size() - 1).toCoordinate().equals2D(next.toCoordinate())) {
                result.add(next);
            }
        }
        return result;
    }

    private RouteValidationIssue issue(String code, String subject, String message) {
        return new RouteValidationIssue(code, subject, message);
    }

    static final class Constraint {
        private final String id;
        private final String type;
        private final Geometry source;
        private final Geometry blocked;
        private final PreparedGeometry preparedBlocked;
        private final PreparedGeometry preparedSource;
        private final double sourceAxisAngle;
        private final long segmentIndexCoordinateReservation;
        private final long roadCrossingCoordinateReservation;
        private volatile PreparedSegmentIntersection segmentIntersection;
        private volatile boolean segmentIntersectionInitialized;
        private volatile PreparedRoadCrossings roadCrossings;
        private volatile boolean roadCrossingsInitialized;
        private final SpatialConstraintRule rule;
        private final double clearanceM;
        private final JoinedRouteContact joinedContact;
        private final List<Coordinate> utilityTieIns;

        private Constraint(
                String id,
                String type,
                Geometry source,
                Geometry blocked,
                SpatialConstraintRule rule) {
            this(id, type, source, blocked, rule, 0);
        }

        private Constraint(String id, String type, Geometry source, Geometry blocked,
                SpatialConstraintRule rule, double clearanceM) {
            this(id, type, source, blocked, rule, clearanceM, null);
        }

        private Constraint(String id, String type, Geometry source, Geometry blocked,
                SpatialConstraintRule rule, double clearanceM, JoinedRouteContact joinedContact) {
            this(id, type, source, blocked, rule, clearanceM, joinedContact, List.of());
        }

        private Constraint(String id, String type, Geometry source, Geometry blocked,
                SpatialConstraintRule rule, double clearanceM, JoinedRouteContact joinedContact,
                List<Coordinate> utilityTieIns) {
            this.id = id;
            this.type = type;
            this.source = source;
            this.blocked = blocked;
            this.preparedBlocked = blocked == null ? null : PreparedGeometryFactory.prepare(blocked);
            boolean specialPolygon = !rule.isForbidden()
                    && rule.getMinimumCrossingAngleDegrees() != null
                    && source.getDimension() == 2;
            this.preparedSource = specialPolygon ? PreparedGeometryFactory.prepare(source) : null;
            this.sourceAxisAngle = specialPolygon ? polygonAxisAngle(source) : 0.0;
            this.segmentIndexCoordinateReservation = PreparedSegmentIntersection.additionalCoordinateReservation(blocked);
            this.roadCrossingCoordinateReservation = !rule.isForbidden() && RoadCrossingClearance.supports(type)
                    ? PreparedRoadCrossings.additionalCoordinateReservation(source) : 0;
            this.rule = rule;
            this.clearanceM = clearanceM;
            this.joinedContact = joinedContact;
            this.utilityTieIns = utilityTieIns.stream().map(Coordinate::new).collect(Collectors.toUnmodifiableList());
        }

        String id() { return id; }
        String type() { return type; }
        Geometry source() { return source; }
        Geometry blocked() { return blocked; }
        PreparedGeometry preparedBlocked() { return preparedBlocked; }
        long segmentIndexCoordinateReservation() { return segmentIndexCoordinateReservation; }
        long roadCrossingCoordinateReservation() { return roadCrossingCoordinateReservation; }
        SpatialConstraintRule rule() { return rule; }
        double clearanceM() { return clearanceM; }

        /** Индекс принадлежит неизменяемому Constraint одного расчёта; память зарезервирована до аллокации. */
        private boolean roadSegmentAllowed(LineString line, RoadCrossingClearance fallback) {
            ensureIntersectionActive();
            if (roadCrossingCoordinateReservation > 0 && !roadCrossingsInitialized) {
                synchronized (this) {
                    if (!roadCrossingsInitialized) {
                        roadCrossings = PreparedRoadCrossings.forReadOnlyConstraint(source);
                        roadCrossingsInitialized = true;
                    }
                }
            }
            ensureIntersectionActive();
            double angle = rule.getMinimumCrossingAngleDegrees().doubleValue();
            double extension = rule.getSpecialExtensionM().doubleValue();
            return roadCrossings == null ? fallback.segmentAllowed(line, source, clearanceM, angle, extension)
                    : roadCrossings.segmentAllowed(line, clearanceM, angle, extension);
        }

        private boolean intersectsBlocked(LineString line) {
            return intersectsBlocked(line, null);
        }

        private boolean intersectsBlocked(LineString line, PreparedSegmentIntersection.Query query) {
            ensureIntersectionActive();
            if (joinedContact != null) {
                return preparedBlocked.intersects(line) && !joinedContact.permitsContact(line, blocked);
            }
            if (line.getNumPoints() != 2 || segmentIndexCoordinateReservation == 0) {
                return preparedBlocked.intersects(line);
            }
            if (!segmentIntersectionInitialized) {
                synchronized (this) {
                    if (!segmentIntersectionInitialized) {
                        segmentIntersection = PreparedSegmentIntersection.forReadOnlyConstraint(blocked);
                        // Отказ тоже публикуется: не сканируем validity на каждом запросе.
                        // Исключение/отмена оставляют инициализацию незавершённой для повторной попытки.
                        segmentIntersectionInitialized = true;
                    }
                }
            }
            ensureIntersectionActive();
            PreparedSegmentIntersection prepared = segmentIntersection;
            if (prepared == null) return preparedBlocked.intersects(line);
            return query == null ? prepared.intersects(line) : prepared.intersectsPrepared(query);
        }

        private static void ensureIntersectionActive() {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Constraint intersection cancelled");
            }
        }
    }

    static final class NormalEgress {
        private final String oksId;
        private final Coordinate start;
        private final Coordinate exit;
        private final boolean alternative;

        private NormalEgress(String oksId, Coordinate start, Coordinate exit) {
            this(oksId, start, exit, false);
        }

        private NormalEgress(String oksId, Coordinate start, Coordinate exit, boolean alternative) {
            this.oksId = oksId;
            this.start = new Coordinate(start);
            this.exit = new Coordinate(exit);
            this.alternative = alternative;
        }

        String oksId() { return oksId; }
        Coordinate start() { return new Coordinate(start); }
        Coordinate exit() { return new Coordinate(exit); }
        Set<String> terminalExemptionIds() { return Set.of(oksId); }

        /** ТЗ §2.2: льгота финального ввода относится только к своему ОКС, остальные запреты сохраняются. */
        boolean exempts(Constraint constraint) {
            return "oks".equals(constraint.type()) && oksId.equals(constraint.id());
        }
    }

    static final class ConstraintIndex {
        private final RouteTraversal traversal;
        private final boolean hasJoinedContacts;
        // На малых наборах отбор и упорядочивание кандидатов дороже линейного обхода.
        private static final int LINEAR_SCAN_THRESHOLD = 128;
        private final List<Constraint> all;
        private final List<Constraint> roads;
        private final STRtree tree;

        private ConstraintIndex(List<Constraint> constraints, RouteTraversal traversal) {
            this.traversal = Objects.requireNonNull(traversal, "Route traversal is required");
            all = List.copyOf(constraints);
            hasJoinedContacts = all.stream().anyMatch(item -> item.joinedContact != null);
            roads = all.stream().filter(item -> !item.rule.isForbidden()
                    && RoadCrossingClearance.supports(item.type)).collect(Collectors.toList());
            if (constraints.size() < LINEAR_SCAN_THRESHOLD) {
                tree = null;
            } else {
                tree = new STRtree();
                for (int ordinal = 0; ordinal < all.size(); ordinal++) {
                    Constraint constraint = all.get(ordinal);
                    Geometry indexed = constraint.blocked != null ? constraint.blocked : constraint.source;
                    if (indexed != null && !indexed.isEmpty()) {
                        org.locationtech.jts.geom.Envelope bounds = new org.locationtech.jts.geom.Envelope(
                                indexed.getEnvelopeInternal());
                        // Полигональный buffer приближает дуги внутрь. Для точного road-clearance
                        // envelope расширяется по source на полный радиус, в том числе после поворота.
                        if (!constraint.rule.isForbidden() && RoadCrossingClearance.supports(constraint.type)) {
                            org.locationtech.jts.geom.Envelope exact = new org.locationtech.jts.geom.Envelope(
                                    constraint.source.getEnvelopeInternal());
                            exact.expandBy(constraint.clearanceM);
                            bounds.expandToInclude(exact);
                        }
                        tree.insert(bounds, ordinal);
                    }
                }
                tree.build();
            }
        }

        RouteTraversal traversal() { return traversal; }

        boolean hasRoadCrossings() { return !roads.isEmpty(); }

        List<Constraint> query(org.locationtech.jts.geom.Envelope envelope) {
            if (tree == null) {
                return all;
            }
            // STRtree обходит элементы в пространственном порядке. Возвращаем исходный порядок,
            // поскольку от него зависят индексы навигационных узлов и разрешение равенств поиска.
            OrdinalHits ordinals = new OrdinalHits();
            tree.query(envelope, ordinals);
            if (ordinals.size == 0) {
                return List.of();
            }
            if (ordinals.size == 1) {
                return List.of(all.get(ordinals.first));
            }
            if (ordinals.size == 2) {
                int first = ordinals.first;
                int second = ordinals.second;
                return first < second ? List.of(all.get(first), all.get(second))
                        : List.of(all.get(second), all.get(first));
            }
            Arrays.sort(ordinals.values, 0, ordinals.size);
            List<Constraint> result = new ArrayList<>(ordinals.size);
            for (int index = 0; index < ordinals.size; index++) {
                result.add(all.get(ordinals.values[index]));
            }
            return result;
        }

        /** Данные только одного запроса; для частых 0–2 попаданий массив не создаётся. */
        private static final class OrdinalHits implements ItemVisitor {
            private int first;
            private int second;
            private int[] values;
            private int size;

            @Override
            public void visitItem(Object item) {
                int ordinal = (Integer) item;
                if (size == 0) { first = ordinal; size = 1; return; }
                if (size == 1) { second = ordinal; size = 2; return; }
                if (values == null) {
                    values = new int[16];
                    values[0] = first;
                    values[1] = second;
                } else if (size == values.length) {
                    values = Arrays.copyOf(values, size + size / 2);
                }
                values[size++] = ordinal;
            }
        }
    }

    private static final class Span {
        private final double start;
        private final double end;
        private final Constraint constraint;
        private final double angle;

        private Span(double start, double end, Constraint constraint, double angle) {
            this.start = start;
            this.end = end;
            this.constraint = constraint;
            this.angle = angle;
        }
    }

}
