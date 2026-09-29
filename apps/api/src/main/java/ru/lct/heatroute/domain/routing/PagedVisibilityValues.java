package ru.lct.heatroute.domain.routing;

/**
 * Значения локальной видимости: большие таблицы выделяют только затронутые страницы.
 * Ноль означает неизвестное ребро; таблица принадлежит одному однопоточному поиску без вытеснения.
 */
final class PagedVisibilityValues {
    private static final int PAGE_SHIFT = 12;
    static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int PAGE_MASK = PAGE_SIZE - 1;
    private final int length;
    private final byte[] dense;
    private final byte[][] pages;

    PagedVisibilityValues(int length) {
        this.length = length;
        // Малые графы сохраняют обычный массив, без дополнительной индирекции страниц.
        this.dense = length <= PAGE_SIZE ? new byte[length] : null;
        this.pages = dense == null ? new byte[((length - 1) >>> PAGE_SHIFT) + 1][] : null;
    }

    byte get(int index) {
        if (dense != null) return dense[index];
        if (index < 0 || index >= length) throw new ArrayIndexOutOfBoundsException(index);
        byte[] page = pages[index >>> PAGE_SHIFT];
        return page == null ? 0 : page[index & PAGE_MASK];
    }

    void put(int index, byte value) {
        if (dense != null) {
            dense[index] = value;
            return;
        }
        if (index < 0 || index >= length) throw new ArrayIndexOutOfBoundsException(index);
        int pageIndex = index >>> PAGE_SHIFT;
        byte[] page = pages[pageIndex];
        if (page == null) {
            if (value == 0) return;
            page = new byte[Math.min(PAGE_SIZE, length - (pageIndex << PAGE_SHIFT))];
            pages[pageIndex] = page;
        }
        page[index & PAGE_MASK] = value;
    }
}
