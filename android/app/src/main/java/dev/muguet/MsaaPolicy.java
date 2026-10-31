package dev.muguet;

/** Sample-count limits for the existing Wasmtime renderer; no GPU resources are evicted. */
final class MsaaPolicy {
    private static final long SIX_GIB = 6L * 1024 * 1024 * 1024;

    private MsaaPolicy() {}

    static int limit(String mode, double scale, long totalMemoryBytes) {
        if ("off".equals(mode)) return 1;
        if (!"adaptive".equals(mode)) return 2;
        boolean lowScale = Double.isFinite(scale) && scale >= 0.25 && scale <= 0.7;
        boolean lowMemory = totalMemoryBytes > 0 && totalMemoryBytes <= SIX_GIB;
        return lowScale || lowMemory ? 1 : 2;
    }
}
