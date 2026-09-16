package ru.lct.heatroute.domain.engineering;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class OfficialPipeCatalog {
    private static final List<PipeCatalogEntry> ENTRIES = List.of(
            entry(50, "3.5", 181, 74023, 96180, ".125", ".150", ".400", ".125"),
            entry(65, "8.3", 245, 78631, 109989, ".140", ".150", ".430", ".140"),
            entry(80, "13.2", 327, 83530, 117582, ".160", ".150", ".470", ".160"),
            entry(100, "22.3", 419, 89748, 133694, ".180", ".150", ".510", ".180"),
            entry(125, "40.2", 554, 97275, 148030, ".225", ".150", ".600", ".225"),
            entry(150, "65.1", 696, 105507, 152295, ".250", ".150", ".650", ".250"),
            entry(200, "152.3", 1042, 120275, 181766, ".315", ".250", ".880", ".315"),
            entry(250, "274.9", 1379, 135323, 202030, ".400", ".250", "1.050", ".400"),
            entry(300, "437.4", 1718, 150022, 228707, ".450", ".250", "1.150", ".450"),
            entry(400, "943.1", 2477, 190299, 271317, ".560", ".250", "1.370", ".560"),
            entry(500, "1663.4", 3245, 224137, 333884, ".710", ".250", "1.670", ".710"),
            entry(600, "2627.7", 4037, 264790, 372703, ".800", ".250", "1.850", ".800"),
            entry(700, "3735.1", 4775, 324298, 439571, ".900", ".250", "2.050", ".900"),
            entry(800, "5296.8", 5644, 325996, 489918, "1.000", ".250", "2.250", "1.000"),
            entry(900, "7165.0", 6518, 327693, 553607, "1.100", ".250", "2.450", "1.100"),
            entry(1000, "9391.8", 7419, 418777, 606679, "1.200", ".250", "2.650", "1.200"),
            entry(1200, "15012.8", 9288, 428074, 825692, "1.425", ".250", "3.100", "1.425"),
            entry(1400, "22501.9", 11276, 683417, 978584, "1.600", ".250", "3.450", "1.600"));

    public List<PipeCatalogEntry> entries() {
        return ENTRIES;
    }

    public Optional<PipeCatalogEntry> byDiameter(int diameter) {
        return ENTRIES.stream().filter(entry -> entry.getDiameter() == diameter).findFirst();
    }

    public Optional<PipeCatalogEntry> minimumForFlow(BigDecimal flowTph) {
        if (flowTph == null || flowTph.signum() <= 0) {
            throw new IllegalArgumentException("flow_tph must be positive");
        }
        return ENTRIES.stream()
                .filter(entry -> entry.getMaxFlowTph().compareTo(flowTph) >= 0)
                .findFirst();
    }

    private static PipeCatalogEntry entry(
            int diameter,
            String maxFlow,
            int maxLength,
            int newRate,
            int reconstructionRate,
            String outerDiameter,
            String shellGap,
            String pairWidth,
            String height) {
        return new PipeCatalogEntry(
                diameter,
                maxFlow,
                maxLength,
                newRate,
                reconstructionRate,
                outerDiameter,
                shellGap,
                pairWidth,
                height);
    }
}
