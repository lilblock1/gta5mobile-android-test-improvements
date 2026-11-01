"""Regressions for the native-only performance pass.

These are source-policy gates; actual performance and memory lifetime still
require the Android ARM64 build and long device sessions.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


def source(path):
    return (ROOT / path).read_text(encoding="utf-8")


class NativePerformanceSafetyTest(unittest.TestCase):
    def test_gpu_eviction_keeps_rehydration_and_45k_policy(self):
        gpu = source("src/gpu.rs")
        self.assertIn("const GPU_BUFFER_SOFT_LIMIT: u64 = 45_000;", gpu)
        self.assertIn("const GPU_BUFFER_SWEEP_GOAL: usize = 15_000;", gpu)
        self.assertIn("const GPU_EVICT_PER_FRAME: usize = 128;", gpu)
        self.assertIn("can_restore_buffer(b.restore_usage.is_some()", gpu)
        self.assertIn("resource_cold(self.presents", gpu)
        self.assertIn("oldest_candidates(&mut candidates, GPU_BUFFER_SWEEP_GOAL);", gpu)
        self.assertIn("fn candidate_selection_only_keeps_oldest_ids()", gpu)

    def test_readback_retains_completion_semantics(self):
        gpu = source("src/gpu.rs")
        self.assertIn("self.flush();\n            self.map_pending(rb, MapKind::Readback", gpu)
        self.assertIn("if let MapKind::Readback { flag, .. } = &kind", gpu)
        self.assertIn("self.mem.word(*flag).store(0, Ordering::SeqCst)", gpu)
        self.assertIn("if self.readback_pool_enabled && mapped && !self.lost.load(Ordering::SeqCst)", gpu)
        self.assertIn("self.readback_pool.len() < 4", gpu)
        self.assertIn("NIGHTLANE_READBACK_REUSE", gpu)
        self.assertIn("buf.size() <= 8 * 1024 * 1024", gpu)
        self.assertIn("self.readback_pool.push(buf);", gpu)
        self.assertIn("fn frame_gap_percentiles_detect_outliers()", gpu)

    def test_pipeline_still_skips_incomplete_variants_without_blocking(self):
        gpu = source("src/gpu.rs")
        self.assertIn("if matches!(self.pipelines.get(&key), Some(PipeEntry::Pending))", gpu)
        self.assertIn("self.collect_pipelines();", gpu)
        self.assertIn("Some(PipeEntry::Pending) => { self.pending_skips += 1; return None; }", gpu)
        self.assertIn("self.pipe_pending.insert(id", gpu)

    def test_native_read_ahead_is_small_and_reversible(self):
        fs = source("vendor/fivestar/NativeHttpFs.c")
        activity = source("android/app/src/main/java/dev/muguet/EngineActivity.java")
        launcher = source("android/app/src/main/java/dev/muguet/LauncherActivity.java")
        self.assertIn("pgHttpFsReadAheadKB = 64;", fs)
        self.assertIn("requested >= 0 && requested <= 256", fs)
        self.assertIn("offset == cache->lastReadEnd", fs)
        self.assertIn("POSIX_FADV_WILLNEED", fs)
        self.assertIn("PG_HTTPFS_READAHEAD_KB=", activity)
        self.assertIn('cfg.optBoolean("nightlane_read_ahead", true)', activity)
        self.assertIn('settings.optBoolean("nightlane_read_ahead", true)', launcher)
        self.assertIn("pgHttpFsSlowReads", fs)
        self.assertIn("cache->files[i].id = 0xffffffffu;", fs)

    def test_touch_aim_and_camera_can_move_together(self):
        touch = source("android/app/src/main/java/dev/muguet/TouchControls.java")
        activity = source("android/app/src/main/java/dev/muguet/EngineActivity.java")
        bridge = source("vendor/fivestar/EngineBridge.c")
        launcher = source("android/app/src/main/java/dev/muguet/LauncherActivity.java")
        self.assertIn('grip.button == -1 || isAimDrag(grip)', touch)
        self.assertIn('action.equals("aim") || action.equals("fire")', touch)
        self.assertIn('boolean hasCameraFinger()', touch)
        self.assertIn('if (aim && !inCar) input.buttons |= 2;', touch)
        self.assertIn('now - lastTouchLookAt < 48_000_000L', activity)
        self.assertIn('controls.hasCameraFinger()', activity)
        self.assertIn('lookDX / 96.0f', bridge)
        self.assertIn('pad.leftTrigger = 1.0f;', bridge)
        self.assertIn('PGSetTargetingMode(mode);', bridge)
        self.assertIn('nativeSetTargetingMode(3)', activity)
        self.assertIn('"nightlane_touch_free_aim"', launcher)

    def test_no_fake_gpu_stall_shortcuts(self):
        gpu = source("src/gpu.rs")
        self.assertIn("PollType::Wait { submission_index: None, timeout: None }", gpu)
        self.assertIn("let popped_validation = validation.pop();", gpu)
        self.assertIn("let popped_oom = oom.pop();", gpu)


if __name__ == "__main__":
    unittest.main()
