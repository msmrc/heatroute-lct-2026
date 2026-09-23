package ru.lct.heatroute.domain.routing;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.run.RoutingAlgorithmProfile;

/** Сопоставляет сохранённый профиль запуска с единственной реализацией алгоритма. */
@Component
public class RoutingAlgorithmRegistry {
    private final Map<RoutingAlgorithmProfile, RoutingAlgorithm> algorithms;

    public RoutingAlgorithmRegistry(List<RoutingAlgorithm> implementations) {
        EnumMap<RoutingAlgorithmProfile, RoutingAlgorithm> indexed =
                new EnumMap<>(RoutingAlgorithmProfile.class);
        for (RoutingAlgorithm implementation : implementations) {
            RoutingAlgorithm previous = indexed.put(implementation.profile(), implementation);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate routing algorithm profile: " + implementation.profile().getWireName());
            }
        }
        for (RoutingAlgorithmProfile profile : RoutingAlgorithmProfile.values()) {
            if (!indexed.containsKey(profile)) {
                throw new IllegalStateException("Missing routing algorithm profile: " + profile.getWireName());
            }
        }
        this.algorithms = Map.copyOf(indexed);
    }

    public RoutingAlgorithm require(RoutingAlgorithmProfile profile) {
        RoutingAlgorithm algorithm = algorithms.get(profile);
        if (algorithm == null) {
            throw new IllegalArgumentException("Unsupported algorithm_profile: " + profile.getWireName());
        }
        return algorithm;
    }
}
