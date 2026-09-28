package ru.lct.heatroute.domain.routing;

import java.util.Collection;
import java.util.Objects;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Создаёт собственный снимок объектов и повторно используемое routing-окружение. */
@Component
public final class RoutingFeatureWindowFactory {
    private final OfficialObstacleRouter router;

    public RoutingFeatureWindowFactory(OfficialObstacleRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    public PreparedRoutingFeatureWindow prepare(
            Collection<ImportedOfficialFeature> relevantFeatures) {
        return PreparedRoutingFeatureWindow.prepare(router, relevantFeatures);
    }
}
