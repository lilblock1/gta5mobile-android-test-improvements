package dev.muguet;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Foreground-only, resumable installer. No network activity until Play requests installation. */
final class GameDataInstaller {
    static final String MANIFEST_URL = "https://pub-259a383567d14badb0df56ab4afe96f9.r2.dev/r2_manifest.json";
    static final String BUILD = "8b0b5899ed";
    static final String WASM_SHA256 = "11ca8d2c04c5e843d18ff4aea4899d72c86973c6b031df334e67c446b2ae83e0";
    private static final long GIB = 1024L * 1024 * 1024;

    interface Progress { void update(String message); }
    interface Cancel { boolean requested(); }
    interface Resources { void copyTo(File gameRoot) throws Exception; }
    interface Transport { Reply open(URI uri, long offset) throws IOException; }

    static final class Reply implements AutoCloseable {
        final int status;
        final String range;
        final InputStream body;
        final Runnable disconnect;
        Reply(int status, String range, InputStream body, Runnable disconnect) {
            this.status = status; this.range = range; this.body = body; this.disconnect = disconnect;
        }
        public void close() throws IOException { try { body.close(); } finally { disconnect.run(); } }
    }

    static final Transport HTTPS = (uri, offset) -> {
        requireHttps(uri);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
        try {
            int status = connection.getResponseCode();
            if (status != 200 && status != 206) throw new IOException("Download server returned HTTP " + status);
            return new Reply(status, connection.getHeaderField("Content-Range"), connection.getInputStream(), connection::disconnect);
        } catch (IOException e) { connection.disconnect(); throw e; }
    };

    static final class Chunk {
        final String filename, sha256;
        final long compressed, expanded;
        Chunk(JSONObject json) throws IOException, org.json.JSONException {
            filename = json.getString("filename");
            sha256 = json.getString("sha256").toLowerCase(Locale.ROOT);
            compressed = json.getLong("compressedSize"); expanded = json.getLong("uncompressedSize");
            if (!filename.matches("[A-Za-z0-9_-]+\\.zip") || !sha256.matches("[a-f0-9]{64}")
                    || compressed <= 0 || compressed > 8 * GIB || expanded <= 0 || expanded > 16 * GIB)
                throw new IOException("Invalid download chunk metadata");
        }
    }

    static final class Plan {
        final List<Chunk> chunks = new ArrayList<>();
        final String fingerprint;
        final long expanded;
        Plan(byte[] bytes) throws Exception {
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!BUILD.equals(json.getString("buildId"))) throw new IOException("Download build does not match this engine");
            JSONArray list = json.getJSONArray("chunks");
            if (list.length() < 1 || list.length() > 128 || list.length() != json.getInt("chunkCount"))
                throw new IOException("Invalid chunk count");
            Set<String> names = new HashSet<>();
            Set<Integer> ids = new HashSet<>();
            long sum = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                Chunk chunk = new Chunk(item);
                if (!names.add(chunk.filename) || !ids.add(item.getInt("id"))) throw new IOException("Duplicate download chunk");
                chunks.add(chunk);
                sum = Math.addExact(sum, chunk.expanded);
            }
            expanded = sum;
            if (expanded > 128 * GIB || expanded != json.getLong("totalSize")) throw new IOException("Invalid total download size");
            fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
    }

    private final Transport transport;
    private final Cancel cancel;
    private final Progress progress;
    GameDataInstaller(Transport transport, Cancel cancel, Progress progress) {
        this.transport = transport; this.cancel = cancel; this.progress = progress;
    }

    private void checkCancelled() throws IOException {
        if (cancel.requested() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download paused. Tap Play to resume.");
    }

    void install(File privateDir, Resources resources) throws Exception {
        // A recreated activity must not race a previous worker's final network read.
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(privateDir, ".game-install.lock"), "rw")) {
            java.nio.channels.FileLock lock;
            try { lock = lockFile.getChannel().tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException e) { throw new IOException("Setup is still stopping. Wait a moment, then tap Play.", e); }
            if (lock == null) throw new IOException("Setup is already running. Wait a moment, then tap Play.");
            try (java.nio.channels.FileLock held = lock) { installLocked(privateDir, resources); }
        }
    }

    private void installLocked(File privateDir, Resources resources) throws Exception {
        checkCancelled();
        File dest = new File(privateDir, "game");
        if (dest.exists()) throw new IOException("An imported game folder already exists. Select it or remove the incomplete import before setup.");
        URI manifestUri = URI.create(MANIFEST_URL);
        byte[] bytes;
        progress.update("Checking download manifest…");
        try (Reply reply = transport.open(manifestUri, 0)) {
            if (reply.status != 200) throw new IOException("Invalid manifest response");
            bytes = readBounded(reply.body, 1024 * 1024);
        }
        Plan plan = new Plan(bytes);
        File work = new File(privateDir, "game-download");
        File identity = new File(work, "manifest.sha256");
        String previous = identity.isFile() ? new String(Files.readAllBytes(identity.toPath()), StandardCharsets.UTF_8) : "";
        if (!plan.fingerprint.equals(previous)) {
            removeTree(work);
            mkdir(work);
            Files.write(identity.toPath(), plan.fingerprint.getBytes(StandardCharsets.UTF_8));
        }
        File staging = new File(work, "staging");
        mkdir(staging);
        // A private build may bundle the matching resources from a user-supplied APK.
        // Fail before downloading large archives when they are unavailable.
        progress.update("Preparing engine resources…");
        resources.copyTo(staging);
        validateEngine(staging);
        progress.update("Checking saved setup progress…");
        long remaining = 0, largest = 0;
        Set<String> completed = new HashSet<>();
        for (Chunk chunk : plan.chunks) {
            if (done(work, chunk)) completed.add(chunk.filename);
            else { remaining += chunk.expanded; largest = Math.max(largest, chunk.compressed); }
        }
        if (privateDir.getUsableSpace() < remaining + largest + 256L * 1024 * 1024)
            throw new IOException("Not enough free storage. Setup needs about " + ((remaining + largest + GIB - 1) / GIB) + " GB more free space.");
        File data = new File(staging, "data");
        mkdir(data);
        for (int i = 0; i < plan.chunks.size(); i++) {
            checkCancelled();
            Chunk chunk = plan.chunks.get(i);
            if (completed.contains(chunk.filename)) continue;
            File archive = new File(work, chunk.filename + ".part");
            download(manifestUri.resolve(chunk.filename), chunk, archive, i + 1, plan.chunks.size());
            progress.update("Extracting part " + (i + 1) + " of " + plan.chunks.size() + "… Keep the app open.");
            JSONArray receipt = extract(archive, data, chunk.expanded);
            JSONObject record = new JSONObject().put("sha256", chunk.sha256).put("files", receipt);
            Files.write(new File(work, chunk.filename + ".done").toPath(), record.toString().getBytes(StandardCharsets.UTF_8));
            Files.delete(archive.toPath());
        }
        validateData(data);
        checkCancelled();
        // Never replace an existing import, saves, or user-selected folder.
        if (dest.exists()) throw new IOException("An imported game folder already exists. Select it or remove the incomplete import before setup.");
        if (!staging.renameTo(dest)) throw new IOException("Could not finish installation; tap Play to retry");
        removeTree(work);
        progress.update("Game data ready.");
    }

    boolean done(File work, Chunk chunk) throws Exception {
        File marker = new File(work, chunk.filename + ".done");
        if (!marker.isFile()) return false;
        JSONObject record;
        try (InputStream in = new FileInputStream(marker)) {
            record = new JSONObject(new String(readBounded(in, 8 * 1024 * 1024), StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) { return false; }
        if (!chunk.sha256.equals(record.optString("sha256"))) return false;
        JSONArray files = record.optJSONArray("files");
        if (files == null || files.length() == 0) return false;
        byte[] buffer = new byte[128 * 1024];
        for (int i = 0; i < files.length(); i++) {
            checkCancelled();
            JSONArray row = files.getJSONArray(i);
            File target = safeTarget(new File(work, "staging/data"), row.getString(0));
            if (!target.isFile() || target.length() != row.getLong(1)) return false;
            CRC32 crc = new CRC32();
            try (InputStream in = new FileInputStream(target)) {
                int n;
                while ((n = in.read(buffer)) != -1) { checkCancelled(); crc.update(buffer, 0, n); }
            }
            if (crc.getValue() != row.getLong(2)) return false;
        }
        return true;
    }

    void download(URI uri, Chunk chunk, File part, int index, int count) throws Exception {
        checkCancelled();
        long offset = part.isFile() ? part.length() : 0;
        if (offset > chunk.compressed) { Files.delete(part.toPath()); offset = 0; }
        if (offset < chunk.compressed) {
            try (Reply reply = transport.open(uri, offset)) {
                boolean append = offset > 0 && reply.status == 206;
                if (reply.status == 206) {
                    String expected = "bytes " + offset + "-" + (chunk.compressed - 1) + "/" + chunk.compressed;
                    if (!expected.equals(reply.range)) throw new IOException("Invalid resumed download range");
                } else if (reply.status != 200) throw new IOException("Invalid download response");
                if (!append) offset = 0; // Server ignored Range: replace, never append a full ZIP.
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(part, append), 128 * 1024)) {
                    byte[] buffer = new byte[128 * 1024];
                    long at = offset, lastUpdate = 0;
                    int n;
                    while ((n = reply.body.read(buffer)) != -1) {
                        checkCancelled();
                        if (n > chunk.compressed - at) throw new IOException("Download exceeds expected size");
                        out.write(buffer, 0, n); at += n;
                        long now = System.nanoTime();
                        if (now - lastUpdate > 500_000_000L) {
                            lastUpdate = now;
                            progress.update("Downloading part " + index + " of " + count + ": " + (at >> 20) + " / " + (chunk.compressed >> 20) + " MB. Keep the app open.");
                        }
                    }
                    if (at != chunk.compressed) throw new EOFException("Download interrupted; tap Play to resume");
                }
            }
        }
        progress.update("Verifying part " + index + " of " + count + "…");
        if (!chunk.sha256.equals(digest(part))) {
            Files.delete(part.toPath());
            throw new IOException("SHA-256 verification failed; tap Play to download this part again");
        }
    }

    JSONArray extract(File archive, File data, long expectedSize) throws Exception {
        long total = 0;
        JSONArray receipt = new JSONArray();
        Set<String> names = new HashSet<>();
        byte[] buffer = new byte[128 * 1024];
        try (ZipFile zip = new ZipFile(archive)) {
            if (zip.size() > 100000) throw new IOException("Too many archive entries");
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkCancelled();
                ZipEntry entry = entries.nextElement();
                File target = safeTarget(data, entry.getName());
                if (entry.isDirectory()) { mkdir(target); continue; }
                if (!names.add(target.getCanonicalPath()) || entry.getSize() < 0 || entry.getSize() > expectedSize - total)
                    throw new IOException("Invalid archive size or duplicate entry");
                mkdir(target.getParentFile());
                CRC32 crc = new CRC32();
                long written = 0;
                try (InputStream in = zip.getInputStream(entry); OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 128 * 1024)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        checkCancelled();
                        if (n > entry.getSize() - written) throw new IOException("Archive entry exceeds declared size");
                        out.write(buffer, 0, n); crc.update(buffer, 0, n); written += n;
                    }
                }
                if (written != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("Archive checksum or size mismatch");
                receipt.put(new JSONArray().put(entry.getName()).put(written).put(crc.getValue()));
                total += written;
            }
        }
        if (total != expectedSize) throw new IOException("Extracted size does not match manifest");
        return receipt;
    }

    static File safeTarget(File root, String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")) throw new IOException("Unsafe archive path");
        for (String part : name.split("/")) if (part.equals("..") || part.equals(".")) throw new IOException("Unsafe archive path");
        File file = new File(root, name).getCanonicalFile();
        if (!file.toPath().startsWith(root.getCanonicalFile().toPath()) || file.equals(root.getCanonicalFile())) throw new IOException("Archive path escapes data folder");
        return file;
    }

    void validateData(File data) throws Exception {
        byte[] bytes;
        try (InputStream in = new FileInputStream(new File(data, "manifest.json"))) { bytes = readBounded(in, 8 * 1024 * 1024); }
        JSONObject manifest = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        JSONArray files = manifest.getJSONArray("files");
        if (files.length() == 0) throw new IOException("Empty game-data manifest");
        boolean corrected = false;
        for (int i = 0; i < files.length(); i++) {
            checkCancelled();
            JSONArray row = files.getJSONArray(i);
            String name = row.getString(0);
            File file = safeTarget(data, name);
            long declared = row.getLong(1);
            if (declared < 0 || !file.isFile()) throw new IOException("Game data missing or wrong size: " + name);
            if (file.length() != declared) {
                // The reviewed mirror has two modified XML configs whose original
                // lengths were left in manifest.json. Correct metadata only for
                // those byte-identical files; never hide an arbitrary mismatch.
                String knownHash = expectedConfigHash(name, declared, file.length());
                if (knownHash == null || !knownHash.equals(digest(file)))
                    throw new IOException("Game data missing or wrong size: " + name);
                row.put(1, file.length());
                corrected = true;
            }
        }
        if (corrected) Files.write(new File(data, "manifest.json").toPath(), manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String expectedConfigHash(String name, long declared, long actual) {
        if ("common/data/gameconfig.xml".equals(name) && declared == 58601 && actual == 58590)
            return "bcf4fbfcb120b14f084a795de657347d61583ae9a7793338f8c30baacc91c374";
        if ("x64/data/settings.xml".equals(name) && declared == 2494 && actual == 2506)
            return "94313e34fafc622fe5d1dee711075a407d03c0d59c82a98fd4f2130548d20ccc";
        return null;
    }

    private void validateEngine(File root) throws Exception {
        File build = new File(root, "b/" + BUILD);
        if (!new File(build, "shaders/index.json").isFile() || !WASM_SHA256.equals(digest(new File(build, "game.wasm")) ))
            throw new IOException("Matching engine resources are missing. Import your compatible game ZIP or use a build with engine resources.");
    }

    private String digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024]; int n;
            while ((n = in.read(buffer)) != -1) { checkCancelled(); hash.update(buffer, 0, n); }
        }
        return hex(hash.digest());
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    static byte[] readBounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (n > limit - out.size()) throw new IOException("Metadata exceeds size limit");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    static void requireHttps(URI uri) throws IOException {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IOException("Downloads require HTTPS");
    }
    private static void mkdir(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Could not create install folder");
    }
    private static void removeTree(File root) throws IOException {
        if (!root.exists()) return;
        if (Files.isSymbolicLink(root.toPath())) throw new IOException("Unexpected link in install folder");
        File[] children = root.listFiles();
        if (children != null) for (File child : children) removeTree(child);
        Files.delete(root.toPath());
    }
}
