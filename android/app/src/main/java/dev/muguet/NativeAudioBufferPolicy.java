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

    static long queueDepth(long written, long played) {
        // AudioTrack's head uses a wrapping unsigned 32-bit frame count.
        // We only retain a bounded total; after a full wrap the caller
        // should widen the playback head monotonically.
        return Math.max(0L, written - played);
    }
}
