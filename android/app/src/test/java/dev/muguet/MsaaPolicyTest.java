package dev.muguet;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MsaaPolicyTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test public void existingAndUnknownSettingsKeepTwoSampleCap() {
        for (String mode : new String[]{null, "", "default", "typo"})
            assertEquals(2, MsaaPolicy.limit(mode, 0.5, 4 * GIB));
    }

    @Test public void adaptiveReducesSamplesForEitherCostConstraint() {
        assertEquals(1, MsaaPolicy.limit("adaptive", 0.7, 12 * GIB));
        assertEquals(1, MsaaPolicy.limit("adaptive", 1.0, 6 * GIB));
        assertEquals(2, MsaaPolicy.limit("adaptive", 0.701, 6 * GIB + 1));
        assertEquals(2, MsaaPolicy.limit("adaptive", 1.0, 12 * GIB));
    }

    @Test public void unavailableMeasurementsDoNotInventMemoryPressure() {
        assertEquals(2, MsaaPolicy.limit("adaptive", 1.0, 0));
        assertEquals(2, MsaaPolicy.limit("adaptive", Double.NaN, 0));
        assertEquals(2, MsaaPolicy.limit("adaptive", Double.POSITIVE_INFINITY, -1));
        assertEquals(2, MsaaPolicy.limit("adaptive", -1.0, 12 * GIB));
        assertEquals(1, MsaaPolicy.limit("adaptive", 0.5, 0));
        assertEquals(1, MsaaPolicy.limit("adaptive", Double.NaN, 4 * GIB));
    }

    @Test public void explicitOffAlwaysUsesOneSample() {
        assertEquals(1, MsaaPolicy.limit("off", 1.0, 16 * GIB));
    }
}
