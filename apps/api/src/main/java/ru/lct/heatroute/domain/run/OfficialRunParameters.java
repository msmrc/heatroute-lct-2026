package ru.lct.heatroute.domain.run;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Parameters of an immutable HeatRoute calculation")
public class OfficialRunParameters {
    public static final BigDecimal PUBLISHED_MINIMUM_DEPTH_M = new BigDecimal("0.7");
    public static final BigDecimal ORDINARY_DEPTH_M = new BigDecimal("3.0");
    public static final BigDecimal DEFAULT_MAXIMUM_DEPTH_M = new BigDecimal("10.0");
    public static final BigDecimal APPLICATION_MAXIMUM_DEPTH_M = new BigDecimal("50.0");

    @Schema(name = "minimum_depth_m", description = "Minimum depth used only when depth_enabled is true",
            example = "0.7", defaultValue = "0.7", minimum = "0.7", maximum = "3.0")
    private final BigDecimal minimumDepthM;
    @Schema(name = "maximum_depth_m", description = "Maximum depth used only when depth_enabled is true",
            example = "10.0", defaultValue = "10.0", minimum = "3.0", maximum = "50.0")
    private final BigDecimal maximumDepthM;
    @Schema(name = "depth_enabled", description = "Enable the optional depth/profile stage",
            example = "false", defaultValue = "false")
    private final boolean depthEnabled;
    public OfficialRunParameters(BigDecimal minimumDepthM, BigDecimal maximumDepthM) {
        this(minimumDepthM, maximumDepthM, false);
    }

    public OfficialRunParameters(
            BigDecimal minimumDepthM,
            BigDecimal maximumDepthM,
            boolean depthEnabled) {
        this.minimumDepthM = minimumDepthM == null ? PUBLISHED_MINIMUM_DEPTH_M : minimumDepthM;
        this.maximumDepthM = maximumDepthM == null ? DEFAULT_MAXIMUM_DEPTH_M : maximumDepthM;
        this.depthEnabled = depthEnabled;
    }

    @JsonCreator
    public static OfficialRunParameters fromJson(
            @JsonProperty("minimum_depth_m") BigDecimal minimumDepthM,
            @JsonProperty("maximum_depth_m") BigDecimal maximumDepthM,
            @JsonProperty("depth_enabled") Boolean depthEnabled) {
        return new OfficialRunParameters(
                minimumDepthM, maximumDepthM, Boolean.TRUE.equals(depthEnabled));
    }

    public static OfficialRunParameters defaults() {
        return new OfficialRunParameters(
                PUBLISHED_MINIMUM_DEPTH_M,
                DEFAULT_MAXIMUM_DEPTH_M,
                false);
    }

    public OfficialRunParameters validated() {
        if (minimumDepthM.compareTo(PUBLISHED_MINIMUM_DEPTH_M) < 0) {
            throw new IllegalArgumentException("minimum_depth_m must be at least 0.7");
        }
        if (minimumDepthM.compareTo(ORDINARY_DEPTH_M) > 0) {
            throw new IllegalArgumentException("minimum_depth_m must include the ordinary 3.0 m depth");
        }
        if (maximumDepthM.compareTo(ORDINARY_DEPTH_M) < 0) {
            throw new IllegalArgumentException("maximum_depth_m must include the ordinary 3.0 m depth");
        }
        if (maximumDepthM.compareTo(minimumDepthM) < 0) {
            throw new IllegalArgumentException("maximum_depth_m must not be less than minimum_depth_m");
        }
        if (maximumDepthM.compareTo(APPLICATION_MAXIMUM_DEPTH_M) > 0) {
            throw new IllegalArgumentException("maximum_depth_m must not exceed 50.0");
        }
        return this;
    }

    public BigDecimal getMinimumDepthM() { return minimumDepthM; }
    public BigDecimal getMaximumDepthM() { return maximumDepthM; }
    public boolean isDepthEnabled() { return depthEnabled; }
}
