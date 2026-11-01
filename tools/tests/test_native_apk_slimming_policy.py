"""Keep native-only APK independent of old JIT resource payload."""
import pathlib, unittest
ROOT = pathlib.Path(__file__).resolve().parents[2]

class NativeApkSlimmingPolicy(unittest.TestCase):
    def test_gradle_excludes_wasm_from_native_package(self):
        gradle = (ROOT / "android/app/build.gradle").read_text()
        self.assertIn("nativeOnlyBuild", gradle)
        self.assertIn("if (!nativeOnlyBuild) include 'assets/b/8b0b5899ed/game.wasm'", gradle)
        self.assertIn("buildConfigField 'boolean', 'NATIVE_ONLY'", gradle)
        self.assertIn("include 'assets/b/8b0b5899ed/shaders/**'", gradle)
    def test_download_and_runtime_stay_compatible(self):
        installer = (ROOT / "android/app/src/main/java/dev/muguet/GameDataInstaller.java").read_text()
        self.assertIn("!BuildConfig.NATIVE_ONLY", installer)
        self.assertIn('new File(build, "shaders/index.json")', installer)
        renderer = (ROOT / "src/renderer_only.rs").read_text()
        self.assertNotIn('game.wasm', renderer.split("fn valid(dir:",1)[1].split("fn find_root",1)[0])
    def test_verified_native_apk_has_compiled_engine(self):
        verifier = (ROOT / "tools/engine_resources.py").read_text()
        self.assertIn("native_only=False", verifier)
        self.assertIn("--native-only", verifier)
        self.assertIn('lib/arm64-v8a/libgta5engine.so', verifier)

if __name__ == "__main__":
    unittest.main()
