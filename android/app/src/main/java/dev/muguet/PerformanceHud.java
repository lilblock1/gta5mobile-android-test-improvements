package dev.muguet;

import java.util.Locale;

// Only formats a 1 Hz, precomputed native snapshot. No process scans, GPU
// queries, or metrics allocations occur in the normal 60 Hz input path.
final class PerformanceHud {
    static final int STATS_COUNT = 6;
    private static final long MIB = 1024L * 1024L;

    private PerformanceHud() {}

    static String format(float fps, long[] data, long skippedInWindow, double seconds) {
        return format(fps, data, skippedInWindow, seconds, "Unknown");
    }

    static String format(float fps, long[] data, long skippedInWindow, double seconds, String thermal) {
        String thermalLabel = thermal == null || thermal.isEmpty() ? "Unknown" : thermal;
        if (data == null || data.length < STATS_COUNT) {
            return String.format(Locale.ROOT, "%.0f FPS  |  Thermal %s", Math.max(0f, fps), thermalLabel);
        }
        // Frame time is the 1-second average implied by presented FPS; it is
        // NOT a GPU timer query or a 1% low measurement.
        String frameMs = fps > 0 ? String.format(Locale.ROOT, "%.1f", 1000f / fps) : "--";
        long mb = data[1] / MIB; // live engine-owned GPU buffer bytes; NOT total VRAM
        double skipsPerSecond = seconds > 0 ? Math.max(0, skippedInWindow) / seconds : 0;
        return String.format(Locale.ROOT,
            "%.0f FPS  |  Thermal %s  |  %s ms  |  GPU buf %d MB\nB %s  T %s  |  Shader %d  |  Skip %.0f/s",
            Math.max(0f, fps), thermalLabel, frameMs, mb, compact(data[2]), compact(data[3]),
            Math.max(0, data[4]), skipsPerSecond);
    }

    private static String compact(long value) {
        value = Math.max(0, value);
        if (value >= 10_000) return String.format(Locale.ROOT, "%.0fk", value / 1000.0);
        return Long.toString(value);
    }
}
