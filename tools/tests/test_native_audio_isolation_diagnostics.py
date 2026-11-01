"""Guard the two independent probes needed to locate the silent native audio path."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]

def src(path):
    return (ROOT / path).read_text()

class NativeAudioIsolationDiagnostics(unittest.TestCase):
    def test_speaker_tone_is_opt_in_and_does_not_depend_on_the_game(self):
        launcher = src("android/app/src/main/java/dev/muguet/LauncherActivity.java")
        probe = src("android/app/src/main/java/dev/muguet/AudioOutputProbe.java")
        self.assertIn('testSpeaker.setText("Test speaker (short tone)")', launcher)
        self.assertIn("AudioOutputProbe.test(result ->", launcher)
        self.assertIn("track.play(); // Start the output clock before a blocking write.", probe)
        self.assertIn("AudioTrack.WRITE_BLOCKING", probe)
        self.assertIn("Math.sin(2.0 * Math.PI * 440.0 * i / rate)", probe)
        self.assertNotIn("nativeAudio(", probe)

    def test_watchdog_runs_independent_of_pcm_writes_and_logs_outside_android_logcat(self):
        engine = src("android/app/src/main/java/dev/muguet/EngineActivity.java")
        self.assertIn("private void recordAudioWatchdog()", engine)
        self.assertIn("recordAudioWatchdog();", engine)
        self.assertIn("nativeAudioProducedFrames()", engine)
        self.assertIn("nativeAudioCallbackCount()", engine)
        self.assertIn("track.getPlaybackHeadPosition()", engine)
        self.assertIn("track.getPlayState()", engine)
        self.assertIn("worker.getState()", engine)
        self.assertIn("worker.getStackTrace()", engine)
        self.assertIn('nativeAudioReport(message);', engine)
        self.assertIn('"audio-watchdog: producer="', engine)
        # The mixer and renderer's fast paths remain unchanged.
        self.assertIn("handler.postDelayed(this, 16);", engine)

if __name__ == "__main__":
    unittest.main()
