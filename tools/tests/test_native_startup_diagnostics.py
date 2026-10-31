"""Static regression checks for native ARM64 startup diagnostics.

The 256-file generated game code isn't kept in the repository; these
checks cover the standalone native synchronization/diagnostic interfaces
without requiring the game binary or Android NDK on the Rust/Java test job.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
HOST = ROOT / "vendor" / "fivestar"
ANDROID = ROOT / "android" / "app" / "src" / "main" / "java" / "dev" / "muguet"


class NativeStartupDiagnosticTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.futex = (HOST / "WasmFutex.c").read_text()
        cls.host = (HOST / "EngineHost.c").read_text()
        cls.bridge = (HOST / "EngineBridge.c").read_text()
        cls.activity = (ANDROID / "EngineActivity.java").read_text()
        cls.launcher = (ANDROID / "LauncherActivity.java").read_text()

    def test_finite_futex_wait_uses_one_monotonic_absolute_deadline(self):
        self.assertIn("clock_gettime(CLOCK_MONOTONIC, &deadline)", self.futex)
        self.assertIn("pthread_cond_timedwait(&waiter.condition, &queueLock, &deadline)", self.futex)
        self.assertIn("pthread_cond_wait(&waiter.condition, &queueLock)", self.futex)
        self.assertIn("while (!waiter.notified && !result)", self.futex)
        self.assertNotIn("uint64_t start = elapsed + (uint64_t)timeout", self.futex)
        self.assertNotIn("pg_port_cond_timedwait_relative(&waiter.condition", self.futex)
        self.assertIn("return notified ? 0 : 2", self.futex)

    def test_renderer_and_native_logs_share_a_bounded_native_file(self):
        self.assertIn("PG_NATIVE_LOG_LIMIT (8 * 1024 * 1024)", self.bridge)
        self.assertIn('native_log_to_file("native", message)', self.bridge)
        self.assertIn('native_log_to_file("renderer", line)', self.bridge)
        self.assertIn("static pthread_mutex_t nativeLogLock", self.bridge)
        self.assertIn('"nightlane-native.log"', self.activity)
        self.assertIn("nativeSetLogPath(", self.activity)
        self.assertIn('{"nativeSetLogPath",', self.bridge)

    def test_screen_does_not_treat_first_futex_wait_as_crash(self):
        self.assertIn('strncmp(message, "Futex wait at ", 14) == 0', self.bridge)
        self.assertIn('stats[0] == 0', self.activity)
        self.assertIn('Export logs', self.activity)
        self.assertIn('missingFrameWarningShown', self.activity)

    def test_launcher_exports_native_and_standard_engine_logs(self):
        self.assertIn('new File(getFilesDir(), "nightlane-native.log")', self.launcher)
        self.assertIn("new File[]{logFile(), nativeLogFile()}", self.launcher)
        self.assertIn("appendGameExitInfo(out)", self.launcher)
        self.assertIn("if (!hasNormal && !hasNative)", self.launcher)

    def test_graphics_ring_has_progress_diagnostics_without_fake_readiness(self):
        self.assertIn('i == 19 || i == 59 || i == 119', self.host)
        self.assertIn('ring head=%u tail=%u ready=%u done=%u', self.host)
        self.assertIn('Native renderer found no futex hooks', self.host)
        self.assertIn('muguet_gpu_start', self.host)


if __name__ == "__main__":
    unittest.main()
