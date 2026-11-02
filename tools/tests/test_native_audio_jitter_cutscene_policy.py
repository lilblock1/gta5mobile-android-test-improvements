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
        self.assertIn('{"nativeAudioReport",       "(Ljava/lang/String;)V"', bridge)
        self.assertIn('native_log_to_file("audio-track", text)', bridge)
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
        self.assertIn("NativeAudioBufferPolicy.requestedStartThreshold(capacityFrames)", activity)
        self.assertIn("audio.setStartThresholdInFrames(desired)", activity)
        self.assertIn("track.getStartThresholdInFrames()", activity)
        self.assertIn("audio.setBufferSizeInFrames(", activity)
        self.assertIn("NativeAudioBufferPolicy.outputQueueTarget(", activity)
        self.assertIn("queued >= queueTarget", activity)
        self.assertIn("played > 0 && queued >= NativeAudioBufferPolicy.MIN_QUEUED_FRAMES", activity)
        self.assertIn("audioOutputAudibleFrames", activity)
        self.assertIn("AudioTrack zero-head stall:", activity)
        # Previously this unconditional empty-ring wait permanently disabled
        # output whenever the game mixer produced no frames at startup.
        self.assertIn("NativeAudioBufferPolicy.waitForInitialSamples(", activity)
        self.assertIn("NativeAudioBufferPolicy.shouldStartAudioTrack(", activity)
        self.assertIn("AudioTrack.play() before first PCM write", activity)
        self.assertIn("AudioTrack first PCM write finished:", activity)
        self.assertLess(activity.index("track.play();"), activity.index("track.write(block, offset, requested, mode)"))
        self.assertIn("AudioTrack.WRITE_NON_BLOCKING", activity)
        self.assertIn("AudioTrack.WRITE_BLOCKING", activity)
        self.assertNotIn("writtenFrames >= Math.min(2048", activity)
        self.assertIn("nativeAudioCallbackCount()", activity)
        self.assertNotIn("if (priming && available == 0) {", activity)
        self.assertIn("int request = available > 0 ? Math.min(", activity)
        self.assertIn("newFrames / elapsed", activity)
        self.assertIn("track.getUnderrunCount()", activity)
        self.assertIn("gameProducedFps=", activity)
        self.assertIn("nativeAudioReport(report)", activity)
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
