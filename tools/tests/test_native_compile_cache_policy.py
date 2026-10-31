"""Guard the optional native compiler-cache optimization.

These tests are intentionally offline; neither Android SDK nor generated
proprietary game code is needed to verify CI safety properties.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE_WORKFLOW = ROOT / ".github/workflows/native-arm64-experiment.yml"
CMAKE = ROOT / "android/app/CMakeLists.txt"
STANDARD_WORKFLOW = ROOT / ".github/workflows/android.yml"


class NativeCompileCachePolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = NATIVE_WORKFLOW.read_text(encoding="utf-8")
        cls.cmake = CMAKE.read_text(encoding="utf-8")
        cls.standard = STANDARD_WORKFLOW.read_text(encoding="utf-8")

    def test_compiler_launcher_is_opt_in_and_requires_ccache(self):
        self.assertIn('if("$ENV{NIGHTLANE_NATIVE_CCACHE}" STREQUAL "1")', self.cmake)
        self.assertIn("find_program(NIGHTLANE_CCACHE_EXECUTABLE NAMES ccache)", self.cmake)
        self.assertIn("C_COMPILER_LAUNCHER", self.cmake)
        self.assertIn('message(FATAL_ERROR "NIGHTLANE_NATIVE_CCACHE=1 but ccache was not found")', self.cmake)
        self.assertIn("set_property(TARGET gta5engine PROPERTY C_COMPILER_LAUNCHER", self.cmake)

    def test_existing_compiler_and_game_flags_remain_intact(self):
        for flag in ("-g0", "-frounding-math", "-fno-optimize-sibling-calls", "PG_UNCHECKED_MEMORY=1"):
            self.assertIn(flag, self.cmake)
        self.assertIn("set_source_files_properties(${GAME_CODE_SOURCES}", self.cmake)

    def test_cache_only_applies_to_experimental_native_workflow(self):
        self.assertIn("NIGHTLANE_NATIVE_CCACHE=1", self.workflow)
        self.assertNotIn("NIGHTLANE_NATIVE_CCACHE", self.standard)
        self.assertIn("ccache", self.workflow)
        self.assertIn("CARGO_BUILD_JOBS: '2'", self.workflow)
        self.assertIn("--max-workers=2", self.workflow)
        self.assertIn("-PwithEngineCore=true", self.workflow)

    def test_compiler_cache_is_content_sensitive_bounded_and_restored(self):
        self.assertIn("actions/cache/restore@v4", self.workflow)
        self.assertIn("actions/cache/save@v4", self.workflow)
        self.assertIn("CCACHE_MAXSIZE=2G", self.workflow)
        self.assertIn("CCACHE_COMPILERCHECK=content", self.workflow)
        self.assertIn("CCACHE_BASEDIR=$GITHUB_WORKSPACE", self.workflow)
        self.assertIn("android/app/CMakeLists.txt", self.workflow)
        self.assertIn("vendor/fivestar/**", self.workflow)
        self.assertIn("app/Scripts/**", self.workflow)
        self.assertIn("github.run_id", self.workflow)
        self.assertIn("steps.native_c_cache.outputs.cache-primary-key", self.workflow)

    def test_partial_compiles_can_be_saved_without_publishing_failed_apks(self):
        self.assertIn("timeout-minutes: 120", self.workflow)
        self.assertIn("if: always() && steps.native_c_cache.outcome == 'success'", self.workflow)
        self.assertIn("Native compiler cache statistics", self.workflow)
        self.assertIn("Publish experimental dual-engine debug APK", self.workflow)
        self.assertIn("Validate that BOTH runtime libraries were actually packaged", self.workflow)


if __name__ == "__main__":
    unittest.main()
