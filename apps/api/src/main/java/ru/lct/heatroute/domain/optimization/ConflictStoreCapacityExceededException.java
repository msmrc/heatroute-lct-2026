package ru.lct.heatroute.domain.optimization;

/** Resource-limit outcome: retaining more proof cuts requires refinement/compaction, not weaker rules. */
public final class ConflictStoreCapacityExceededException extends IllegalStateException {
    private final int capacity;

    public ConflictStoreCapacityExceededException(int capacity) {
        super("Conflict store capacity exceeded: " + capacity);
        this.capacity = capacity;
    }

    public int getCapacity() { return capacity; }
}
