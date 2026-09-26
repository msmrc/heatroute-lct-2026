package ru.lct.heatroute.domain.sizing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import ru.lct.heatroute.domain.engineering.OfficialPipeCatalog;

/** Сверяет реальный solver с прямым перечислением допустимых назначений таблицы 1. */
class OfficialNetworkSizingOracleTest {
    private static final int[] DIAMETERS = {
        50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400
    };
    private static final BigDecimal[] CAPACITIES = decimals(
            "3.5", "8.3", "13.2", "22.3", "40.2", "65.1", "152.3", "274.9", "437.4",
            "943.1", "1663.4", "2627.7", "3735.1", "5296.8", "7165.0", "9391.8", "15012.8", "22501.9");
    private static final BigDecimal[] LIMITS = decimals(
            "181", "245", "327", "419", "554", "696", "1042", "1379", "1718",
            "2477", "3245", "4037", "4775", "5644", "6518", "7419", "9288", "11276");

    @Test
    void actualAssignmentMatchesLiteralEighteenDiameterEnumeration() {
        Random random = new Random(0x5eed);
        OfficialNetworkSizer sizer = new OfficialNetworkSizer(new OfficialPipeCatalog());
        BigDecimal[] lengths = decimals("1", "90", "181", "181.001", "245", "245.001", "327", "327.001", "11999");
        BigDecimal[] demands = decimals("0", "1.75", "3.5", "8.3", "13.2", "943.1", "15012.8", "22501.9");
        for (int sample = 0; sample < 256; sample++) {
            int count = 1 + sample % 4;
            int[] parents = new int[count];
            BigDecimal[] edgeLengths = new BigDecimal[count];
            BigDecimal[] flows = new BigDecimal[count];
            Map<String, BigDecimal> demandMap = new HashMap<>();
            List<NetworkTreeEdge> edges = new ArrayList<>();
            for (int edge = 0; edge < count; edge++) {
                parents[edge] = edge == 0 ? -1 : random.nextInt(edge);
                edgeLengths[edge] = lengths[random.nextInt(lengths.length)];
                flows[edge] = demands[random.nextInt(demands.length)];
                demandMap.put("n" + edge, flows[edge]);
                edges.add(new NetworkTreeEdge("e" + edge,
                        parents[edge] < 0 ? "root" : "n" + parents[edge], "n" + edge, edgeLengths[edge]));
            }
            for (int edge = count - 1; edge > 0; edge--) {
                flows[parents[edge]] = flows[parents[edge]].add(flows[edge]);
            }
            Enumeration oracle = new Enumeration(parents, edgeLengths, flows);
            oracle.enumerate(0);
            Collections.shuffle(edges, random);
            NetworkSizingResult actual = sizer.size(edges, demandMap);
            assertThat(actual.isValid()).as("sample %s validity", sample).isEqualTo(oracle.feasible > 0);
            if (oracle.feasible > 0) {
                for (int edge = 0; edge < count; edge++) {
                    assertThat(actual.getEdges().get("e" + edge).getDiameter())
                            .as("sample %s edge %s", sample, edge).isEqualTo(DIAMETERS[oracle.least[edge]]);
                }
            }
        }
    }

    private static BigDecimal[] decimals(String... values) {
        return Arrays.stream(values).map(BigDecimal::new).toArray(BigDecimal[]::new);
    }

    /** Проверка определений идёт от корня; здесь нет components и bottom-up suffix модели solver. */
    private static final class Enumeration {
        private final int[] parents;
        private final BigDecimal[] lengths;
        private final BigDecimal[] flows;
        private final int[] assigned;
        private final int[] least;
        private final BigDecimal[] prefix;
        private int feasible;

        private Enumeration(int[] parents, BigDecimal[] lengths, BigDecimal[] flows) {
            this.parents = parents;
            this.lengths = lengths;
            this.flows = flows;
            assigned = new int[parents.length];
            least = new int[parents.length];
            Arrays.fill(least, DIAMETERS.length);
            prefix = new BigDecimal[parents.length];
        }

        private void enumerate(int edge) {
            if (edge == parents.length) {
                feasible++;
                for (int index = 0; index < least.length; index++) {
                    least[index] = Math.min(least[index], assigned[index]);
                }
                return;
            }
            int parent = parents[edge];
            for (int diameter = 0; diameter < DIAMETERS.length; diameter++) {
                if (CAPACITIES[diameter].compareTo(flows[edge]) < 0) {
                    continue;
                }
                if (parent >= 0 && (assigned[parent] < diameter
                        || (flows[parent].compareTo(flows[edge]) == 0 && assigned[parent] != diameter))) {
                    continue;
                }
                BigDecimal continuous = lengths[edge];
                if (parent >= 0 && assigned[parent] == diameter) {
                    continuous = continuous.add(prefix[parent]);
                }
                if (continuous.compareTo(LIMITS[diameter]) > 0) {
                    continue;
                }
                assigned[edge] = diameter;
                prefix[edge] = continuous;
                enumerate(edge + 1);
            }
        }
    }
}
