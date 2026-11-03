package dev.muguet;

/** A ceiling for game memory, not a promise that Android can commit that much. */
final class HeapPolicy {
    private static final long GIB = 1024L * 1024 * 1024;
    private HeapPolicy() {}

    static int limit(int requestedGb, long totalMemoryBytes, boolean automatic) {
        int requested = Math.max(1, Math.min(8, requestedGb));
        if (!automatic || totalMemoryBytes < 3 * GIB) return requested;
        // Round marketed physical RAM as the reference profiler does. Reserve
        // space for Android, native compilation, GPU resources and other apps.
        long totalGb = Math.min(64, Math.round((double) totalMemoryBytes / GIB));
        // Pinned game.wasm imports 49,152 64 KiB pages: its initial heap is 3 GiB.
        int cap = (int) Math.max(3, totalGb - (totalGb <= 6 ? 2 : 3));
        return Math.min(requested, cap);
    }
}
