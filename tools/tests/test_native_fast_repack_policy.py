"""Check the native-only launcher quick repack never recompiles engine code."""
import pathlib
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[2]

class FastNativeRepackPolicy(unittest.TestCase):
    def test_download_control(self):
        launcher = (ROOT / "android/app/src/main/java/dev/muguet/LauncherActivity.java").read_text()
        self.assertIn('downloadGameButton.setText("Download game files (HTTPS)")', launcher)
        self.assertIn("installOnFirstPlay(EngineActivity.class);", launcher)
        self.assertIn("downloadGameButton.setEnabled(!ready && folder.isEmpty())", launcher)

    def test_fast_workflow(self):
        workflow = (ROOT / ".github/workflows/native-arm64-fast-repack.yml").read_text()
        self.assertIn("-x :app:buildRust", workflow)
        self.assertNotIn("-PwithEngineCore=true", workflow)
        self.assertIn("tools/reuse_native_libraries.py", workflow)
        self.assertIn("Nightlane-ARM64-NATIVE-ONLY-DOWNLOAD-FIX", workflow)

    def test_full_compile_not_auto_triggered(self):
        native = (ROOT / ".github/workflows/native-arm64-experiment.yml").read_text()
        self.assertNotIn("experimental/native-only-renderer", native.split("workflow_dispatch:", 1)[0])

if __name__ == "__main__":
    unittest.main()
