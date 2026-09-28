package ru.lct.heatroute.domain.routing;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.topology.ImportedOfficialFeature;

/** Один раз подготавливает восстановление специальных секций для неизменяемого окна объектов. */
@Component
public final class CatalogEdgeSectionAssemblerFactory {
    private final OfficialObstacleRouter router;

    public CatalogEdgeSectionAssemblerFactory(OfficialObstacleRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    public PreparedAssembler prepare(Collection<ImportedOfficialFeature> relevantFeatures) {
        return prepare(PreparedRoutingFeatureWindow.prepare(router, relevantFeatures));
    }

    public PreparedAssembler prepare(PreparedRoutingFeatureWindow featureWindow) {
        Objects.requireNonNull(featureWindow, "featureWindow");
        return new PreparedAssembler(router, featureWindow.environment());
    }

    public static final class PreparedAssembler
            implements CatalogFrozenCandidateAssembler.EdgeSectionAssembler {
        private final OfficialObstacleRouter router;
        private final OfficialRoutingEnvironment environment;
        private final AtomicLong assemblyCalls = new AtomicLong();
        private final AtomicLong fallbackAssemblies = new AtomicLong();

        private PreparedAssembler(OfficialObstacleRouter router,
                OfficialRoutingEnvironment environment) {
            this.router = router;
            this.environment = environment;
        }

        @Override
        public List<RouteSection> sections(CatalogFrozenCandidateAssembler.EdgeAssembly edge) {
            Objects.requireNonNull(edge, "edge");
            assemblyCalls.incrementAndGet();
            List<Coordinate> coordinates = edge.getCoordinates().stream()
                    .map(RouteCoordinate::toCoordinate).collect(Collectors.toList());
            Envelope bounds = new Envelope();
            coordinates.forEach(bounds::expandToInclude);
            RoutePath complete = router.completeCheckedCorridorAssembly(
                    edge.getDiameterMm(), environment, bounds, coordinates.get(0),
                    edge.getUpstreamTargetId(), coordinates);
            if (complete != null) return complete.sections();

            // A hybrid master path can join individually valid atoms into an invalid road turn.
            // Preserve the frozen XY with neutral sections: the independent final validator then
            // returns a normal engineering rejection and the refinement loop can cut this exact
            // assignment instead of turning a catalog combination into a technical ERROR.
            fallbackAssemblies.incrementAndGet();
            return List.of(new RouteSection("base", null, null, edge.getCoordinates(),
                    lengthM(edge.getCoordinates()), null));
        }

        public long getAssemblyCalls() { return assemblyCalls.get(); }
        public long getFallbackAssemblies() { return fallbackAssemblies.get(); }
    }

    private static double lengthM(List<RouteCoordinate> coordinates) {
        double result = 0.0;
        for (int index = 1; index < coordinates.size(); index++) {
            RouteCoordinate left = coordinates.get(index - 1);
            RouteCoordinate right = coordinates.get(index);
            result += Math.hypot(right.getXM().doubleValue() - left.getXM().doubleValue(),
                    right.getYM().doubleValue() - left.getYM().doubleValue());
        }
        return result;
    }
}
