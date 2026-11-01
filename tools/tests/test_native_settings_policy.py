"""Native-only configuration must pass supported controls into the C renderer."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[2]

class NativeSettingsPolicy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = (ROOT / "android/app/src/main/java/dev/muguet/EngineActivity.java").read_text()
        cls.launcher = (ROOT / "android/app/src/main/java/dev/muguet/LauncherActivity.java").read_text()
        cls.bridge = (ROOT / "vendor/fivestar/EngineBridge.c").read_text()
    def test_engine_forwards_settings_before_native_start(self):
        self.assertIn("nativeConfigure(nativeGraphicsArgs(config)", self.engine)
        self.assertLess(self.engine.index("nativeConfigure(nativeGraphicsArgs(config)"),
                        self.engine.index("nativeStart(gameDir.getAbsolutePath()"))
        self.assertIn("nightlane_native_render_scale", self.launcher)
        self.assertIn("native_cpu_workers", self.launcher)
        self.assertIn("nativeTargetFps(config)", self.engine)
    def test_engine_fps_is_not_passed_as_raw_mode(self):
        self.assertIn("const int nativeFrameMode = frameLimit <= 0 ? 0 : frameLimit <= 30 ? 2 : 1", self.bridge)
        self.assertIn("PGConfigureEngine(qualityPointers, qualityCount", self.bridge)
        self.assertIn("MUGUET_RENDER_FPS_LIMIT", self.engine)
        self.assertNotIn("PGConfigureEngine(quality, 0, width, height, frameLimit)", self.bridge)
    def test_graphics_and_renderer_settings_have_allowlist(self):
        self.assertIn("approvedQuality[]", self.bridge)
        self.assertIn("approvedRendererEnv[]", self.bridge)
        self.assertIn("NIGHTLANE_PRESENT", self.bridge)
        self.assertIn("NIGHTLANE_FRAME_QUEUE", self.bridge)
        self.assertIn('nativeConfigure",         "([Ljava/lang/String;IF[Ljava/lang/String;)V"', self.bridge)

if __name__ == "__main__":
    unittest.main()
