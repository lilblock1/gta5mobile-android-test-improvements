package dev.muguet;

import android.content.Context;
import android.util.JsonReader;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small, cancellable launch-time preparation. The only game-data I/O is
 * read-only. Repeated reads warm Android's file page cache: no proprietary
 * game archives are copied or retained, no 2GB RAM reservation is made.
 *
 * Shader sources are CPU-parsed with Naga (Rust JNI) before entering the
 * engine. Full graphics pipelines depend on actual game render states; the
 * Vulkan driver cache created by gpu.rs preserves those between sessions.
 */
final class NightlanePrecache {
    interface Listener { void update(int percent, String detail); }
    static final int MAX_SHADERS = 128;
    static final int MAX_ASSET_FILES = 96;
    static final int ASSET_PREFIX_BYTES = 512 * 1024;
    static final long MAX_WARMED_BYTES = 48L * 1024 * 1024;
    private static final String BUILD = "8b0b5899ed";

    private NightlanePrecache() {}

    static void prepare(Context context, File gameRoot, AtomicBoolean skip, Listener listener,
                        ShaderCheck check) {
        listener.update(0, "Locating game shaders");
        File shaders = new File(gameRoot, "b/" + BUILD + "/shaders");
        File index = new File(shaders, "index.json");
        try {
            if (!index.isFile()) throw new IOException("Shader index is missing");
            File markerDir = new File(context.getCacheDir(), "nightlane-prelaunch");
            String fingerprint = fileDigest(index);
            File marker = new File(markerDir, "shader-" + fingerprint + ".ready");
            boolean indexPrepared = marker.isFile();
            ArrayList<String> shadersToCheck = readShaderNames(index, MAX_SHADERS);
            int passed = 0;
            for (int i = 0; i < shadersToCheck.size() && !skip.get(); i++) {
                File source = new File(shaders, shadersToCheck.get(i) + ".wgsl");
                // A prior successful parse of this exact index lets later
                // launches perform metadata checks instead of parsing again.
                if (source.isFile() && (indexPrepared || check.verify(source.getAbsolutePath()))) passed++;
                int progress = 5 + (int) (43L * (i + 1) / Math.max(1, shadersToCheck.size()));
                if (i == 0 || i % 5 == 0 || i == shadersToCheck.size() - 1)
                    listener.update(progress, indexPrepared
                        ? "Reusing prepared shader sources (" + (i + 1) + "/" + shadersToCheck.size() + ")"
                        : "Checking shader sources (" + (i + 1) + "/" + shadersToCheck.size() + ")");
            }
            if (!skip.get() && !indexPrepared && passed == shadersToCheck.size() && passed > 0) {
                if (markerDir.isDirectory() || markerDir.mkdirs()) {
                    Files.write(marker.toPath(), Integer.toString(passed).getBytes(StandardCharsets.UTF_8));
                    // Only one marker belongs to an engine index. Stale
                    // revisions are removed; the GPU binary cache has its own
                    // wgpu/driver validation and size bound.
                    File[] old = markerDir.listFiles();
                    if (old != null) for (File file : old) {
                        if (file.getName().startsWith("shader-") && file.getName().endsWith(".ready")
                            && !file.equals(marker)) file.delete();
                    }
                }
            }
            listener.update(50, "Shader preparation: " + passed + "/" + shadersToCheck.size()
                + "; checking nearby game assets");
        } catch (Exception error) {
            listener.update(50, "Shader preparation unavailable: " + error.getMessage()
                + " (using normal engine loading)");
        }

        if (skip.get()) { listener.update(100, "Preparation skipped"); return; }
        try {
            warmAssetPages(gameRoot, skip, listener);
        } catch (Exception error) {
            listener.update(95, "File pre-cache unavailable: " + error.getMessage()
                + " (normal game streaming remains active)");
        }
        listener.update(100, skip.get() ? "Preparation skipped" : "Ready to launch native engine");
    }

    interface ShaderCheck { boolean verify(String sourceFile); }

    static String fileDigest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = input.read(buf)) > 0) digest.update(buf, 0, n);
        }
        StringBuilder result = new StringBuilder();
        byte[] bytes = digest.digest();
        for (int i = 0; i < 12; i++) result.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
        return result.toString();
    }

    private static ArrayList<String> readShaderNames(File index, int max) throws IOException {
        ArrayList<String> names = new ArrayList<>();
        try (JsonReader reader = new JsonReader(new BufferedReader(new FileReader(index)))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                reader.skipValue();
                if (names.size() < 4096 && name.matches("[a-zA-Z0-9_-]{4,96}")) names.add(name);
            }
            reader.endObject();
        }
        names.sort(String::compareTo);
        if (names.size() > max) names.subList(max, names.size()).clear();
        return names;
    }

    private static final class Candidate {
        final File file;
        final int weight;
        final long bytes;
        Candidate(File file, int weight, long bytes) {
            this.file = file; this.weight = weight; this.bytes = bytes;
        }
    }

    private static int priority(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (!p.endsWith(".rpf") && !p.endsWith(".dat") && !p.endsWith(".awc")) return 0;
        if (p.startsWith("update/")) return 9;
        if (p.contains("common.rpf")) return 8;
        if (p.startsWith("x64")) return 6;
        if (p.endsWith(".rpf")) return 4;
        return 1;
    }

    private static ArrayList<Candidate> selectAssets(File gameRoot) throws IOException {
        ArrayList<Candidate> candidates = new ArrayList<>();
        File manifest = new File(gameRoot, "data/manifest.json");
        // The native HTTPFS root is the manifest's parent (gameRoot/data),
        // not gameRoot itself. Use precisely the same relative path layout.
        // Canonical paths keep malformed manifests inside the chosen game data.
        File dataRoot = manifest.getParentFile();
        String base = dataRoot.getCanonicalPath() + File.separator;
        try (JsonReader json = new JsonReader(new BufferedReader(new FileReader(manifest)))) {
            json.beginObject();
            while (json.hasNext()) {
                String key = json.nextName();
                if (!"files".equals(key)) { json.skipValue(); continue; }
                json.beginArray();
                while (json.hasNext()) {
                    json.beginArray();
                    String path = json.nextString();
                    while (json.hasNext()) json.skipValue();
                    json.endArray();
                    int rank = priority(path);
                    if (rank == 0 || candidates.size() >= 512) continue;
                    File file = new File(dataRoot, path);
                    if (!file.getCanonicalPath().startsWith(base) || !file.isFile()) continue;
                    long read = Math.min(file.length(), ASSET_PREFIX_BYTES);
                    if (read > 0) candidates.add(new Candidate(file, rank, read));
                }
                json.endArray();
            }
            json.endObject();
        }
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.weight).reversed()
            .thenComparing(c -> c.file.getName()));
        if (candidates.size() > MAX_ASSET_FILES)
            candidates.subList(MAX_ASSET_FILES, candidates.size()).clear();
        return candidates;
    }

    private static void warmAssetPages(File gameRoot, AtomicBoolean skip, Listener listener)
            throws IOException {
        ArrayList<Candidate> files = selectAssets(gameRoot);
        long total = 0;
        for (Candidate file : files) {
            if (total + file.bytes > MAX_WARMED_BYTES) break;
            total += file.bytes;
        }
        if (total == 0) { listener.update(95, "No local archive pages to pre-cache"); return; }
        byte[] buffer = new byte[64 * 1024];
        long complete = 0;
        for (Candidate candidate : files) {
            if (skip.get() || complete >= total || complete + candidate.bytes > total) break;
            long remaining = candidate.bytes;
            try (InputStream input = new BufferedInputStream(new FileInputStream(candidate.file))) {
                while (remaining > 0 && !skip.get()) {
                    int n = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                    if (n < 0) break;
                    remaining -= n;
                }
            } catch (IOException ignored) {
                // Game file access problems are handled by the actual engine
                // and installer; an optional warmup must never prevent launch.
            }
            complete += candidate.bytes;
            int pct = 50 + (int) (45 * complete / total);
            listener.update(Math.min(95, pct),
                String.format(Locale.ROOT, "Pre-caching game files: %.1f / %.1f MiB",
                    complete / 1048576.0, total / 1048576.0));
        }
        listener.update(95, skip.get() ? "File pre-cache skipped" : "Game file pre-cache prepared");
    }
}
