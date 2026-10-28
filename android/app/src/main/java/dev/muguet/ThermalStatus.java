package dev.muguet;

import android.content.Context;
import android.os.PowerManager;

// Android API 29+ exposes thermal *pressure / throttling severity*, not an
// actual Celsius sensor reading. Keep sampling on the existing 1 Hz HUD tick:
// no sensor polling thread, wake lock, GPU query or per-frame Binder call.
final class ThermalStatus {
    private final PowerManager power;

    ThermalStatus(Context context) {
        power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    String read() {
        if (power == null) return "Unknown";
        try {
            return label(power.getCurrentThermalStatus());
        } catch (RuntimeException | LinkageError unavailable) {
            // Some vendor builds do not supply the Android thermal service.
            return "Unknown";
        }
    }

    static String label(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "Normal";
            case PowerManager.THERMAL_STATUS_LIGHT: return "Light";
            case PowerManager.THERMAL_STATUS_MODERATE: return "Moderate";
            case PowerManager.THERMAL_STATUS_SEVERE: return "Severe";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "Critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "Emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "Shutdown";
            default: return "Unknown";
        }
    }
}
