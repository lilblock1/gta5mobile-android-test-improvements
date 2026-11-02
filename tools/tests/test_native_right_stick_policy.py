"""Guards native touch camera stick wiring, independent of on-device proof."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

def source(p):
    return (ROOT / p).read_text()

class NativeRightStickPolicyTest(unittest.TestCase):
    def test_touchpad_is_default_and_stick_mode_remains_available(self):
        touch = source("android/app/src/main/java/dev/muguet/TouchControls.java")
        engine = source("android/app/src/main/java/dev/muguet/EngineActivity.java")
        policy = source("android/app/src/main/java/dev/muguet/TouchLookPolicy.java")
        launcher = source("android/app/src/main/java/dev/muguet/LauncherActivity.java")
        self.assertIn("TouchControls.nativeTouchpad(this, touchGain,", engine)
        self.assertIn('settings.optBoolean("nightlane_touchpad_camera", true)', engine)
        self.assertIn("new TouchControls(this, touchGain, true)", engine)
        self.assertIn("nativeTouchLook(dx, dy)", engine)
        self.assertIn("nativeTouchpad", touch)
        self.assertIn("TouchLookPolicy.relativePixels(dx, lookSensitivity)", touch)
        self.assertIn("TouchLookPolicy.relativePixels(dy, lookSensitivity)", touch)
        self.assertIn("input.rightStickX = dx / length * amount;", touch)
        self.assertIn("input.rightStickY = dy / length * amount;", touch)
        self.assertIn("float pixels = deltaDp * gain * 2.5f;", policy)
        self.assertIn('settings.put("nightlane_touchpad_camera", touchpadCamera.isChecked())', launcher)
        self.assertIn("pendingTouchpadX = pendingTouchpadY = 0f", touch)
        self.assertIn("grips.remove(event.getPointerId(index))", touch)
        self.assertIn("else if (action == MotionEvent.ACTION_CANCEL) release();", touch)

    def test_jni_accepts_normalized_right_stick_with_expected_y_direction(self):
        bridge = source("vendor/fivestar/EngineBridge.c")
        self.assertIn("const float scale = useAnalogSticks ? 1.0f : (1.0f / 96.0f);", bridge)
        self.assertIn("pad.rightX = fmaxf(-1.0f, fminf(1.0f, lookDX * scale));", bridge)
        self.assertIn("pad.rightY = fmaxf(-1.0f, fminf(1.0f, -lookDY * scale));", bridge)
        self.assertIn("PGUpdateVirtualPad(pad);", bridge)
        self.assertIn("PGInjectTouchMouseDelta(", bridge)
        self.assertIn('{"nativeTouchLook",         "(FF)V"', bridge)

    def test_old_game_activity_retains_relative_touch_look(self):
        touch = source("android/app/src/main/java/dev/muguet/TouchControls.java")
        self.assertIn("GameActivity::nativeLook", touch)
        self.assertIn("if (!nativeRightStick && (grip.button == -1 || isAimDrag(grip)))", touch)

if __name__ == "__main__":
    unittest.main()
