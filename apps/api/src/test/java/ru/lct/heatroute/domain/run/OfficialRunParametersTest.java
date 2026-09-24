package ru.lct.heatroute.domain.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OfficialRunParametersTest {
    @Test
    void suppliesPublishedDefaults() {
        OfficialRunParameters parameters = new OfficialRunParameters(null, null).validated();

        assertThat(parameters.getMinimumDepthM()).isEqualByComparingTo("0.7");
        assertThat(parameters.getMaximumDepthM()).isEqualByComparingTo("10.0");
        assertThat(parameters.isDepthEnabled()).isFalse();
        assertThat(parameters.getAlgorithmProfile()).isEqualTo(RoutingAlgorithmProfile.STABLE);
    }

    @Test
    void enablesOptionalDepthOnlyWhenExplicitlyRequested() {
        OfficialRunParameters parameters = new OfficialRunParameters(
                new BigDecimal("0.7"), new BigDecimal("10.0"), true).validated();

        assertThat(parameters.isDepthEnabled()).isTrue();
    }

    @Test
    void keepsTheLegacyProfileInsideImmutableParameters() {
        OfficialRunParameters parameters = new OfficialRunParameters(
                null,
                null,
                false,
                RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL).validated();

        assertThat(parameters.getAlgorithmProfile())
                .isEqualTo(RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL);
    }

    @Test
    void roundTripsTheLegacyProfileWithoutRewritingSavedParameters() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        OfficialRunParameters original = new OfficialRunParameters(
                null,
                null,
                false,
                RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL);

        String json = objectMapper.writeValueAsString(original);
        OfficialRunParameters restored = objectMapper.readValue(json, OfficialRunParameters.class);

        assertThat(json).contains("\"algorithm_profile\":\"expert_experimental\"");
        assertThat(restored.getAlgorithmProfile())
                .isEqualTo(RoutingAlgorithmProfile.EXPERT_EXPERIMENTAL);
    }

    @Test
    void readsOlderParametersWithoutAProfileAsPrimary() throws Exception {
        OfficialRunParameters restored = new ObjectMapper().readValue(
                "{\"minimum_depth_m\":0.7,\"maximum_depth_m\":10.0}",
                OfficialRunParameters.class);

        assertThat(restored.getAlgorithmProfile()).isEqualTo(RoutingAlgorithmProfile.STABLE);
    }

    @Test
    void stillRejectsUnknownProfilesInsteadOfSilentlySelectingPrimary() {
        assertThatThrownBy(() -> RoutingAlgorithmProfile.fromWireName("unknown_algorithm"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown algorithm_profile");
    }

    @Test
    void acceptsAnExplicitImmutableDepthRange() {
        OfficialRunParameters parameters = new OfficialRunParameters(
                new BigDecimal("1.0"), new BigDecimal("7.5")).validated();

        assertThat(parameters.getMinimumDepthM()).isEqualByComparingTo("1.0");
        assertThat(parameters.getMaximumDepthM()).isEqualByComparingTo("7.5");
    }

    @Test
    void rejectsRangesThatExcludeThePublishedOrdinaryDepth() {
        assertThatThrownBy(() -> new OfficialRunParameters(
                new BigDecimal("3.5"), new BigDecimal("8")).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ordinary 3.0");
        assertThatThrownBy(() -> new OfficialRunParameters(
                new BigDecimal("0.7"), new BigDecimal("2.5")).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ordinary 3.0");
    }

    @Test
    void rejectsDepthBelowThePublishedMinimum() {
        assertThatThrownBy(() -> new OfficialRunParameters(
                new BigDecimal("0.6"), new BigDecimal("10")).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 0.7");
    }

    @Test
    void rejectsAnUnboundedSearchRange() {
        assertThatThrownBy(() -> new OfficialRunParameters(
                new BigDecimal("0.7"), new BigDecimal("50.5")).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed 50.0");
    }
}
