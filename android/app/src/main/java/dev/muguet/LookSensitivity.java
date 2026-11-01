package dev.muguet;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

// Touch look gain. The launcher writes config.json before Play, and the :game
// process reads it here rather than from Rust, whose android_main has not run yet.
// Clamped so a damaged config can never leave touch look unusable.
final class LookSensitivity {
    static final float MIN = 0.25f, MAX = 12f, DEFAULT = 1f;
    static final int MAX_PROGRESS = 48;
    private LookSensitivity() {}

    static float fromConfig(File config) {
        if (!config.isFile()) return DEFAULT;
        try {
            JSONObject json = new JSONObject(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8));
            double value = json.optDouble("touch_sensitivity", DEFAULT);
            if (!Double.isFinite(value)) return DEFAULT;
            return (float) Math.max(MIN, Math.min(MAX, value));
        } catch (Exception e) { return DEFAULT; }
    }

    // 1..48 maps to 0.25x..12x; 4/4 = 1x preserves the previous default.
    static float fromSlider(int progress) {
        return Math.max(1, Math.min(MAX_PROGRESS, progress)) / 4.0f;
    }
    static int toSlider(float gain) {
        if (!Float.isFinite(gain)) gain = DEFAULT;
        return Math.max(1, Math.min(MAX_PROGRESS, Math.round(gain * 4)));
    }
}
