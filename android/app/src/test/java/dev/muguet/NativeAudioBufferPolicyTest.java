package dev.muguet;

import static org.junit.Assert.*;
import org.junit.Test;

public final class NativeAudioBufferPolicyTest {
    @Test public void startupWatermarkRespectsActualRingCapacity() {
        assertEquals(0, NativeAudioBufferPolicy.startupWatermark(0));
        assertEquals(341, NativeAudioBufferPolicy.startupWatermark(1024));
        assertEquals(1024, NativeAudioBufferPolicy.startupWatermark(3072));
        assertEquals(3072, NativeAudioBufferPolicy.startupWatermark(16384));
    }

    @Test public void packedRingInfoIsClampedAndAccurate() {
        long state = (8192L << 32) | 2048L;
        assertEquals(2048, NativeAudioBufferPolicy.boundedAvailable(state));
        assertEquals(8192, NativeAudioBufferPolicy.ringCapacity(state));
        assertEquals(8192, NativeAudioBufferPolicy.boundedAvailable((8192L << 32) | 10000L));
    }

    @Test public void deviceSizedStartupBufferAvoidsZeroPlaybackHeadDeadlock() {
        assertEquals(8192, NativeAudioBufferPolicy.outputQueueTarget(8811, false));
        assertEquals(4096, NativeAudioBufferPolicy.outputQueueTarget(8811, true));
        assertEquals(4096, NativeAudioBufferPolicy.outputQueueTarget(4096, false));
        assertEquals(2048, NativeAudioBufferPolicy.outputQueueTarget(2048, false));
        assertEquals(2048, NativeAudioBufferPolicy.outputQueueTarget(2048, true));
        assertEquals(4096, NativeAudioBufferPolicy.outputQueueTarget(0, false));
    }

    @Test public void outputMeterCountsOnlyAudibleWrittenStereoFrames() {
        short[] pcm = new short[] {0, 0, 9, -16, 17, 0, 0, -18, 0, 0};
        assertEquals(2, NativeAudioBufferPolicy.countAudibleFrames(pcm, 0, pcm.length));
        assertEquals(1, NativeAudioBufferPolicy.countAudibleFrames(pcm, 4, 2));
        assertEquals(0, NativeAudioBufferPolicy.countAudibleFrames(pcm, 0, 4));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidPcmRangesFailFast() {
        NativeAudioBufferPolicy.countAudibleFrames(new short[2], 2, 4);
    }

    @Test public void onlyWaitWhenOutputHasRoomToAbsorbStall() {
        assertTrue(NativeAudioBufferPolicy.waitForProducer(0, 2000, false));
        assertFalse(NativeAudioBufferPolicy.waitForProducer(0, 256, false));
        assertFalse(NativeAudioBufferPolicy.waitForProducer(1024, 2000, false));
        assertTrue(NativeAudioBufferPolicy.waitForProducer(512, 0, true));
        assertFalse(NativeAudioBufferPolicy.waitForProducer(1024, 0, true));
        assertTrue(NativeAudioBufferPolicy.waitForInitialSamples(0, 0, 30000));
        assertTrue(NativeAudioBufferPolicy.waitForInitialSamples(0, 16384, 1499));
        assertFalse(NativeAudioBufferPolicy.waitForInitialSamples(0, 16384, 1500));
        assertFalse(NativeAudioBufferPolicy.waitForInitialSamples(256, 16384, 100));
        assertFalse(NativeAudioBufferPolicy.shouldStartAudioTrack(0, 0, 5000));
        assertFalse(NativeAudioBufferPolicy.shouldStartAudioTrack(0, 16384, 1499));
        assertTrue(NativeAudioBufferPolicy.shouldStartAudioTrack(0, 16384, 1500));
        assertTrue(NativeAudioBufferPolicy.shouldStartAudioTrack(3072, 16384, 0));
        assertFalse(NativeAudioBufferPolicy.shouldStartAudioTrack(2048, 16384, 0));
        assertEquals(2048, NativeAudioBufferPolicy.queueDepth(4096, 2048));
        assertEquals(0, NativeAudioBufferPolicy.queueDepth(2048, 4096));
    }
}
