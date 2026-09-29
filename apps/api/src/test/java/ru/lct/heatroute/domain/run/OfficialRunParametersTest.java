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
    }

    @Test
    void enablesOptionalDepthOnlyWhenExplicitlyRequested() {
        OfficialRunParameters parameters = new OfficialRunParameters(
                new BigDecimal("0.7"), new BigDecimal("10.0"), true).validated();

        assertThat(parameters.isDepthEnabled()).isTrue();
    }

    @Test
    void roundTripsCalculationParameters() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        OfficialRunParameters original = new OfficialRunParameters(
                null,
                null,
                true);

        String json = objectMapper.writeValueAsString(original);
        OfficialRunParameters restored = objectMapper.readValue(json, OfficialRunParameters.class);

        assertThat(objectMapper.readTree(json).size()).isEqualTo(3);
        assertThat(restored.isDepthEnabled()).isTrue();
    }

    @Test
    void ignoresUnknownFieldsInStoredParameters() throws Exception {
        OfficialRunParameters restored = new ObjectMapper().readValue(
                "{\"minimum_depth_m\":0.7,\"maximum_depth_m\":10.0,"
                        + "\"removed_field\":\"outdated_value\"}",
                OfficialRunParameters.class);

        assertThat(restored.getMinimumDepthM()).isEqualByComparingTo("0.7");
        assertThat(restored.isDepthEnabled()).isFalse();
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
