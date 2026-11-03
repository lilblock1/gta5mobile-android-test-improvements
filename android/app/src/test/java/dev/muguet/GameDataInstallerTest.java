package dev.muguet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class GameDataInstallerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final URI SOURCE = URI.create("https://example.com/fixture.zip");

    private static GameDataInstaller installer(GameDataInstaller.Transport transport) {
        return new GameDataInstaller(transport, () -> false, message -> {});
    }
    private static GameDataInstaller.Reply response(int code, String range, byte[] bytes) {
        return new GameDataInstaller.Reply(code, range, new ByteArrayInputStream(bytes), () -> {});
    }
    private static JSONObject chunk(byte[] content) throws Exception {
        return new JSONObject().put("id", 1).put("filename", "fixture.zip")
            .put("sha256", GameDataInstaller.hex(MessageDigest.getInstance("SHA-256").digest(content)))
            .put("compressedSize", content.length).put("uncompressedSize", 3);
    }
    private static byte[] manifest(JSONObject chunk) throws Exception {
        return new JSONObject().put("buildId", GameDataInstaller.BUILD).put("chunkCount", 1)
            .put("totalSize", 3).put("chunks", new JSONArray().put(chunk)).toString().getBytes(StandardCharsets.UTF_8);
    }
    private File zip(String name, byte[] data) throws Exception {
        File file = temp.newFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new ZipEntry(name)); out.write(data); out.closeEntry();
        }
        return file;
    }

    @Test public void acceptsReviewedManifestFormatAndRequiresHashes() throws Exception {
        JSONObject item = chunk(new byte[]{1, 2, 3});
        assertEquals(1, new GameDataInstaller.Plan(manifest(item)).chunks.size());
        item.put("sha256", "missing");
        assertThrows(IOException.class, () -> new GameDataInstaller.Plan(manifest(item)));
    }
    @Test public void rejectsDifferentBuildDuplicateChunksAndUnsafeFilenames() throws Exception {
        JSONObject item = chunk(new byte[]{1, 2, 3});
        JSONObject meta = new JSONObject(new String(manifest(item), StandardCharsets.UTF_8));
        meta.put("buildId", "another-build");
        assertThrows(IOException.class, () -> new GameDataInstaller.Plan(meta.toString().getBytes(StandardCharsets.UTF_8)));
        item.put("filename", "../fixture.zip");
        assertThrows(IOException.class, () -> new GameDataInstaller.Plan(manifest(item)));
        item.put("filename", "fixture.zip");
        meta.put("buildId", GameDataInstaller.BUILD).put("chunkCount", 2).put("totalSize", 6)
            .put("chunks", new JSONArray().put(item).put(item));
        assertThrows(IOException.class, () -> new GameDataInstaller.Plan(meta.toString().getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void resumesAndVerifiesTheEntireArchive() throws Exception {
        byte[] data = {1, 2, 3, 4, 5};
        File part = temp.newFile(); Files.write(part.toPath(), new byte[]{1, 2});
        installer((uri, offset) -> {
            assertEquals(2, offset);
            return response(206, "bytes 2-4/5", new byte[]{3, 4, 5});
        }).download(SOURCE, new GameDataInstaller.Chunk(chunk(data)), part, 1, 1);
        assertArrayEquals(data, Files.readAllBytes(part.toPath()));
    }
    @Test public void ignoredRangeRestartsInsteadOfAppending() throws Exception {
        byte[] data = {1, 2, 3};
        File part = temp.newFile(); Files.write(part.toPath(), new byte[]{9});
        installer((uri, offset) -> response(200, null, data))
            .download(SOURCE, new GameDataInstaller.Chunk(chunk(data)), part, 1, 1);
        assertArrayEquals(data, Files.readAllBytes(part.toPath()));
    }
    @Test public void wrongRangeCannotCorruptThePartialFile() throws Exception {
        byte[] data = {1, 2, 3};
        File part = temp.newFile(); Files.write(part.toPath(), new byte[]{1});
        assertThrows(IOException.class, () -> installer((uri, offset) -> response(206, "bytes 0-2/3", data))
            .download(SOURCE, new GameDataInstaller.Chunk(chunk(data)), part, 1, 1));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(part.toPath()));
    }
    @Test public void interruptedTransferKeepsBytesForResume() throws Exception {
        File part = temp.newFile();
        assertThrows(EOFException.class, () -> installer((uri, offset) -> response(200, null, new byte[]{1, 2}))
            .download(SOURCE, new GameDataInstaller.Chunk(chunk(new byte[]{1, 2, 3})), part, 1, 1));
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(part.toPath()));
    }
    @Test public void corruptAndOversizedResponsesNeverVerify() throws Exception {
        byte[] data = {1, 2, 3}; File part = temp.newFile();
        assertThrows(IOException.class, () -> installer((uri, offset) -> response(200, null, new byte[]{9, 9, 9}))
            .download(SOURCE, new GameDataInstaller.Chunk(chunk(data)), part, 1, 1));
        assertFalse(part.exists());
        assertThrows(IOException.class, () -> installer((uri, offset) -> response(200, null, new byte[]{1, 2, 3, 4}))
            .download(SOURCE, new GameDataInstaller.Chunk(chunk(data)), part, 1, 1));
        assertTrue(part.length() <= data.length);
    }
    @Test public void alreadyCompletePartIsRehashedWithoutNetwork() throws Exception {
        byte[] data = {1, 2, 3}; File part = temp.newFile(); Files.write(part.toPath(), data);
        installer((uri, offset) -> { throw new AssertionError("Unexpected download"); })
            .download(SOURCE, new GameDataInstaller.Chunk(chunk(data)), part, 1, 1);
    }
    @Test public void cancellationStopsBeforeNetwork() throws Exception {
        GameDataInstaller installer = new GameDataInstaller((uri, offset) -> { throw new AssertionError(); }, () -> true, message -> {});
        assertThrows(InterruptedIOException.class, () -> installer.download(SOURCE,
            new GameDataInstaller.Chunk(chunk(new byte[]{1, 2, 3})), temp.newFile(), 1, 1));
    }
    @Test public void checkedFixtureDownloadsExtractsAndValidates() throws Exception {
        File source = temp.newFile();
        byte[] payload = {1, 2, 3};
        byte[] gameManifest = new JSONObject().put("files", new JSONArray().put(new JSONArray().put("common/test.bin").put(3)))
            .toString().getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(source))) {
            out.putNextEntry(new ZipEntry("common/test.bin")); out.write(payload); out.closeEntry();
            out.putNextEntry(new ZipEntry("manifest.json")); out.write(gameManifest); out.closeEntry();
        }
        byte[] archive = Files.readAllBytes(source.toPath());
        GameDataInstaller installer = installer((uri, offset) -> response(200, null, archive));
        File part = temp.newFile(); File data = temp.newFolder();
        installer.download(SOURCE, new GameDataInstaller.Chunk(chunk(archive)), part, 1, 1);
        installer.extract(part, data, payload.length + gameManifest.length);
        installer.validateData(data);
        Files.delete(new File(data, "common/test.bin").toPath());
        assertThrows(IOException.class, () -> installer.validateData(data));
    }
    @Test public void traversalAndExpansionLimitsFailInsideStaging() throws Exception {
        File data = temp.newFolder();
        for (String path : new String[]{"../escape", "/absolute", "C:/drive", "a/../escape", "a\\escape"}) {
            File archive = zip(path, new byte[]{1, 2, 3});
            assertThrows(IOException.class, () -> installer(null).extract(archive, data, 3));
        }
        File archive = zip("good.bin", new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> installer(null).extract(archive, data, 2));
        assertFalse(new File(data, "good.bin").exists());
    }
    @Test public void missingEngineStopsBeforeLargeDownloads() throws Exception {
        byte[] bytes = manifest(chunk(new byte[]{1, 2, 3}));
        AtomicBoolean fetchedManifest = new AtomicBoolean();
        GameDataInstaller installer = installer((uri, offset) -> {
            assertFalse(fetchedManifest.getAndSet(true)); return response(200, null, bytes);
        });
        assertThrows(IOException.class, () -> installer.install(temp.newFolder(), root -> {}));
    }
    @Test public void existingInstallIsPreservedWithoutNetwork() throws Exception {
        File root = temp.newFolder(); assertTrue(new File(root, "game").mkdir());
        assertThrows(IOException.class, () -> installer((uri, offset) -> { throw new AssertionError(); }).install(root, dir -> {}));
        assertTrue(new File(root, "game").isDirectory());
    }
    @Test public void resumeRechecksExtractedBytesInsteadOfTrustingAMarker() throws Exception {
        File work = temp.newFolder(); File data = new File(work, "staging/data"); assertTrue(data.mkdirs());
        File archive = zip("fixture.bin", new byte[]{1, 2, 3});
        GameDataInstaller installer = installer(null);
        JSONArray receipt = installer.extract(archive, data, 3);
        GameDataInstaller.Chunk chunk = new GameDataInstaller.Chunk(chunk(Files.readAllBytes(archive.toPath())));
        JSONObject record = new JSONObject().put("sha256", chunk.sha256).put("files", receipt);
        Files.write(new File(work, chunk.filename + ".done").toPath(), record.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(installer.done(work, chunk));
        Files.write(new File(data, "fixture.bin").toPath(), new byte[]{9, 9, 9});
        assertFalse(installer.done(work, chunk));
    }
    @Test public void recreatedActivityCannotStartASecondInstaller() throws Exception {
        File root = temp.newFolder();
        byte[] bytes = manifest(chunk(new byte[]{1, 2, 3}));
        AtomicBoolean checked = new AtomicBoolean();
        GameDataInstaller first = installer((uri, offset) -> {
            assertThrows(IOException.class, () -> installer((u, o) -> { throw new AssertionError("Concurrent request"); })
                .install(root, dir -> {}));
            checked.set(true);
            return response(200, null, bytes);
        });
        assertThrows(IOException.class, () -> first.install(root, dir -> {}));
        assertTrue(checked.get());
    }
    @Test public void knownConfigLengthRepairCannotHideCorruption() throws Exception {
        assertNotNull(GameDataInstaller.expectedConfigHash("common/data/gameconfig.xml", 58601, 58590));
        assertNull(GameDataInstaller.expectedConfigHash("arbitrary.bin", 58601, 58590));
        assertNull(GameDataInstaller.expectedConfigHash("common/data/gameconfig.xml", 58601, 58591));
        File data = temp.newFolder(); File config = new File(data, "common/data/gameconfig.xml");
        assertTrue(config.getParentFile().mkdirs());
        Files.write(config.toPath(), new byte[58590]);
        byte[] meta = new JSONObject().put("files", new JSONArray()
            .put(new JSONArray().put("common/data/gameconfig.xml").put(58601))).toString().getBytes(StandardCharsets.UTF_8);
        File manifest = new File(data, "manifest.json"); Files.write(manifest.toPath(), meta);
        assertThrows(IOException.class, () -> installer(null).validateData(data));
        assertArrayEquals(meta, Files.readAllBytes(manifest.toPath()));
    }
    @Test public void rejectsCleartextAndOversizedMetadata() {
        assertThrows(IOException.class, () -> GameDataInstaller.requireHttps(URI.create("http://example.com/file")));
        assertThrows(IOException.class, () -> GameDataInstaller.readBounded(new ByteArrayInputStream(new byte[9]), 8));
    }
}
