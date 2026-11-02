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

    def test_legacy_fast_workflow_retired_after_native_abi_change(self):
        workflow = (ROOT / ".github/workflows/native-arm64-fast-repack.yml").read_text()
        self.assertNotIn("branches: [experimental/native-only-renderer]", workflow)
        self.assertIn("nativeConfigure()", workflow)
        self.assertIn("exit 1", workflow)
        renderer = (ROOT / "src/renderer_only.rs").read_text()
        self.assertIn('pub extern "C" fn android_main(', renderer)
        self.assertIn('#[no_mangle]', renderer)

    def test_native_only_touch_does_not_call_old_jni(self):
        touch = (ROOT / "android/app/src/main/java/dev/muguet/TouchControls.java").read_text()
        engine = (ROOT / "android/app/src/main/java/dev/muguet/EngineActivity.java").read_text()
        old = (ROOT / "android/app/src/main/java/dev/muguet/GameActivity.java").read_text()
        self.assertIn("interface LookSink", touch)
        self.assertIn("lookSink.onLook(", touch)
        self.assertIn("GameActivity::nativeLook", touch)
        self.assertNotIn("GameActivity.nativeLook(", touch)
        self.assertIn("TouchControls.nativeTouchpad(this, touchGain,", engine)
        self.assertIn("new TouchControls(this, touchGain, true)", engine)
        self.assertIn("nativeTouchLook(dx, dy)", engine)
        self.assertIn("touchpadCamera ? 0f : touch.rightStickX", engine)
        self.assertIn("nativeUpdatePad(keys.toArray", engine)
        self.assertNotIn("accLookX + dx", engine)
        self.assertNotIn("accLookY + dy", engine)
        self.assertIn("new TouchControls(this,", old)

    def test_native_surface_uses_reduced_internal_resolution(self):
        engine = (ROOT / "android/app/src/main/java/dev/muguet/EngineActivity.java").read_text()
        self.assertIn('surface.getHolder().setFixedSize(renderWidth, renderHeight);', engine)
        self.assertIn('nightlane_native_render_scale', engine)
        self.assertIn('int width = renderWidth', engine)
        self.assertIn('int height = renderHeight', engine)

    def test_full_optimized_native_compile_is_auto_triggered(self):
        native = (ROOT / ".github/workflows/native-arm64-experiment.yml").read_text()
        self.assertIn("experimental/native-only-renderer", native.split("workflow_dispatch:", 1)[0])
        self.assertIn("-PnativeRendererOnly=true", native)
        self.assertIn("--native-only", native)
        self.assertIn("Nightlane-ARM64-NATIVE-ONLY-OPTIMIZED-0.2.0", native)
        self.assertIn("Confirm optimized Clang options were used", native)

if __name__ == "__main__":
    unittest.main()
