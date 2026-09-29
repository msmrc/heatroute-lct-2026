package ru.lct.heatroute.domain.routing;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.run.RoutingAlgorithmProfile;

/** Resolves both compatible API profile names to the single production NextGen adapter. */
@Component
public class RoutingAlgorithmRegistry {
    private final Map<RoutingAlgorithmProfile, RoutingAlgorithm> algorithms;

    public RoutingAlgorithmRegistry(List<RoutingAlgorithm> implementations) {
        EnumMap<RoutingAlgorithmProfile, RoutingAlgorithm> indexed =
                new EnumMap<>(RoutingAlgorithmProfile.class);
        for (RoutingAlgorithm implementation : implementations) {
            if (implementation.profile() != RoutingAlgorithmProfile.STABLE) {
                throw new IllegalStateException("Only the stable routing implementation may be registered");
            }
            RoutingAlgorithm previous = indexed.put(implementation.profile(), implementation);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate routing algorithm profile: " + implementation.profile().getWireName());
            }
        }
        if (!indexed.containsKey(RoutingAlgorithmProfile.STABLE)) {
            throw new IllegalStateException("Missing routing algorithm profile: stable");
        }
        this.algorithms = Map.copyOf(indexed);
    }

    public RoutingAlgorithm require(RoutingAlgorithmProfile profile) {
        if (profile == RoutingAlgorithmProfile.STABLE
                || profile == RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL) {
            // Persisted parameters and old API clients remain readable without data migration.
            return algorithms.get(RoutingAlgorithmProfile.STABLE);
        }
        throw new IllegalArgumentException("Unsupported algorithm_profile: " + profile);
    }

    /** Не позволяет очереди молча выполнить сохранённый run другой версией движка. */
    public RoutingAlgorithm requireVersion(RoutingAlgorithmProfile profile, String expectedVersion) {
        RoutingAlgorithm algorithm = require(profile);
        if (expectedVersion == null || !expectedVersion.equals(algorithm.version())) {
            throw new RoutingEngineVersionUnavailableException(expectedVersion, algorithm.version());
        }
        return algorithm;
    }
}
