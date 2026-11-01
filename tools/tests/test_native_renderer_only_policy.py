"""Regression guards for the ARM64 native-only renderer build."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[2]
class NativeRendererOnlyTest(unittest.TestCase):
    def test_no_wasmtime_library_is_linked(self):
        cargo = (ROOT / "Cargo.toml").read_text()
        renderer = (ROOT / "src/renderer_only.rs").read_text()
        self.assertIn('optional = true', cargo.split('wasmtime = ', 1)[1].split('\\n', 1)[0])
        self.assertIn('native-renderer = []', cargo)
        self.assertNotIn('use wasmtime', renderer)
        self.assertIn('include!("renderer_only.rs")', (ROOT / "src/lib_router.rs").read_text())
        self.assertIn('muguet_gpu_start', (ROOT / "src/gpu.rs").read_text())
    def test_one_native_only_play_button(self):
        wf = (ROOT / ".github/workflows/native-arm64-experiment.yml").read_text()
        gradle = (ROOT / "android/app/build.gradle").read_text()
        launcher = (ROOT / "android/app/src/main/java/dev/muguet/LauncherActivity.java").read_text()
        self.assertIn('-PnativeRendererOnly=true', wf)
        self.assertIn('--no-default-features', gradle)
        self.assertIn('lib/arm64-v8a/libmuguet.so', wf)
        self.assertIn('lib/arm64-v8a/libgta5engine.so', wf)
        self.assertIn('play.setOnClickListener(v -> launch(EngineActivity.class))', launcher)
        self.assertNotIn('android:name="dev.muguet.GameActivity"', (ROOT / "android/app/src/main/AndroidManifest.xml").read_text())
if __name__ == "__main__":
    unittest.main()
