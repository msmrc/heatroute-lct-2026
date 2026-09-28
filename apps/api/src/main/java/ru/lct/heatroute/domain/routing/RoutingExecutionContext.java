package ru.lct.heatroute.domain.routing;

import java.util.Objects;
import java.util.UUID;

/** Неизменяемая идентичность импортированного набора для одного запуска маршрутизации. */
public final class RoutingExecutionContext {
    private final UUID importId;
    private final String sourceHash;
    private final String inputContractVersion;
    private final String inputProfile;

    public RoutingExecutionContext(UUID importId, String sourceHash,
            String inputContractVersion, String inputProfile) {
        this.importId = Objects.requireNonNull(importId, "importId");
        this.sourceHash = required(sourceHash, "source hash");
        this.inputContractVersion = required(inputContractVersion, "input contract version");
        this.inputProfile = required(inputProfile, "input profile");
    }

    public UUID getImportId() { return importId; }
    public String getSourceHash() { return sourceHash; }
    public String getInputContractVersion() { return inputContractVersion; }
    public String getInputProfile() { return inputProfile; }

    private static String required(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return result;
    }
}
