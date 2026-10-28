package dev.muguet;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Validates and stages AdrenoTools-format Vulkan driver packages.
 * This class does not activate a driver: activation requires the native Vulkan proxy.
 */
public final class DriverPackages {
    private DriverPackages() {}
    public static File root(Context context) { return new File(context.getFilesDir(), "drivers"); }
    public static File selection(Context context) { return new File(context.getFilesDir(), "driver.txt"); }
    public static String selected(Context context) {
        try { return Files.readString(selection(context).toPath(), StandardCharsets.UTF_8).trim(); }
        catch (IOException ignored) { return "system"; }
    }
    public static void select(Context context, String name) throws IOException {
        if (!"system".equals(name) && !name.matches("custom:[A-Za-z0-9_-]{1,64}"))
            throw new IOException("Invalid driver selection");
        Files.writeString(selection(context).toPath(), name, StandardCharsets.UTF_8);
    }
    /** Native Vulkan proxy must be present before a custom selection can launch. */
    public static boolean nativeProxyInstalled(Context context) {
        File dir = new File(context.getApplicationInfo().nativeLibraryDir);
        return new File(dir, "libvulkan.so").isFile()
            && new File(dir, "libmain_hook.so").isFile()
            && new File(dir, "libhook_impl.so").isFile();
    }
    public static String effectiveDriver(Context context) {
        String requested = selected(context);
        return requested.startsWith("custom:") && !nativeProxyInstalled(context)
            ? "system" : requested;
    }
    public static String importZip(Context context, Uri uri) throws IOException {
        File root = root(context);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create driver folder");
        File staging = new File(root, ".incoming");
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot stage driver");
        int entries = 0;
        long total = 0;
        String library = "libvulkan_freedreno.so";
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Cannot open driver ZIP");
                try (ZipInputStream zip = new ZipInputStream(input)) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (++entries > 128) throw new IOException("Too many files in driver ZIP");
                        String name = entry.getName().replace('\\', '/');
                        if (name.startsWith("/") || name.contains("../") || name.equals(".."))
                            throw new IOException("Unsafe ZIP path");
                        File dest = new File(staging, name);
                        String base = staging.getCanonicalPath() + File.separator;
                        if (!dest.getCanonicalPath().startsWith(base)) throw new IOException("Unsafe ZIP path");
                        if (entry.isDirectory()) { if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("Cannot create folder"); continue; }
                        File parent = dest.getParentFile();
                        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create folder");
                        try (FileOutputStream out = new FileOutputStream(dest)) {
                            byte[] buf = new byte[32768];
                            int n;
                            while ((n = zip.read(buf)) != -1) {
                                total += n;
                                if (total > 128L * 1024 * 1024) throw new IOException("Driver ZIP exceeds 128 MiB");
                                out.write(buf, 0, n);
                            }
                        }
                        zip.closeEntry();
                    }
                }
            }
            File meta = new File(staging, "meta.json");
            if (meta.isFile()) {
                JSONObject json = new JSONObject(Files.readString(meta.toPath(), StandardCharsets.UTF_8));
                library = json.optString("libraryName", library);
            }
            if (!library.matches("[A-Za-z0-9_.-]+\\.so") || !new File(staging, library).isFile())
                throw new IOException("Driver library missing: " + library);
            String name = "imported_" + Long.toHexString(System.currentTimeMillis());
            File target = new File(root, name);
            if (!staging.renameTo(target)) throw new IOException("Cannot finalize driver");
            return name;
        } catch (Exception error) {
            deleteTree(staging);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Invalid driver package", error);
        }
    }
    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (file.exists()) file.delete();
    }
}
