package ru.lct.heatroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Dense-array oracle for the bounded, per-search visibility value store. */
class PagedVisibilityValuesTest {

    @Test
    void matchesDenseArrayAtEveryStorageBoundaryAndAcrossPages() {
        for (int length : new int[] {0, 1, 4095, 4096, 4097, 8192, 8193}) {
            PagedVisibilityValues actual = new PagedVisibilityValues(length);
            byte[] expected = new byte[length];
            assertStorageShape(actual, length);
            for (int index : boundaryIndexes(length)) {
                assertThat(actual.get(index)).as("initial length=%s index=%s", length, index).isZero();
                for (byte value : new byte[] {1, 2, 0, 2, 1, 0}) {
                    actual.put(index, value);
                    expected[index] = value;
                    assertThat(actual.get(index)).as("length=%s index=%s value=%s", length, index, value)
                            .isEqualTo(expected[index]);
                }
            }
        }
    }

    @Test
    void sparseAndDenseSeededOperationsMatchDenseOracleWithoutReadAllocation() {
        for (int length : new int[] {1, 4095, 4096, 4097, 8192, 8193}) {
            PagedVisibilityValues actual = new PagedVisibilityValues(length);
            byte[] expected = new byte[length];
            Random random = new Random(0x50414745444cL + length);
            for (int operation = 0; operation < 1536; operation++) {
                int index = random.nextInt(length);
                if ((operation & 3) != 0) {
                    byte value = (byte) random.nextInt(3);
                    actual.put(index, value);
                    expected[index] = value;
                }
                assertThat(actual.get(index)).as("length=%s operation=%s", length, operation)
                        .isEqualTo(expected[index]);
            }
            for (int index = 0; index < length; index++) {
                assertThat(actual.get(index)).as("final length=%s index=%s", length, index)
                        .isEqualTo(expected[index]);
            }
        }
    }

    @Test
    void pagedBackingAllocatesOnlyNonzeroWrittenPagesAndKeepsShortTail() {
        PagedVisibilityValues values = new PagedVisibilityValues(8193);
        assertThat(ReflectionTestUtils.getField(values, "dense")).isNull();
        byte[][] pages = (byte[][]) ReflectionTestUtils.getField(values, "pages");
        assertThat((Object[]) pages).hasSize(3).containsOnlyNulls();

        assertThat(values.get(0)).isZero();
        assertThat(values.get(4096)).isZero();
        values.put(4095, (byte) 0);
        values.put(8192, (byte) 0);
        assertThat((Object[]) pages).containsOnlyNulls();

        values.put(4096, (byte) 1);
        assertThat(pages[0]).isNull();
        assertThat(pages[1]).hasSize(PagedVisibilityValues.PAGE_SIZE);
        assertThat(pages[2]).isNull();
        values.put(8192, (byte) 2);
        assertThat(pages[2]).hasSize(1);
        assertThat(values.get(4096)).isEqualTo((byte) 1);
        assertThat(values.get(8192)).isEqualTo((byte) 2);
    }

    @Test
    void invalidIndexesFailLikeAnOrdinaryByteArrayIncludingTheShortTail() {
        for (int length : new int[] {0, 1, 4096, 4097, 8193}) {
            PagedVisibilityValues values = new PagedVisibilityValues(length);
            for (int invalid : new int[] {-1, length, length + 1, Integer.MAX_VALUE}) {
                assertThatThrownBy(() -> values.get(invalid)).as("get length=%s index=%s", length, invalid)
                        .isInstanceOf(ArrayIndexOutOfBoundsException.class);
                assertThatThrownBy(() -> values.put(invalid, (byte) 1))
                        .as("put length=%s index=%s", length, invalid)
                        .isInstanceOf(ArrayIndexOutOfBoundsException.class);
            }
        }
    }

    @Test
    void independentStoresKeepForwardAndReverseValuesSeparate() {
        PagedVisibilityValues forward = new PagedVisibilityValues(8193);
        PagedVisibilityValues reverse = new PagedVisibilityValues(8193);

        forward.put(4096, (byte) 1);
        reverse.put(4096, (byte) 2);
        forward.put(8192, (byte) 2);

        assertThat(forward.get(4096)).isEqualTo((byte) 1);
        assertThat(forward.get(8192)).isEqualTo((byte) 2);
        assertThat(reverse.get(4096)).isEqualTo((byte) 2);
        assertThat(reverse.get(8192)).isZero();
        assertThat(ReflectionTestUtils.getField(forward, "pages"))
                .isNotSameAs(ReflectionTestUtils.getField(reverse, "pages"));
    }

    private static void assertStorageShape(PagedVisibilityValues values, int length) {
        Object dense = ReflectionTestUtils.getField(values, "dense");
        Object pages = ReflectionTestUtils.getField(values, "pages");
        if (length <= PagedVisibilityValues.PAGE_SIZE) {
            assertThat((byte[]) dense).hasSize(length);
            assertThat(pages).isNull();
        } else {
            assertThat(dense).isNull();
            assertThat((Object[]) pages).hasSize((length + PagedVisibilityValues.PAGE_SIZE - 1)
                    / PagedVisibilityValues.PAGE_SIZE).containsOnlyNulls();
        }
    }

    private static int[] boundaryIndexes(int length) {
        if (length == 0) return new int[0];
        java.util.LinkedHashSet<Integer> result = new java.util.LinkedHashSet<>();
        for (int index : new int[] {0, 1, 4094, 4095, 4096, 4097, 8191, 8192, length - 1}) {
            if (index >= 0 && index < length) result.add(index);
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }
}
