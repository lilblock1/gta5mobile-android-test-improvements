package dev.muguet;

import static org.junit.Assert.*;
import org.junit.Test;

public final class AudioOutputProbeTest {
    @Test public void shortToneIsStereoAndActuallyContainsAudio() {
        short[] samples = AudioOutputProbe.toneSamples(48000, 500);
        assertEquals(48000, samples.length);
        boolean nonzero = false;
        int peak = 0;
        for (int i = 0; i < samples.length; i += 2) {
            assertEquals(samples[i], samples[i + 1]);
            peak = Math.max(peak, Math.abs((int)samples[i]));
            if (samples[i] != 0) nonzero = true;
        }
        assertTrue(nonzero);
        assertTrue(peak > 3000);
        assertTrue(peak < 6000); // Quiet enough to avoid a startling test tone.
        assertEquals(0, samples[0]);
        assertEquals(0, samples[samples.length - 1]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectInvalidDuration() {
        AudioOutputProbe.toneSamples(48000, 10000);
    }
}
