package dev.muguet;

import static org.junit.Assert.*;
import org.junit.Test;

public final class TouchLookPolicyTest {
    @Test public void centeredTouchAndSmallUnintendedMovementsStayNeutral() {
        assertEquals(0f, TouchLookPolicy.deflection(0, 0, 12), 0.0001f);
        assertEquals(0f, TouchLookPolicy.deflection(3, 4, 12), 0.0001f);
        assertEquals(0f, TouchLookPolicy.deflection(Float.NaN, 40, 2), 0.0001f);
    }

    @Test public void sensitivityControlsActualAnalogDeflection() {
        float low = TouchLookPolicy.deflection(25, 0, 0.25f);
        float normal = TouchLookPolicy.deflection(25, 0, 1f);
        float high = TouchLookPolicy.deflection(25, 0, 4f);
        float maximum = TouchLookPolicy.deflection(25, 0, 12f);
        assertTrue(low > 0f);
        assertTrue(normal > low);
        assertTrue(high > normal);
        assertEquals(1f, maximum, 0.0001f);
        assertEquals(1f, TouchLookPolicy.deflection(80, 0, 1f), 0.0001f);
    }

    @Test public void radialDeflectionPreservesDiagonalsWithoutOverRange() {
        assertEquals(TouchLookPolicy.deflection(30, 40, 1.5f),
            TouchLookPolicy.deflection(-30, -40, 1.5f), 0.0001f);
        assertEquals(1f, TouchLookPolicy.deflection(1000, 1000, 12f), 0.0001f);
        assertTrue(TouchLookPolicy.deflection(12, 12, 1f) <= 1f);
    }
}
