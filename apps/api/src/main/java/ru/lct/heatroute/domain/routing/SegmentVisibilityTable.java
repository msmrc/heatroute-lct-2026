package ru.lct.heatroute.domain.routing;

/**
 * Точная таблица видимости пар: соседние ID второго узла лежат в одной странице.
 * Страницы ограничены по памяти; разреженный хвост сохраняет прежнюю hash-таблицу.
 * Ноль означает отсутствие ответа. Живёт в однопоточном окружении одного расчёта.
 */
final class SegmentVisibilityTable {
    private static final int PAGE_SHIFT = 6;
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int MAX_PAGES = 32_768;
    private final int maxPages;
    private long[] pageKeys = new long[128];
    private byte[][] pages = new byte[128][];
    private int pageCount;
    private int size;
    private long lastPageKey;
    private byte[] lastPage;
    private SparseTable sparse;

    SegmentVisibilityTable() { this(MAX_PAGES); }

    SegmentVisibilityTable(int maxPages) {
        if (maxPages < 0 || maxPages > MAX_PAGES) {
            throw new IllegalArgumentException("Invalid visibility page budget");
        }
        this.maxPages = maxPages;
    }

    int size() { return size; }

    byte get(long key) {
        if (pages == null) return sparse.get(key);
        long pageKey = key >>> PAGE_SHIFT;
        byte[] page = page(pageKey);
        return page != null ? page[(int) key & (PAGE_SIZE - 1)]
                : sparse == null ? 0 : sparse.get(key);
    }

    void put(long key, byte value) {
        if (value == 0) throw new IllegalArgumentException("Zero is reserved for unknown visibility");
        if (pages == null) {
            int before = sparse.size;
            sparse.put(key, value);
            size += sparse.size - before;
            return;
        }
        long pageKey = key >>> PAGE_SHIFT;
        byte[] page = page(pageKey);
        if (page == null && pageCount < maxPages) {
            if ((pageCount + 1) * 5 > pages.length * 3) growPages();
            int slot = pageSlot(pageKey);
            pageKeys[slot] = pageKey;
            page = new byte[PAGE_SIZE];
            pages[slot] = page;
            pageCount++;
            lastPageKey = pageKey;
            lastPage = page;
        }
        if (page != null) {
            int offset = (int) key & (PAGE_SIZE - 1);
            if (page[offset] == 0) size++;
            page[offset] = value;
        } else {
            if (sparse == null) sparse = new SparseTable();
            int before = sparse.size;
            sparse.put(key, value);
            size += sparse.size - before;
            collapseSparsePages();
        }
    }

    /** На разреженных ID возвращаемся к одному lookup вместо постоянного двойного промаха. */
    private void collapseSparsePages() {
        if (sparse.size <= maxPages || (pageCount > 0 && size - sparse.size >= 4L * pageCount)) return;
        for (int slot = 0; slot < pages.length; slot++) {
            byte[] page = pages[slot];
            if (page == null) continue;
            long prefix = pageKeys[slot] << PAGE_SHIFT;
            for (int offset = 0; offset < PAGE_SIZE; offset++) {
                if (page[offset] != 0) sparse.put(prefix | offset, page[offset]);
            }
        }
        pageKeys = null;
        pages = null;
        lastPage = null;
    }

    private byte[] page(long pageKey) {
        if (lastPage != null && pageKey == lastPageKey) return lastPage;
        byte[] page = pages[pageSlot(pageKey)];
        lastPageKey = pageKey;
        lastPage = page;
        return page;
    }

    private int pageSlot(long key) {
        int slot = index(key, pages.length);
        while (pages[slot] != null && pageKeys[slot] != key) slot = (slot + 1) & (pages.length - 1);
        return slot;
    }

    private void growPages() {
        long[] previousKeys = pageKeys;
        byte[][] previousPages = pages;
        pageKeys = new long[previousKeys.length * 2];
        pages = new byte[previousPages.length * 2][];
        for (int i = 0; i < previousPages.length; i++) {
            if (previousPages[i] == null) continue;
            int slot = pageSlot(previousKeys[i]);
            pageKeys[slot] = previousKeys[i];
            pages[slot] = previousPages[i];
        }
    }

    private static int index(long key, int capacity) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdl;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53l;
        key ^= key >>> 33;
        return (int) key & (capacity - 1);
    }

    /** Прежний exact-key fallback; предел числа сегментов задаёт владелец memo. */
    private static final class SparseTable {
        private long[] keys = new long[1024];
        private byte[] values = new byte[1024];
        private int size;

        private byte get(long key) {
            int slot = index(key, keys.length);
            while (values[slot] != 0) {
                if (keys[slot] == key) return values[slot];
                slot = (slot + 1) & (keys.length - 1);
            }
            return 0;
        }

        private void put(long key, byte value) {
            if ((size + 1) > keys.length * 0.6) grow();
            int slot = index(key, keys.length);
            while (values[slot] != 0) {
                if (keys[slot] == key) { values[slot] = value; return; }
                slot = (slot + 1) & (keys.length - 1);
            }
            keys[slot] = key;
            values[slot] = value;
            size++;
        }

        private void grow() {
            long[] previousKeys = keys;
            byte[] previousValues = values;
            keys = new long[previousKeys.length * 2];
            values = new byte[previousValues.length * 2];
            size = 0;
            for (int i = 0; i < previousKeys.length; i++) {
                if (previousValues[i] != 0) put(previousKeys[i], previousValues[i]);
            }
        }
    }
}
