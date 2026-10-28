package dev.muguet;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Per-process Vulkan proxy initialization, before the first Vulkan instance.
 * A successful native probe is required; importing a ZIP alone is not success.
 */
public final class DriverBridge {
    private static final String TAG = "MuguetDriver";
    private static boolean initialized;
    private static String report = "Not initialized";
    private DriverBridge() {}
    private static native String nativeInit(String nativeDir, String driverDir,
                                            String libraryName, boolean custom, boolean fallback);
    private static native String nativeStatus();

    public static synchronized boolean prepare(Context context) {
        if (initialized) return true;
        String requested = DriverPackages.selected(context);
        if (!requested.startsWith("custom:")) {
            report = "System Vulkan driver selected";
            return true;
        }
        if (!DriverPackages.nativeProxyInstalled(context)) {
            report = "Custom Vulkan proxy libraries are missing";
            Log.e(TAG, report);
            return false;
        }
        try {
            String name = requested.substring("custom:".length());
            if (!name.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid driver name");
            File dir = new File(DriverPackages.root(context), name).getCanonicalFile();
            File root = DriverPackages.root(context).getCanonicalFile();
            if (!dir.getParentFile().equals(root)) throw new IllegalArgumentException("Driver directory escapes private storage");
            File meta = new File(dir, "meta.json");
            String library = "libvulkan_freedreno.so";
            if (meta.isFile()) {
                library = new JSONObject(new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8))
                    .optString("libraryName", library);
            }
            if (!library.matches("[A-Za-z0-9_.-]+\\.so") || !new File(dir, library).isFile())
                throw new IllegalArgumentException("Driver library is missing");
            String nativeDir = context.getApplicationInfo().nativeLibraryDir;
            System.load(new File(nativeDir, "libvulkan.so").getAbsolutePath());
            report = nativeInit(nativeDir, dir.getAbsolutePath(), library, true, true);
            JSONObject result = new JSONObject(report);
            initialized = result.optBoolean("ok", false);
            if (initialized && !"turnip".equals(result.optString("mode")))
                Log.w(TAG, "Custom driver rejected; native Vulkan proxy is using the stock driver: " + report);
            Log.i(TAG, report);
            return initialized;
        } catch (Throwable error) {
            report = "Custom Vulkan driver initialization failed: " + error;
            Log.e(TAG, report, error);
            return false;
        }
    }
    public static synchronized String status() { return report; }
}
