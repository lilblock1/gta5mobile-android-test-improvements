"""Native audio/cutscene performance guardrails: no faked playback success.

Audio hardware testing must still be performed on a physical Android device.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
def source(path):
    return (ROOT / path).read_text()

class NativeAudioJitterSafetyTests(unittest.TestCase):
    def test_audio_ring_is_observed_before_consuming(self):
        h = source("vendor/fivestar/EngineHost.c")
        header = source("vendor/fivestar/EngineHost.h")
        bridge = source("vendor/fivestar/EngineBridge.c")
        self.assertIn("PGAvailableAudioFrames(uint32_t *reportedCapacity)", h)
        self.assertIn("if (!ring || !capacity) return 0;", h)
        self.assertIn("PGProducedAudioFrames(void)", h)
        self.assertIn("uint32_t PGAvailableAudioFrames(uint32_t *capacity);", header)
        self.assertIn("uint32_t PGProducedAudioFrames(void);", header)
        self.assertIn('{"nativeAudioBufferState", "()J"', bridge)
        self.assertIn('{"nativeAudioProducedFrames", "()J"', bridge)
        self.assertIn("PGAvailableAudioFrames(&capacity)", bridge)
        self.assertIn("PGProducedAudioFrames()", bridge)
        self.assertIn("uint32_t real = PGFillAudio", bridge)
        self.assertIn("g_audioLastBlockWasGap", bridge)

    def test_queue_avoids_runaway_latency_and_reports_source_fps(self):
        activity = source("android/app/src/main/java/dev/muguet/EngineActivity.java")
        policy = source("android/app/src/main/java/dev/muguet/NativeAudioBufferPolicy.java")
        self.assertIn("TARGET_QUEUED_FRAMES = 4096", policy)
        self.assertIn("MIN_QUEUED_FRAMES = 1536", policy)
        self.assertIn("MAX_STARTUP_WAIT_MS = 1500", policy)
        self.assertIn("NativeAudioBufferPolicy.startupWatermark(capacity)", activity)
        self.assertIn("NativeAudioBufferPolicy.queueDepth(writtenFrames, played)", activity)
        self.assertIn("NativeAudioBufferPolicy.waitForProducer(available, queued, priming)", activity)
        self.assertIn("queued >= NativeAudioBufferPolicy.TARGET_QUEUED_FRAMES", activity)
        self.assertIn("if (priming && available == 0)", activity)
        self.assertIn("int request = available > 0 ? Math.min(", activity)
        self.assertIn("newFrames / elapsed", activity)
        self.assertIn("track.getUnderrunCount()", activity)
        self.assertIn("gameProducedFps=", activity)
        self.assertIn("audioStarted = true", activity)
        self.assertNotIn("audio.play();\n        audioThread = new Thread", activity)

    def test_shader_workers_use_mild_nice_instead_of_starving_audio(self):
        gpu = source("src/gpu.rs")
        self.assertIn("libc::setpriority(libc::PRIO_PROCESS, 0, 4)", gpu)
        self.assertIn("#[cfg(target_os = \"android\")]", gpu)
        self.assertIn('name(format!("pipeline-compile-{i}"))', gpu)
        self.assertIn("let p = compile_pipeline(&dev, &lost, &job, disk.as_ref());", gpu)
        self.assertIn("pipeline_last_compile_request.elapsed() < Duration::from_secs(8)", gpu)
        self.assertIn("!self.pipe_pending.is_empty()", gpu)
        bridge = source("vendor/fivestar/EngineBridge.c")
        self.assertIn("if (!fatal || !g_vm || !g_activity || !g_set_status_method) return;", bridge)

if __name__ == "__main__":
    unittest.main()
