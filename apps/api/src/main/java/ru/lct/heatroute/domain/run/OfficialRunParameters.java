package ru.lct.heatroute.domain.run;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public class OfficialRunParameters {
    public static final BigDecimal PUBLISHED_MINIMUM_DEPTH_M = new BigDecimal("0.7");
    public static final BigDecimal ORDINARY_DEPTH_M = new BigDecimal("3.0");
    public static final BigDecimal DEFAULT_MAXIMUM_DEPTH_M = new BigDecimal("10.0");
    public static final BigDecimal APPLICATION_MAXIMUM_DEPTH_M = new BigDecimal("50.0");

    private final BigDecimal minimumDepthM;
    private final BigDecimal maximumDepthM;

    @JsonCreator
    public OfficialRunParameters(
            @JsonProperty("minimum_depth_m") BigDecimal minimumDepthM,
            @JsonProperty("maximum_depth_m") BigDecimal maximumDepthM) {
        this.minimumDepthM = minimumDepthM == null ? PUBLISHED_MINIMUM_DEPTH_M : minimumDepthM;
        this.maximumDepthM = maximumDepthM == null ? DEFAULT_MAXIMUM_DEPTH_M : maximumDepthM;
    }

    public static OfficialRunParameters defaults() {
        return new OfficialRunParameters(PUBLISHED_MINIMUM_DEPTH_M, DEFAULT_MAXIMUM_DEPTH_M);
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
}
