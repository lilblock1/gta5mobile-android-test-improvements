package dev.muguet;

import static org.junit.Assert.*;
import org.junit.Test;

public final class TouchLookPolicyTest {
    @Test public void touchpadUsesRelativeFingerMovementAndNotHeldStickPosition() {
        assertEquals(0f, TouchLookPolicy.relativePixels(0, 12), 0.0001f);
        assertEquals(2.5f, TouchLookPolicy.relativePixels(1, 1), 0.0001f);
        assertEquals(-2.5f, TouchLookPolicy.relativePixels(-1, 1), 0.0001f);
        assertEquals(10f, TouchLookPolicy.relativePixels(1, 4), 0.0001f);
        assertEquals(30f, TouchLookPolicy.relativePixels(1, 12), 0.0001f);
        assertEquals(0.625f, TouchLookPolicy.relativePixels(1, 0.25f), 0.0001f);
        assertEquals(720f, TouchLookPolicy.relativePixels(80, 12), 0.0001f);
        assertEquals(-720f, TouchLookPolicy.relativePixels(-80, 12), 0.0001f);
    }

    @Test public void invalidTouchpadDeltasAndSettingsCannotCauseUncontrolledCameraMotion() {
        assertEquals(0f, TouchLookPolicy.relativePixels(Float.NaN, 4), 0.0001f);
        assertEquals(0f, TouchLookPolicy.relativePixels(Float.POSITIVE_INFINITY, 4), 0.0001f);
        assertEquals(2.5f, TouchLookPolicy.relativePixels(1, Float.NaN), 0.0001f);
        assertEquals(720f, TouchLookPolicy.relativePixels(5000, 5000), 0.0001f);
    }

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
