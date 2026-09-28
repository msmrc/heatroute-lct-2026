package ru.lct.heatroute.domain.optimization;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Неизменяемый каталог взаимоисключающих альтернатив для CP-SAT master-задачи.
 * Стоимости — неотрицательные целые единицы, выбранные вызывающим кодом без double-округления.
 */
public final class DiscreteChoiceProblem {
    private final List<Group> groups;
    private final List<Conflict> conflicts;
    private final Map<String, Group> groupsById;

    public DiscreteChoiceProblem(Collection<Group> groups, Collection<Conflict> conflicts) {
        if (groups == null || groups.isEmpty() || conflicts == null) {
            throw new IllegalArgumentException("Non-empty groups and conflicts collection required");
        }
        List<Group> orderedGroups = new ArrayList<>(groups);
        orderedGroups.sort(Comparator.comparing(Group::getId));
        Map<String, Group> indexed = new LinkedHashMap<>();
        for (Group group : orderedGroups) {
            if (group == null || indexed.put(group.getId(), group) != null) {
                throw new IllegalArgumentException("Group IDs must be unique");
            }
        }
        List<Conflict> orderedConflicts = new ArrayList<>(conflicts);
        orderedConflicts.sort(Comparator.comparing(Conflict::signature));
        Set<String> signatures = new HashSet<>();
        for (Conflict conflict : orderedConflicts) {
            if (conflict == null || !signatures.add(conflict.signature())) {
                throw new IllegalArgumentException("Conflicts must be non-null and unique");
            }
            for (Choice choice : conflict.getChoices()) validateChoice(indexed, choice);
        }
        this.groups = List.copyOf(orderedGroups);
        this.conflicts = List.copyOf(orderedConflicts);
        this.groupsById = Collections.unmodifiableMap(indexed);
    }

    private static void validateChoice(Map<String, Group> groups, Choice choice) {
        Group group = groups.get(choice.getGroupId());
        if (group == null || group.alternativesById.get(choice.getAlternativeId()) == null) {
            throw new IllegalArgumentException("Conflict references an unknown choice: " + choice);
        }
    }

    public List<Group> getGroups() { return groups; }
    public List<Conflict> getConflicts() { return conflicts; }
    public Group group(String id) { return groupsById.get(id); }

    /** Одна обязательная группа: solver выбирает ровно одну альтернативу. */
    public static final class Group {
        private final String id;
        private final List<Alternative> alternatives;
        private final Map<String, Alternative> alternativesById;

        public Group(String id, Collection<Alternative> alternatives) {
            this.id = requireId(id, "group");
            if (alternatives == null || alternatives.isEmpty()) {
                throw new IllegalArgumentException("Every group requires at least one alternative");
            }
            List<Alternative> ordered = new ArrayList<>(alternatives);
            ordered.sort(Comparator.comparing(Alternative::getId));
            Map<String, Alternative> indexed = new HashMap<>();
            for (Alternative alternative : ordered) {
                if (alternative == null || indexed.put(alternative.getId(), alternative) != null) {
                    throw new IllegalArgumentException("Alternative IDs must be unique inside a group");
                }
            }
            this.alternatives = List.copyOf(ordered);
            this.alternativesById = Collections.unmodifiableMap(indexed);
        }

        public String getId() { return id; }
        public List<Alternative> getAlternatives() { return alternatives; }
        public Alternative alternative(String alternativeId) { return alternativesById.get(alternativeId); }
    }

    /** Альтернатива с целочисленной направляющей стоимостью. */
    public static final class Alternative {
        private final String id;
        private final long cost;

        public Alternative(String id, long cost) {
            this.id = requireId(id, "alternative");
            if (cost < 0L) throw new IllegalArgumentException("Alternative cost cannot be negative");
            this.cost = cost;
        }

        public String getId() { return id; }
        public long getCost() { return cost; }
    }

    /** Ссылка на один Boolean literal каталога. */
    public static final class Choice {
        private final String groupId;
        private final String alternativeId;

        public Choice(String groupId, String alternativeId) {
            this.groupId = requireId(groupId, "choice group");
            this.alternativeId = requireId(alternativeId, "choice alternative");
        }

        public String getGroupId() { return groupId; }
        public String getAlternativeId() { return alternativeId; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Choice)) return false;
            Choice choice = (Choice) other;
            return groupId.equals(choice.groupId) && alternativeId.equals(choice.alternativeId);
        }

        @Override
        public int hashCode() { return Objects.hash(groupId, alternativeId); }

        @Override
        public String toString() { return groupId + "=" + alternativeId; }
    }

    /** Набор literal-ов, которые доказанно нельзя выбирать одновременно. */
    public static final class Conflict {
        private final List<Choice> choices;
        private final String reason;

        public Conflict(Collection<Choice> choices, String reason) {
            if (choices == null || choices.isEmpty()) {
                throw new IllegalArgumentException("A conflict requires at least one selected choice");
            }
            LinkedHashSet<Choice> unique = new LinkedHashSet<>(choices);
            if (unique.size() != choices.size()) throw new IllegalArgumentException("Duplicate conflict choice");
            List<Choice> ordered = new ArrayList<>(unique);
            ordered.sort(Comparator.comparing(Choice::getGroupId).thenComparing(Choice::getAlternativeId));
            this.choices = List.copyOf(ordered);
            this.reason = requireId(reason, "conflict reason");
        }

        public List<Choice> getChoices() { return choices; }
        public String getReason() { return reason; }
        String signature() { return choices.toString() + "#" + reason; }
    }

    private static String requireId(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " ID required");
        return value;
    }
}
