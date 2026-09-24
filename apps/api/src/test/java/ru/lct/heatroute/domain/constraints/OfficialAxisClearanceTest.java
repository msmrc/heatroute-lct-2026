package ru.lct.heatroute.domain.constraints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

class OfficialAxisClearanceTest {
    private static final String[] TYPES_WITHOUT_EXISTING_DIAMETER = {
        "oks", "park", "social_area", "prohibited_site", "water", "railway",
        "road", "tram_tracks", "gas_pipeline", "power_cable"
    };

    private final OfficialAxisClearance clearance = new OfficialAxisClearance(
            new OfficialPipeCatalog(), new OfficialConstraintCatalog());

    @ParameterizedTest
    @CsvSource({
        "50,   0.200, 5.200",
        "65,   0.215, 5.215",
        "80,   0.235, 5.235",
        "100,  0.255, 5.255",
        "125,  0.300, 5.300",
        "150,  0.325, 5.325",
        "200,  0.440, 5.440",
        "250,  0.525, 5.525",
        "300,  0.575, 5.575",
        "400,  0.685, 5.685",
        "500,  0.835, 7.835",
        "600,  0.925, 7.925",
        "700,  1.025, 8.025",
        "800,  1.125, 8.125",
        "900,  1.225, 10.225",
        "1000, 1.325, 10.325",
        "1200, 1.550, 10.550",
        "1400, 1.725, 10.725"
    })
    void appliesPublishedClearancesAndBothNetworkWidthsForEveryOfficialDiameter(
            int diameter, String halfWidthM, String buildingAxisClearanceM) {
        // Ожидания взяты из таблиц приложения, а не из проверяемых каталогов.
        BigDecimal halfWidth = new BigDecimal(halfWidthM);
        assertThat(clearance.axisClearanceM("oks", diameter, null))
                .isEqualByComparingTo(buildingAxisClearanceM);
        for (String type : new String[] {"park", "social_area", "prohibited_site", "water", "railway"}) {
            assertThat(clearance.axisClearanceM(type, diameter, null)).as(type + " DU " + diameter)
                    .isEqualByComparingTo(new BigDecimal("1.0").add(halfWidth));
        }
        for (String type : new String[] {"road", "tram_tracks"}) {
            assertThat(clearance.axisClearanceM(type, diameter, null)).as(type + " DU " + diameter)
                    .isEqualByComparingTo(new BigDecimal("1.5").add(halfWidth));
        }
        assertThat(clearance.axisClearanceM("gas_pipeline", diameter, null))
                .isEqualByComparingTo(new BigDecimal("2.20").add(halfWidth));
        assertThat(clearance.axisClearanceM("power_cable", diameter, null))
                .isEqualByComparingTo(new BigDecimal("2.10").add(halfWidth));
        assertThat(clearance.axisClearanceM("heat_network", diameter, 100))
                .isEqualByComparingTo(new BigDecimal("1.255").add(halfWidth));
        assertThat(clearance.axisClearanceM("heat_network", 100, diameter))
                .isEqualByComparingTo(new BigDecimal("1.255").add(halfWidth));
    }

    @Test
    void addsTheExistingPairEnvelopeWithoutApplyingBuildingDiameterThresholds() {
        assertThat(clearance.axisClearanceM("heat_network", 50, 1400))
                .isEqualByComparingTo("2.925");
        assertThat(clearance.axisClearanceM("heat_network", 1400, 50))
                .isEqualByComparingTo("2.925");
        assertThat(clearance.axisClearanceM("heat_network", 1400, 1400))
                .isEqualByComparingTo("4.450");
    }

    @Test
    void doesNotUseExistingNetworkDiameterForOtherRestrictionTypes() {
        for (String type : TYPES_WITHOUT_EXISTING_DIAMETER) {
            assertThat(clearance.axisClearanceM(type, 100, 1400)).as(type)
                    .isEqualByComparingTo(clearance.axisClearanceM(type, 100, null));
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "unknown", "Road", " road", "road ", "oks_existing", "heat_chamber"})
    void rejectsMissingAndUnknownRestrictionTypes(String type) {
        assertThatThrownBy(() -> clearance.axisClearanceM(type, 100, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restriction type");
    }

    @ParameterizedTest
    @ValueSource(ints = {
        Integer.MIN_VALUE, -1, 0, 49, 51, 499, 501, 799, 801, 899, 901, 1100, 1399, 1401, Integer.MAX_VALUE
    })
    void rejectsNonCatalogNewDiametersForEveryRestrictionType(int diameter) {
        for (String type : TYPES_WITHOUT_EXISTING_DIAMETER) {
            assertThatThrownBy(() -> clearance.axisClearanceM(type, diameter, null)).as(type)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("new-network diameter must be an official DU");
        }
        assertThatThrownBy(() -> clearance.axisClearanceM("heat_network", diameter, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("new-network diameter must be an official DU");
    }

    @Test
    void rejectsMissingExistingHeatNetworkDiameterWithoutAFallback() {
        assertThatThrownBy(() -> clearance.axisClearanceM("heat_network", 100, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing heat-network diameter is required");
    }

    @ParameterizedTest
    @ValueSource(ints = {
        Integer.MIN_VALUE, -1, 0, 49, 51, 499, 501, 799, 801, 899, 901, 1100, 1399, 1401, Integer.MAX_VALUE
    })
    void rejectsNonCatalogExistingHeatNetworkDiametersWithoutAFallback(int diameter) {
        assertThatThrownBy(() -> clearance.axisClearanceM("heat_network", 100, diameter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing heat-network diameter must be an official DU");
    }

    @Test
    void requiresBothCatalogs() {
        assertThatThrownBy(() -> new OfficialAxisClearance(null, new OfficialConstraintCatalog()))
                .isInstanceOf(NullPointerException.class).hasMessage("pipeCatalog");
        assertThatThrownBy(() -> new OfficialAxisClearance(new OfficialPipeCatalog(), null))
                .isInstanceOf(NullPointerException.class).hasMessage("constraintCatalog");
    }
}
