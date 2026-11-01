"""Guards native touch camera stick wiring, independent of on-device proof."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

def source(p):
    return (ROOT / p).read_text()

class NativeRightStickPolicyTest(unittest.TestCase):
    def test_touch_anchors_are_sampled_as_stick_until_release(self):
        touch = source("android/app/src/main/java/dev/muguet/TouchControls.java")
        engine = source("android/app/src/main/java/dev/muguet/EngineActivity.java")
        self.assertIn("camera.x - camera.startX", touch)
        self.assertIn("camera.y - camera.startY", touch)
        self.assertIn("TouchLookPolicy.deflection(dx, dy, lookSensitivity)", touch)
        self.assertIn("input.rightStickX = dx / length * amount;", touch)
        self.assertIn("input.rightStickY = dy / length * amount;", touch)
        self.assertIn("new TouchControls(this, touchGain, true)", engine)
        self.assertIn("touch.rightStickX, touch.rightStickY, true", engine)
        self.assertNotIn("accLookX", engine)
        self.assertNotIn("lastTouchLookX", engine)
        self.assertIn("grips.remove(event.getPointerId(index))", touch)
        self.assertIn("else if (action == MotionEvent.ACTION_CANCEL) release();", touch)

    def test_jni_accepts_normalized_right_stick_with_expected_y_direction(self):
        bridge = source("vendor/fivestar/EngineBridge.c")
        self.assertIn("const float scale = useAnalogSticks ? 1.0f : (1.0f / 96.0f);", bridge)
        self.assertIn("pad.rightX = fmaxf(-1.0f, fminf(1.0f, lookDX * scale));", bridge)
        self.assertIn("pad.rightY = fmaxf(-1.0f, fminf(1.0f, -lookDY * scale));", bridge)
        self.assertIn("PGUpdateVirtualPad(pad);", bridge)

    def test_old_game_activity_retains_relative_touch_look(self):
        touch = source("android/app/src/main/java/dev/muguet/TouchControls.java")
        self.assertIn("GameActivity::nativeLook", touch)
        self.assertIn("if (!nativeRightStick && (grip.button == -1 || isAimDrag(grip)))", touch)

if __name__ == "__main__":
    unittest.main()
