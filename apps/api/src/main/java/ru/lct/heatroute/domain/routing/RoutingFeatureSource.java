package ru.lct.heatroute.domain.routing;

import java.util.List;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Lazily supplies bulky routing constraints for one calculation import. */
public interface RoutingFeatureSource {
    List<ImportedOfficialFeature> findInMetricWindow(Envelope window);

    List<ImportedOfficialFeature> findByFeatureIds(Set<String> featureIds);
}
