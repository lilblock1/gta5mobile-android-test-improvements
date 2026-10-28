package dev.muguet;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ThermalStatusTest {
    // PowerManager thermal status values, stable since Android API 29.
    @Test public void mapsAllAndroidThermalSeverityLevels() {
        assertEquals("Normal", ThermalStatus.label(0));
        assertEquals("Light", ThermalStatus.label(1));
        assertEquals("Moderate", ThermalStatus.label(2));
        assertEquals("Severe", ThermalStatus.label(3));
        assertEquals("Critical", ThermalStatus.label(4));
        assertEquals("Emergency", ThermalStatus.label(5));
        assertEquals("Shutdown", ThermalStatus.label(6));
    }

    @Test public void unknownStatusIsNotMisreportedAsNormal() {
        assertEquals("Unknown", ThermalStatus.label(-1));
        assertEquals("Unknown", ThermalStatus.label(99));
    }
}
