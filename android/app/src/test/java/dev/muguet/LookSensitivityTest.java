package dev.muguet;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public final class LookSensitivityTest {
    @Test public void sliderCoversTwelveTimesAndPreservesOriginalDefault() {
        assertEquals(0.25f, LookSensitivity.fromSlider(1), 0.00001f);
        assertEquals(1.0f, LookSensitivity.fromSlider(4), 0.00001f);
        assertEquals(4.0f, LookSensitivity.fromSlider(16), 0.00001f);
        assertEquals(12.0f, LookSensitivity.fromSlider(48), 0.00001f);
        assertEquals(12.0f, LookSensitivity.fromSlider(1000), 0.00001f);
        assertEquals(0.25f, LookSensitivity.fromSlider(0), 0.00001f);
        assertEquals(4, LookSensitivity.toSlider(LookSensitivity.DEFAULT));
        assertEquals(48, LookSensitivity.toSlider(12.0f));
        assertEquals(48, LookSensitivity.toSlider(100.0f));
        assertEquals(4, LookSensitivity.toSlider(Float.NaN));
    }

    @Test public void nativeProcessReadsSavedSensitivityAndClampsCorruptValues() throws Exception {
        File config = File.createTempFile("nightlane-look", ".json");
        try {
            Files.write(config.toPath(),
                "{\"touch_sensitivity\":10.5}".getBytes(StandardCharsets.UTF_8));
            assertEquals(10.5f, LookSensitivity.fromConfig(config), 0.00001f);
            Files.write(config.toPath(),
                "{\"touch_sensitivity\":99}".getBytes(StandardCharsets.UTF_8));
            assertEquals(12.0f, LookSensitivity.fromConfig(config), 0.00001f);
            Files.write(config.toPath(),
                "{\"touch_sensitivity\":-42}".getBytes(StandardCharsets.UTF_8));
            assertEquals(0.25f, LookSensitivity.fromConfig(config), 0.00001f);
        } finally {
            config.delete();
        }
    }
}
