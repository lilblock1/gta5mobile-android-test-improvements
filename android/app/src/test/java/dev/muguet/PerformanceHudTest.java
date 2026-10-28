package dev.muguet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PerformanceHudTest {
    @Test public void formatsCompactRealtimeStats() {
        long[] stats = {600, 1341L * 1048576L, 110669, 34604, 3, 450};
        String text = PerformanceHud.format(59.7f, stats, 15, 1.0);
        assertTrue(text.contains("60 FPS"));
        assertTrue(text.contains("16.8 ms"));
        assertTrue(text.contains("GPU buf 1341 MB"));
        assertTrue(text.contains("B 111k"));
        assertTrue(text.contains("T 35k"));
        assertTrue(text.contains("Shader 3"));
        assertTrue(text.contains("Skip 15/s"));
    }

    @Test public void showsAnUnknownFrameTimeWhenNoFramesPresent() {
        long[] stats = {0, 0, 0, 0, 0, 0};
        String text = PerformanceHud.format(0, stats, 0, 1);
        assertTrue(text.contains("0 FPS"));
        assertTrue(text.contains("-- ms"));
        assertFalse(text.contains("Infinity"));
        assertFalse(text.contains("NaN"));
    }

    @Test public void rejectsMissingMetricsGracefully() {
        assertEquals("25 FPS", PerformanceHud.format(24.8f, null, 0, 1));
        assertEquals("25 FPS", PerformanceHud.format(24.8f, new long[]{1}, 0, 1));
    }

    @Test public void neverDisplaysNegativeSkippedDraws() {
        String text = PerformanceHud.format(30, new long[]{10, 0, 2, 3, 0, 1}, -10, 0);
        assertTrue(text.contains("Skip 0/s"));
    }
}
