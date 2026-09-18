package ru.lct.heatroute.domain.routing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OfficialCalculationResult {
    private final String algorithmVersion;
    private final String inputProfile;
    private final int demandCount;
    private final List<RouteVariant> variants;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String preferredVariantId;

    public OfficialCalculationResult(
            String algorithmVersion,
            int demandCount,
            List<RouteVariant> variants,
            String preferredVariantId) {
        this(algorithmVersion, null, demandCount, variants, preferredVariantId);
    }

    public OfficialCalculationResult(
            String algorithmVersion,
            String inputProfile,
            int demandCount,
            List<RouteVariant> variants,
            String preferredVariantId) {
        this.algorithmVersion = algorithmVersion;
        this.inputProfile = inputProfile;
        this.demandCount = demandCount;
        this.variants = Collections.unmodifiableList(new ArrayList<>(variants));
        this.preferredVariantId = preferredVariantId;
    }

    public String getAlgorithmVersion() { return algorithmVersion; }
    public String getInputProfile() { return inputProfile; }
    public int getDemandCount() { return demandCount; }
    public List<RouteVariant> getVariants() { return variants; }
    public String getPreferredVariantId() { return preferredVariantId; }
}
