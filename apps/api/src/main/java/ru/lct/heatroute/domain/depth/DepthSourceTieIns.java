package ru.lct.heatroute.domain.depth;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import ru.lct.heatroute.domain.routing.RouteCoordinate;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/**
 * Реальная внешняя врезка: указанный объект источника и подтверждённая геометрическая
 * инцидентность.
 */
final class DepthSourceTieIns {
    static Set<String> heatIds(
            Set<String> targetIds,
            RouteCoordinate endpoint,
            List<ImportedOfficialFeature> features) {
        Set<String> result = new HashSet<>();
        Point point = new GeometryFactory().createPoint(endpoint.toCoordinate());
        boolean chamber = false;
        for (ImportedOfficialFeature feature : features) {
            if (!targetIds.contains(feature.getFeatureId())
                    || feature.getMetricGeometry() == null
                    || feature.getMetricGeometry().distance(point) > 0.05) {
                continue;
            }
            if ("heat_network".equals(feature.getObjectType())) {
                result.add(feature.getFeatureId());
            }
            if ("heat_chamber".equals(feature.getObjectType())) {
                chamber = true;
            }
        }
        if (chamber) {
            for (ImportedOfficialFeature feature : features) {
                if ("heat_network".equals(feature.getObjectType())
                        && feature.getMetricGeometry() != null
                        && feature.getMetricGeometry().distance(point) <= 0.05) {
                    result.add(feature.getFeatureId());
                }
            }
        }
        return result;
    }
}
