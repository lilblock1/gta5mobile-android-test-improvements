"""Check the native-only launcher quick repack never recompiles engine code."""
import pathlib
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[2]

class FastNativeRepackPolicy(unittest.TestCase):
    def test_download_control(self):
        launcher = (ROOT / "android/app/src/main/java/dev/muguet/LauncherActivity.java").read_text()
        self.assertIn('downloadGameButton.setText("Download game files (HTTPS)")', launcher)
        self.assertIn('downloadGameButton.setOnClickListener(v -> downloadGameFiles());', launcher)
        self.assertIn('private void downloadGameFiles()', launcher)
        self.assertIn('installOnFirstPlay(null);', launcher)
        self.assertIn('downloadGameButton.setEnabled(true);', launcher)
        self.assertIn('else if (activity != null) launch(activity);', launcher)
        self.assertIn('GameDataInstaller.HTTPS', launcher)
        self.assertIn('play.setOnClickListener(v -> playNative());', launcher)
        self.assertIn('private void playNative()', launcher)
        self.assertIn('play.setEnabled(true);', launcher)
        self.assertIn('showLastLaunchError()', launcher)
        engine = (ROOT / "android/app/src/main/java/dev/muguet/EngineActivity.java").read_text()
        self.assertIn('System.loadLibrary("muguet");', engine)
        self.assertIn('reportStartupFailure(', engine)
        self.assertIn('Return to launcher', engine)
        self.assertNotIn('finish();\n            return;\n        }\n        engineCoreLoaded', engine)
        self.assertLess(launcher.index("column.addView(play);"),
                        launcher.index("column.addView(pauseDownload);"))
        self.assertNotIn('downloadGameButton.setEnabled(!ready && folder.isEmpty())', launcher)

    def test_fast_workflow(self):
        workflow = (ROOT / ".github/workflows/native-arm64-fast-repack.yml").read_text()
        self.assertIn("-x :app:buildRust", workflow)
        self.assertNotIn("-PwithEngineCore=true", workflow)
        self.assertIn("tools/reuse_native_libraries.py", workflow)
        self.assertIn("Nightlane-ARM64-RENDERER-LINK-FIX-0.1.12", workflow)
        self.assertIn("cargo ndk -t arm64-v8a --platform 29", workflow)
        self.assertIn("Precompiled native game engine was modified", workflow)
        renderer = (ROOT / "src/renderer_only.rs").read_text()
        self.assertIn('pub extern "C" fn android_main(', renderer)
        self.assertIn('#[no_mangle]', renderer)

    def test_full_compile_not_auto_triggered(self):
        native = (ROOT / ".github/workflows/native-arm64-experiment.yml").read_text()
        self.assertNotIn("experimental/native-only-renderer", native.split("workflow_dispatch:", 1)[0])

if __name__ == "__main__":
    unittest.main()
