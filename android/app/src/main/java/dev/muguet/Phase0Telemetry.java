package dev.muguet;

import android.app.Activity;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;
import android.os.SystemClock;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import org.json.JSONObject;

/** Low-frequency, best-effort Phase 0 samples. No GPU allocations or filesystem writes. */
final class Phase0Telemetry {
    private static final String TAG = "NightlanePhase0";
    private long lastSampleMs;
    private final Activity activity;
    Phase0Telemetry(Activity activity) { this.activity = activity; }

    void sample() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSampleMs < 5000L) return;
        lastSampleMs = now;
        try {
            Debug.MemoryInfo memory = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memory);
            JSONObject row = new JSONObject();
            row.put("schema", 1);
            row.put("monotonic_ms", now);
            row.put("pss_kib", memory.getTotalPss());
            row.put("private_dirty_kib", memory.getTotalPrivateDirty());
            row.put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize());
            row.put("native_heap_size_bytes", Debug.getNativeHeapSize());
            PowerManager power = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            if (power != null && Build.VERSION.SDK_INT >= 29) {
                row.put("thermal_status", power.getCurrentThermalStatus());
            }
            if (power != null && Build.VERSION.SDK_INT >= 30) {
                try {
                    float headroom = power.getThermalHeadroom(10);
                    if (!Float.isNaN(headroom) && !Float.isInfinite(headroom)) {
                        row.put("thermal_headroom_10s", headroom);
                    }
                } catch (RuntimeException ignored) { }
            }
            Intent battery = activity.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int tempTenthsC = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                if (tempTenthsC >= 0) row.put("battery_temperature_c", tempTenthsC / 10.0);
                row.put("battery_level", battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1));
            }
            Log.i(TAG, row.toString());
        } catch (Exception e) {
            Log.w(TAG, "sample unavailable: " + e.getClass().getSimpleName());
        }
    }
}
