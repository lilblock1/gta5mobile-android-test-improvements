package dev.muguet;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;
import java.util.function.Consumer;

/**
 * User-triggered, low-volume output test that does NOT use any GTA game audio
 * or native engine code. A successful write is not proof sound was heard.
 */
final class AudioOutputProbe {
    private static final int RATE = 48000;
    private static final int DURATION_MS = 500;

    private AudioOutputProbe() {}

    static short[] toneSamples(int rate, int durationMs) {
        if (rate <= 0 || durationMs <= 0 || durationMs > 2000)
            throw new IllegalArgumentException("Invalid audio probe duration/rate");
        int frames = rate * durationMs / 1000;
        short[] samples = new short[frames * 2];
        int fadeFrames = Math.max(1, rate / 80); // 12.5-ms fade both ends.
        for (int i = 0; i < frames; i++) {
            double envelope = Math.min(1.0, Math.min((double)i / fadeFrames,
                (double)(frames - 1 - i) / fadeFrames));
            short value = (short)Math.round(32767.0 * 0.14 * envelope
                * Math.sin(2.0 * Math.PI * 440.0 * i / rate));
            samples[i * 2] = value;
            samples[i * 2 + 1] = value;
        }
        return samples;
    }

    /** Runs entirely on its own thread. The callback may run off the UI thread. */
    static void test(Consumer<String> report) {
        new Thread(() -> {
            AudioTrack track = null;
            try {
                int channels = AudioFormat.CHANNEL_OUT_STEREO;
                int pcm = AudioFormat.ENCODING_PCM_16BIT;
                int minimum = AudioTrack.getMinBufferSize(RATE, channels, pcm);
                if (minimum <= 0) throw new IllegalStateException("AudioTrack min buffer=" + minimum);
                track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(RATE).setChannelMask(channels).setEncoding(pcm).build())
                    .setBufferSizeInBytes(Math.max(minimum, 4096 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED)
                    throw new IllegalStateException("AudioTrack not initialized");

                short[] samples = toneSamples(RATE, DURATION_MS);
                track.play(); // Start the output clock before a blocking write.
                int sent = 0;
                while (sent < samples.length) {
                    int written = track.write(samples, sent, samples.length - sent,
                        AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) throw new IllegalStateException("AudioTrack.write=" + written);
                    sent += written;
                }
                Thread.sleep(DURATION_MS + 150);
                int played = track.getPlaybackHeadPosition();
                String result = "Speaker test: sent " + (sent / 2) + " frames, played "
                    + played + " frames. Did you hear the short tone? "
                    + "If yes, Android playback works and the game mixer needs investigation.";
                Log.i("NightlaneAudioProbe", result);
                report.accept(result);
            } catch (Throwable error) {
                String message = "Speaker test failed: " + error;
                Log.e("NightlaneAudioProbe", message, error);
                report.accept(message);
            } finally {
                if (track != null) {
                    try { track.stop(); } catch (IllegalStateException ignored) {}
                    track.release();
                }
            }
        }, "nightlane-audio-probe").start();
    }
}
