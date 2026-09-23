package ru.lct.heatroute.domain.run;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** Выбирает изолированный профиль планировщика для неизменяемого запуска. */
public enum RoutingAlgorithmProfile {
    STABLE("stable"),
    EXPERT_EXPERIMENTAL("expert_experimental");

    private final String wireName;

    RoutingAlgorithmProfile(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String getWireName() {
        return wireName;
    }

    @JsonCreator
    public static RoutingAlgorithmProfile fromWireName(String value) {
        if (value == null || value.isBlank()) {
            return STABLE;
        }
        return Arrays.stream(values())
                .filter(profile -> profile.wireName.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown algorithm_profile: " + value));
    }
}
