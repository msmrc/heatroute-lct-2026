package ru.lct.heatroute.domain.optimization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Хранит проверенные cuts между solve-вызовами и фильтрует их после смены catalog scope. */
public final class ConflictStore {
    private static final int DEFAULT_MAX_CONFLICTS = 10_000;
    private final Map<String, ConflictExplanation> explanations = new LinkedHashMap<>();
    private final int maxConflicts;

    public ConflictStore() { this(DEFAULT_MAX_CONFLICTS); }

    public ConflictStore(int maxConflicts) {
        if (maxConflicts <= 0) throw new IllegalArgumentException("Positive conflict-store capacity required");
        this.maxConflicts = maxConflicts;
    }

    public synchronized boolean add(ConflictExplanation explanation) {
        Objects.requireNonNull(explanation, "explanation");
        if (explanations.containsKey(explanation.getSignature())) return false;
        if (explanations.size() >= maxConflicts) {
            throw new ConflictStoreCapacityExceededException(maxConflicts);
        }
        explanations.put(explanation.getSignature(), explanation);
        return true;
    }

    public synchronized List<ConflictExplanation> activeFor(CatalogIdentity identity) {
        return explanations.values().stream().filter(explanation -> explanation.appliesTo(identity))
                .collect(Collectors.toUnmodifiableList());
    }

    public synchronized List<NetworkConstraintProblem.Conflict> modelConflicts(CatalogIdentity identity) {
        List<NetworkConstraintProblem.Conflict> result = new ArrayList<>();
        for (ConflictExplanation explanation : explanations.values()) {
            if (explanation.appliesTo(identity)) result.add(explanation.toModelConflict());
        }
        return List.copyOf(result);
    }

    public synchronized int size() { return explanations.size(); }
}
