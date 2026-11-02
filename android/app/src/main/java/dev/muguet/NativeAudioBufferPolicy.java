package dev.muguet;

/**
 * Bounded native audio buffering policy. All quantities are stereo *frames*,
 * not shorts or bytes. Designed to protect against short game-engine stalls
 * without allowing an unbounded audio/video synchronization delay.
 */
final class NativeAudioBufferPolicy {
    static final int BLOCK_FRAMES = 1024;
    static final int TARGET_QUEUED_FRAMES = 4096;
    static final int MIN_QUEUED_FRAMES = 1536;
    static final int MAX_STARTUP_WAIT_MS = 1500;

    private NativeAudioBufferPolicy() {}

    static int startupWatermark(int ringCapacity) {
        if (ringCapacity <= 0) return 0;
        // Do not wait for an impossible watermark on devices/game versions
        // that expose only a small circular audio mixer ring.
        return Math.min(3072, Math.max(1, ringCapacity / 3));
    }

    static int boundedAvailable(long packed) {
        int capacity = (int) (packed >>> 32);
        int available = (int) packed;
        if (capacity <= 0 || available < 0) return 0;
        return Math.min(capacity, available);
    }

    static int ringCapacity(long packed) {
        return (int) (packed >>> 32);
    }

    static boolean waitForProducer(int available, long queued, boolean priming) {
        if (priming) return available < BLOCK_FRAMES;
        // Keep reading immediately if the output queue is running low.
        // Otherwise let the game catch up rather than turn every empty
        // mixer read into 1024 frames of silence.
        return available < BLOCK_FRAMES && queued >= MIN_QUEUED_FRAMES;
    }

    // Emulate browser AudioWorklet startup: after a bounded grace period,
    // begin callback delivery with silence even if the game mixer is empty.
    static boolean waitForInitialSamples(int available, int capacity, long elapsedMs) {
        return available == 0 &&
            (capacity <= 0 || elapsedMs < MAX_STARTUP_WAIT_MS);
    }

    static boolean shouldStartAudioTrack(int available, int capacity, long elapsedMs) {
        if (capacity <= 0) return false;
        // Begin playback as soon as a genuine startup watermark is present,
        // or after the bounded grace period even if the producer is silent.
        return available >= startupWatermark(capacity) ||
            elapsedMs >= MAX_STARTUP_WAIT_MS;
    }

    // Some Android routes do not advance the playback head until enough PCM
    // has been written to start the hardware stream. Limiting initial writes
    // to 4096 frames on an 8811-frame device can leave the playback head at 0
    // forever. Until we see real head movement, allow one bounded device-sized
    // prefill; after that, return to the low-latency 4096-frame target.
    static int outputQueueTarget(int deviceCapacityFrames, boolean playbackAdvanced) {
        if (deviceCapacityFrames <= 0) return TARGET_QUEUED_FRAMES;
        int wanted = playbackAdvanced ? TARGET_QUEUED_FRAMES : 8192;
        return Math.max(1, Math.min(wanted, deviceCapacityFrames));
    }

    // How many frames in an interleaved stereo PCM16 range have a meaningful
    // signal? Only counts actual output writes, not a ring producer index.
    static int countAudibleFrames(short[] pcm, int offsetSamples, int countSamples) {
        if (pcm == null || offsetSamples < 0 || countSamples < 0
                || offsetSamples > pcm.length || countSamples > pcm.length - offsetSamples)
            throw new IllegalArgumentException("Invalid PCM range");
        int audible = 0;
        for (int i = offsetSamples; i + 1 < offsetSamples + countSamples; i += 2) {
            if (Math.abs((int)pcm[i]) > 16 || Math.abs((int)pcm[i + 1]) > 16) audible++;
        }
        return audible;
    }

    static long queueDepth(long written, long played) {
        // AudioTrack's head uses a wrapping unsigned 32-bit frame count.
        // We only retain a bounded total; after a full wrap the caller
        // should widen the playback head monotonically.
        return Math.max(0L, written - played);
    }
}
