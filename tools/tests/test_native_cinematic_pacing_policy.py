"""Stable cinematic timing: exactly one primary FPS pacer at each target.

A source-policy test cannot establish actual device FPS; compare exported
cutscene runs and audio-production telemetry on the handset.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
def read(name):
    return (ROOT / name).read_text()

class NativeCinematicPacingSafetyTests(unittest.TestCase):
    def test_30fps_uses_only_game_pacing(self):
        java = read("android/app/src/main/java/dev/muguet/EngineActivity.java")
        bridge = read("vendor/fivestar/EngineBridge.c")
        self.assertIn('if (fps > 0 && fps < 60 && fps != 30)', java)
        self.assertIn('const int nativeFrameMode = frameLimit <= 0 ? 0 : frameLimit == 30 ? 2 : 1;', bridge)
        self.assertIn('vars.add("MUGUET_RENDER_FPS_LIMIT=" + fps)', java)
        self.assertIn('PGConfigureEngine(qualityPointers, qualityCount', bridge)
        self.assertIn("frameLimit == 30 ? 2 : 1", bridge)

    def test_host_pacing_and_gpu_fences_are_separately_measured(self):
        gpu = read("src/gpu.rs")
        for part in (
            "host_pace_sleeps", "host_pace_sleep_ns", "host_pace_max_ns",
            '"renderer pacing: host sleep calls', "std::thread::sleep(deadline - now)",
            "self.frame_due = Some(if next < actual",
            "frame_waits: u64",
            "gpu stalls: frame-slot waits",
        ):
            self.assertIn(part, gpu)
        self.assertIn("PollType::Wait { submission_index: Some(idx), timeout: None }", gpu)

    def test_audio_telemetry_catches_bad_one_second_windows(self):
        java = read("android/app/src/main/java/dev/muguet/EngineActivity.java")
        self.assertIn("worstSecondProducedFps", java)
        # 48-kHz AudioTrack sample rate is already named "rate" in
        # startAudio(); Java lambdas cannot redeclare enclosing locals.
        self.assertIn("long producedFramesPerSecond = newFrames * 1000L / secondsElapsedMs;", java)
        self.assertNotIn("long rate = newFrames * 1000L / secondsElapsedMs;", java)
        self.assertIn("if (producedFramesPerSecond < 46000)", java)
        self.assertIn('min1sProducedFps=', java)
        self.assertIn('slowProducerSeconds=', java)
        self.assertIn("nativeAudioProducedFrames() & 0xffffffffL", java)
        self.assertIn("if (audioStarted)", java)
        self.assertIn('nativeAudioReport(report)', java)
        self.assertIn("NativeAudioBufferPolicy.queueDepth", java)

if __name__ == "__main__":
    unittest.main()
