package ru.lct.heatroute.domain.constraints;

import java.math.BigDecimal;
import java.util.Objects;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/**
 * Переводит просвет из §§3.1/4 технического приложения в расстояние от оси новой пары труб.
 * Применяется при прохождении рядом с ограничением; исключения ввода и специального прохода
 * определяет вызывающий код.
 */
public final class OfficialAxisClearance {
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal GAS_HALF_WIDTH_M = new BigDecimal("0.20");
    private static final BigDecimal CABLE_HALF_WIDTH_M = new BigDecimal("0.10");

    private final OfficialPipeCatalog pipeCatalog;
    private final OfficialConstraintCatalog constraintCatalog;

    public OfficialAxisClearance(
            OfficialPipeCatalog pipeCatalog,
            OfficialConstraintCatalog constraintCatalog) {
        this.pipeCatalog = Objects.requireNonNull(pipeCatalog, "pipeCatalog");
        this.constraintCatalog = Objects.requireNonNull(constraintCatalog, "constraintCatalog");
    }

    /**
     * Возвращает метры до границы полигона либо оси линейного ограничения без округления.
     * Для heat_network нужен ДУ существующей пары; для остальных типов этот параметр не используется.
     * @throws IllegalArgumentException если тип неизвестен или необходимый ДУ отсутствует в каталоге
     */
    public BigDecimal axisClearanceM(
            String type, int newDiameter, Integer existingHeatNetworkDiameter) {
        SpatialConstraintRule rule = constraintCatalog.find(type)
                .orElseThrow(() -> new IllegalArgumentException("unknown restriction type: " + type));
        BigDecimal newHalfWidthM = halfPairWidthM(newDiameter, "new-network");
        BigDecimal clearanceM = "oks".equals(type)
                ? constraintCatalog.existingBuildingClearanceM(newDiameter)
                : rule.getHorizontalClearanceM();
        return clearanceM.add(newHalfWidthM)
                .add(restrictionHalfWidthM(type, existingHeatNetworkDiameter));
    }

    private BigDecimal restrictionHalfWidthM(String type, Integer existingHeatNetworkDiameter) {
        switch (type) {
            case "gas_pipeline":
                return GAS_HALF_WIDTH_M;
            case "power_cable":
                return CABLE_HALF_WIDTH_M;
            case "heat_network":
                if (existingHeatNetworkDiameter == null) {
                    throw new IllegalArgumentException("existing heat-network diameter is required");
                }
                return halfPairWidthM(existingHeatNetworkDiameter, "existing heat-network");
            default:
                return BigDecimal.ZERO;
        }
    }

    private BigDecimal halfPairWidthM(int diameter, String network) {
        return pipeCatalog.byDiameter(diameter)
                .orElseThrow(() -> new IllegalArgumentException(network + " diameter must be an official DU"))
                .getPairWidthM().divide(TWO);
    }
}
