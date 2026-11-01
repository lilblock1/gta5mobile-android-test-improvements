"""Pre-launch cache and Vulkan pipeline persistence safety gates.

Device FPS and cache reuse benefit must still be measured on Android hardware.
"""
import pathlib
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[2]

def source(path):
    return (ROOT / path).read_text(encoding="utf8")

class NightlanePrecacheTests(unittest.TestCase):
    def test_preparation_is_real_bounded_local_and_cancellable(self):
        src = source("android/app/src/main/java/dev/muguet/NightlanePrecache.java")
        self.assertIn("MAX_SHADERS = 128", src)
        self.assertIn("MAX_ASSET_FILES = 96", src)
        self.assertIn("ASSET_PREFIX_BYTES = 512 * 1024", src)
        self.assertIn("48L * 1024 * 1024", src)
        self.assertIn("skip.get()", src)
        self.assertIn("InputStream input = new BufferedInputStream(new FileInputStream(", src)
        self.assertIn('file.getCanonicalPath().startsWith(base)', src)
        self.assertIn("markerDir", src)
        self.assertIn("MessageDigest.getInstance(\"SHA-256\")", src)
        self.assertIn("listener.update(100,", src)
        self.assertNotIn("Thread.sleep", src)
        self.assertNotIn("FileOutputStream", src)
        self.assertNotIn("System.loadLibrary", src)
        self.assertNotIn("HttpURLConnection", src)

    def test_shader_warmup_uses_native_naga_without_faking_gpu_compilation(self):
        src = source("src/renderer_only.rs")
        self.assertIn("Java_dev_muguet_LauncherActivity_nativeShaderValidate", src)
        self.assertIn("wgpu::naga::front::wgsl::parse_str", src)
        self.assertIn("metadata.len() > 2 * 1024 * 1024", src)
        launch = source("android/app/src/main/java/dev/muguet/LauncherActivity.java")
        self.assertIn("nativeShaderValidate(source)", launch)
        self.assertIn("NightlanePrecache.prepare(", launch)
        self.assertIn("prelaunchWarmup.isChecked()", launch)
        self.assertIn("new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)", launch)
        self.assertIn("setNegativeButton(\"Skip\"", launch)
        self.assertIn("warmingNative.compareAndSet(false, true)", launch)
        self.assertIn('if (activity == EngineActivity.class', launch)
        self.assertIn('settings.optBoolean("nightlane_prelaunch_warmup", true)', launch)

    def test_persistent_gpu_cache_is_driver_guarded_bounded_and_async(self):
        src = source("src/gpu.rs")
        self.assertIn("| Features::PIPELINE_CACHE", src)
        self.assertIn("wgpu::util::pipeline_cache_key(&info)", src)
        self.assertIn("shader_index_fingerprint(index_text.as_bytes())", src)
        self.assertIn("const PIPELINE_DISK_CACHE_MAX: u64 = 32 * 1024 * 1024;", src)
        self.assertIn("device.create_pipeline_cache(&PipelineCacheDescriptor", src)
        self.assertIn("fallback: true", src)
        self.assertIn("cache: self.pipeline_disk.as_ref()", src)
        self.assertIn("compile_pipeline(&dev, &lost, &job, disk.as_ref())", src)
        self.assertIn("cache.get_data()", src)
        self.assertIn('std::thread::Builder::new().name("nightlane-cache-writer"', src)
        self.assertIn('std::fs::rename(&temp, &path)', src)
        self.assertIn("fn shader_index_fingerprint_changes_with_index_bytes()", src)

    def test_game_start_and_existing_download_stay_usable(self):
        engine = source("android/app/src/main/java/dev/muguet/EngineActivity.java")
        bridge = source("vendor/fivestar/EngineBridge.c")
        launch = source("android/app/src/main/java/dev/muguet/LauncherActivity.java")
        self.assertIn('nativeOptions.add("NIGHTLANE_PIPELINE_CACHE_DIR="', engine)
        self.assertIn('"NIGHTLANE_PIPELINE_CACHE_DIR"', bridge)
        self.assertIn('startActivity(new Intent(this, activity));', launch)
        self.assertIn("GameDataInstaller installer = new GameDataInstaller", launch)
        self.assertIn("installOnFirstPlay(null);", launch)
        self.assertIn('if (!nativePrepare(getFilesDir().getAbsolutePath()))', launch)

if __name__ == "__main__":
    unittest.main()
