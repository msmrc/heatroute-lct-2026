package ru.lct.heatroute.domain.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Каталог вместе с telemetry полноты; truncation никогда не трактуется как доказанный no-route. */
public final class CatalogBuildResult {
    public enum Status { COMPLETE, CATALOG_INCOMPLETE }

    private final RoutingCatalogSnapshot snapshot;
    private final Status status;
    private final Map<String, Long> counters;
    private final Map<String, Boolean> generatorCoverage;
    private final List<String> remainingWork;
    private final List<String> truncationReasons;

    public CatalogBuildResult(RoutingCatalogSnapshot snapshot, Map<String, Long> counters,
            Map<String, Boolean> generatorCoverage, Collection<String> remainingWork,
            Collection<String> truncationReasons) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.counters = nonNegativeCounters(counters);
        this.generatorCoverage = sortedCoverage(generatorCoverage);
        this.remainingWork = sortedUnique(remainingWork, "remaining work");
        this.truncationReasons = sortedUnique(truncationReasons, "truncation reason");
        boolean incomplete = !this.remainingWork.isEmpty() || !this.truncationReasons.isEmpty()
                || this.generatorCoverage.values().stream().anyMatch(covered -> !covered);
        this.status = incomplete ? Status.CATALOG_INCOMPLETE : Status.COMPLETE;
    }

    public RoutingCatalogSnapshot getSnapshot() { return snapshot; }
    public Status getStatus() { return status; }
    public Map<String, Long> getCounters() { return counters; }
    public Map<String, Boolean> getGeneratorCoverage() { return generatorCoverage; }
    public List<String> getRemainingWork() { return remainingWork; }
    public List<String> getTruncationReasons() { return truncationReasons; }
    public boolean isComplete() { return status == Status.COMPLETE; }

    private static Map<String, Long> nonNegativeCounters(Map<String, Long> supplied) {
        if (supplied == null) throw new IllegalArgumentException("Catalog counters are required");
        Map<String, Long> result = new LinkedHashMap<>();
        supplied.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String key = required(entry.getKey(), "counter name");
            Long value = Objects.requireNonNull(entry.getValue(), "counter value");
            if (value < 0L) throw new IllegalArgumentException("Catalog counter cannot be negative: " + key);
            result.put(key, value);
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Map<String, Boolean> sortedCoverage(Map<String, Boolean> supplied) {
        if (supplied == null || supplied.isEmpty()) {
            throw new IllegalArgumentException("Generator coverage is required");
        }
        Map<String, Boolean> result = new LinkedHashMap<>();
        supplied.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                result.put(required(entry.getKey(), "generator ID"),
                        Objects.requireNonNull(entry.getValue(), "coverage value")));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static List<String> sortedUnique(Collection<String> supplied, String label) {
        if (supplied == null) throw new IllegalArgumentException(label + " collection is required");
        List<String> result = new ArrayList<>(supplied.size());
        for (String value : supplied) result.add(required(value, label));
        result.sort(Comparator.naturalOrder());
        if (result.stream().distinct().count() != result.size()) {
            throw new IllegalArgumentException(label + " values must be unique");
        }
        return List.copyOf(result);
    }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }
}
