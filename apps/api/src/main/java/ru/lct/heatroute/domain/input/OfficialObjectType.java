package ru.lct.heatroute.domain.input;

import java.util.Arrays;
import java.util.Optional;

public enum OfficialObjectType {
    SOURCE("source"),
    HEAT_NETWORK("heat_network"),
    HEAT_CHAMBER("heat_chamber"),
    OKS_FUTURE("oks_future"),
    OKS_CONNECTION_POINT("oks_connection_point"),
    OKS_EXISTING("oks_existing"),
    RESTRICTION("restriction");

    private final String wireName;

    OfficialObjectType(String wireName) {
        this.wireName = wireName;
    }

    public String getWireName() {
        return wireName;
    }

    public static Optional<OfficialObjectType> fromWireName(String value) {
        return Arrays.stream(values()).filter(type -> type.wireName.equals(value)).findFirst();
    }
}
