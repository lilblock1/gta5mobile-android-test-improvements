"""Build optimization stays scoped to wasm2c, without changing guest memory semantics."""
import pathlib, unittest
ROOT = pathlib.Path(__file__).resolve().parents[2]

class NativePerformancePolicy(unittest.TestCase):
    def test_optimized_c_compilation(self):
        cmake = (ROOT / "android/app/CMakeLists.txt").read_text()
        self.assertIn("target_compile_options(gta5engine PRIVATE\n  -O2", cmake)
        self.assertIn('COMPILE_OPTIONS "-w;-O1"', cmake)
        self.assertIn("WASM_RT_MEMCHECK_BOUNDS_CHECK=1", cmake)
        self.assertIn("-frounding-math", cmake)
        self.assertIn("-fno-optimize-sibling-calls", cmake)
    def test_pipeline_worker_cap(self):
        gpu = (ROOT / "src/gpu.rs").read_text()
        self.assertIn("NIGHTLANE_PIPELINE_WORKERS", gpu)
        self.assertIn("(1..=4).contains(&count)", gpu)
        self.assertIn("2 } else { 4 }", gpu)
        bridge = (ROOT / "vendor/fivestar/EngineBridge.c").read_text()
        self.assertIn('"NIGHTLANE_PIPELINE_WORKERS"', bridge)

if __name__ == "__main__":
    unittest.main()
