package dev.muguet;

/**
 * Touch drag -> virtual gamepad right-stick deflection.
 *
 * Displacement is measured from the finger-down anchor in density-independent
 * pixels. The result stays held for as long as the finger is held: it is not
 * a mouse delta or a transient event. The existing sensitivity slider controls
 * how far the finger must move to achieve a given right-stick deflection.
 */
final class TouchLookPolicy {
    static final float DEADZONE_DP = 5f;
    static final float FULL_STICK_DP = 80f;

    private TouchLookPolicy() {}

    static float deflection(float dx, float dy, float sensitivity) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy)) return 0f;
        float distance = (float) Math.hypot(dx, dy);
        if (!Float.isFinite(distance) || distance <= DEADZONE_DP) return 0f;
        float gain = Float.isFinite(sensitivity)
            ? Math.max(LookSensitivity.MIN, Math.min(LookSensitivity.MAX, sensitivity))
            : LookSensitivity.DEFAULT;
        return Math.min(1f, Math.max(0f,
            (distance - DEADZONE_DP) * gain / (FULL_STICK_DP - DEADZONE_DP)));
    }
}
