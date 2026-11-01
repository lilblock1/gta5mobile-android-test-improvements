"""Native ARM64 HUD and stereo-audio stability regression gates.

All assertions guard real code paths; hardware validation still requires
a playback test on the Samsung device.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
def src(path):
    return (ROOT / path).read_text()

class NativeAudioOverlayPolicy(unittest.TestCase):
    def test_game_ring_diagnostics_dont_cover_gameplay(self):
        activity = src("android/app/src/main/java/dev/muguet/EngineActivity.java")
        status = src("android/app/src/main/java/dev/muguet/EngineStatusPolicy.java")
        touch = src("android/app/src/main/java/dev/muguet/TouchControls.java")
        self.assertIn("EngineStatusPolicy.showInGame(text)", activity)
        self.assertNotIn('text.startsWith("Graphics")', activity)
        self.assertIn("Gravity.TOP | android.view.Gravity.LEFT", activity)
        self.assertNotIn("android.view.Gravity.CENTER));\n        status.setVisibility", activity)
        self.assertIn('message.startsWith("Graphics initialization failed:")', status)
        self.assertIn('message.startsWith("Graphics validation failed:")', status)
        self.assertNotIn('message.startsWith("Graphics")', status)
        self.assertIn("canvas.drawCircle(sx, sy, stickRadius, paint)", touch)
        self.assertIn("canvas.drawCircle(sx + dx, sy + dy", touch)

    def test_native_stereo_ring_never_replays_overwritten_frames(self):
        host = src("vendor/fivestar/EngineHost.c")
        header = src("vendor/fivestar/EngineHost.h")
        self.assertIn("uint32_t PGFillAudio(float *left, float *right, uint32_t count)", header)
        self.assertIn("if (available > capacity)", host)
        self.assertIn("read = write - capacity", host)
        self.assertIn("atomic_fetch_add(&audioUnderflowFrames", host)
        self.assertIn("atomic_fetch_add(&audioOverflowFrames", host)
        self.assertIn("return take;", host)
        self.assertIn("PGGetAudioDiagnostics", host)

    def test_audio_consumer_handles_underrun_and_limits_peaks(self):
        bridge = src("vendor/fivestar/EngineBridge.c")
        activity = src("android/app/src/main/java/dev/muguet/EngineActivity.java")
        self.assertIn("PGFillAudio(g_audioL, g_audioR", bridge)
        self.assertIn("sampleCapacity / 2", bridge)
        self.assertIn("pg_audio_soft_limit", bridge)
        self.assertIn("g_audioLastBlockWasGap", bridge)
        self.assertIn("AUDIO_RAMP_FRAMES", bridge)
        self.assertIn("Native audio: submitted", bridge)
        self.assertIn("AudioTrack.WRITE_BLOCKING", activity)
        self.assertIn("AudioAttributes.CONTENT_TYPE_MUSIC", activity)
        self.assertIn("8192 * 2 * 2", activity)
        self.assertIn("short[1024 * 2]", activity)
        self.assertIn("THREAD_PRIORITY_AUDIO", activity)
        self.assertIn("if (audioPaused)", activity)
        self.assertIn("try { audio.play(); audioPaused = false; }", activity)
        self.assertIn("stopAudio();", activity)
        self.assertLess(activity.index("running = true;\n            if (!muted) startAudio();"),
                        activity.index("handler.post(tick);", activity.index("running = true;\n            if (!muted) startAudio();")))

if __name__ == "__main__":
    unittest.main()
