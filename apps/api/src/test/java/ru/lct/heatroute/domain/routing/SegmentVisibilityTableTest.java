package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Страничное хранение не меняет ответы, направление пар и unknown при любых границах. */
class SegmentVisibilityTableTest {
    @Test
    void exactKeysAndUpdatesMatchIndependentMapAcrossPagesAndSparseFallback() {
        for (int budget : new int[] {0, 1, 127, 32768}) {
            SegmentVisibilityTable table = new SegmentVisibilityTable(budget);
            Map<Long, Byte> expected = new HashMap<>();
            Random random = new Random(103);
            for (int i = 0; i < 80000; i++) {
                long key = i % 3 == 0 ? random.nextLong()
                        : ((long) random.nextInt(80) << 32) | random.nextInt(1024);
                assertThat(table.get(key)).isEqualTo(expected.getOrDefault(key, (byte) 0));
                byte value = (byte) (i % 2 + 1);
                table.put(key, value);
                expected.put(key, value);
                assertThat(table.get(key)).isEqualTo(value);
                if (i % 19 == 0) {
                    table.put(key, (byte) (3 - value));
                    expected.put(key, (byte) (3 - value));
                }
            }
            assertThat(table.size()).isEqualTo(expected.size());
            expected.forEach((key, value) -> assertThat(table.get(key)).isEqualTo(value));
        }
    }

    @Test
    void zeroSignedExtremesAdjacentPagesAndReversePairsRemainDistinct() {
        SegmentVisibilityTable table = new SegmentVisibilityTable(2);
        long[] keys = {0, 1, 63, 64, 127, 128, Long.MIN_VALUE, Long.MAX_VALUE, -1,
                (7L << 32) | 33, (33L << 32) | 7};
        for (int i = 0; i < keys.length; i++) {
            assertThat(table.get(keys[i])).isZero();
            table.put(keys[i], (byte) (i % 2 + 1));
        }
        for (int i = 0; i < keys.length; i++) assertThat(table.get(keys[i])).isEqualTo((byte) (i % 2 + 1));
        assertThat(table.get(62)).isZero();
        assertThat(table.get(65)).isZero();
        assertThat(table.size()).isEqualTo(keys.length);
        assertThatThrownBy(() -> table.put(0, (byte) 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(table.size()).isEqualTo(keys.length);
    }

    @Test
    void denseVisibilityGridSurvivesGrowthAndReplacementWithoutLosingEntries() {
        SegmentVisibilityTable table = new SegmentVisibilityTable();
        for (int first = 0; first < 1000; first++) {
            for (int second = 0; second < 1000; second++) {
                table.put(((long) first << 32) | second, (byte) ((first + second) % 2 + 1));
            }
        }
        assertThat(table.size()).isEqualTo(1000000);
        for (int first = 999; first >= 0; first--) {
            for (int second = 999; second >= 0; second--) {
                assertThat(table.get(((long) first << 32) | second))
                        .isEqualTo((byte) ((first + second) % 2 + 1));
            }
        }
    }
}
