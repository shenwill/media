package androidx.media3.common.util;

public class DirectMappedIntCache {
    private final int mask;
    private final int[] keys;
    private final int[] values;
    private final int emptySentinel;

    public DirectMappedIntCache(int capacityPowerOfTwo, int emptySentinel) {
        this.mask = capacityPowerOfTwo - 1; // e.g., capacity 1024 -> mask 1023
        this.keys = new int[capacityPowerOfTwo];
        this.values = new int[capacityPowerOfTwo];
        this.emptySentinel = emptySentinel;
        java.util.Arrays.fill(keys, emptySentinel);
    }

    public int get(int key) {
        int index = hash(key) & mask;
        return keys[index] == key ? values[index] : emptySentinel;
    }

    public void put(int key, int value) {
        int index = hash(key) & mask;
        keys[index] = key; // Directly overwrites collision slot
        values[index] = value;
    }

    public void reset() {
        java.util.Arrays.fill(keys, emptySentinel);
    }

    private static int hash(int key) {
        // Simple hash spreader to reduce sequential collision clusters
        key ^= key >>> 16;
        key *= 0x85ebca6b;
        return key ^ (key >>> 13);
    }
}