package dev.muguet;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Test;

public final class NativeSaveStorageTest {
    @Test public void nativeEngineSaveRootsMatchGameDirectory() throws Exception {
        File files = Files.createTempDirectory("nightlane-native-save-roots").toFile();
        File game = NativeSaveStorage.gameRoot(files, "");
        assertEquals(new File(files, "game").getCanonicalPath(), game.getCanonicalPath());
        NativeSaveStorage.Root[] roots = NativeSaveStorage.roots(files, game);
        assertEquals(3, roots.length);
        assertEquals(new File(game, "userdata").getCanonicalPath(), roots[0].directory.getCanonicalPath());
        assertEquals(new File(game, "sandbox").getCanonicalPath(), roots[1].directory.getCanonicalPath());
        assertEquals(new File(files, "saves").getCanonicalPath(), roots[2].directory.getCanonicalPath());
    }

    @Test public void aSelectedGameFolderUsesSameRootAsEngineActivity() throws Exception {
        File files = Files.createTempDirectory("nightlane-native-save-picked").toFile();
        File picked = new File(files, "picked");
        File data = new File(picked, "data");
        assertTrue(data.mkdirs());
        assertTrue(new File(data, "manifest.json").createNewFile());
        assertEquals(picked.getCanonicalPath(),
            NativeSaveStorage.gameRoot(files, picked.getAbsolutePath()).getCanonicalPath());
    }

    @Test public void newAndOldBackupArchivesRestoreToTheCorrectRoots() throws Exception {
        File files = Files.createTempDirectory("nightlane-native-save-zip").toFile();
        File game = new File(files, "game");
        assertEquals(new File(game, "userdata/Documents/SGTA50015").getCanonicalPath(),
            NativeSaveStorage.restoreTarget(files, game,
                "native/userdata/Documents/SGTA50015").getCanonicalPath());
        assertEquals(new File(game, "sandbox/Documents/SGTA50017").getCanonicalPath(),
            NativeSaveStorage.restoreTarget(files, game,
                "native/sandbox/Documents/SGTA50017").getCanonicalPath());
        assertEquals(new File(files, "saves/Documents/SGTA50015").getCanonicalPath(),
            NativeSaveStorage.restoreTarget(files, game, "Documents/SGTA50015").getCanonicalPath());
    }

    @Test public void craftedBackupEntriesCannotEscapeAnySaveDirectory() throws Exception {
        File files = Files.createTempDirectory("nightlane-native-save-escape").toFile();
        File game = new File(files, "game");
        for (String name : new String[] {
            "../config.json", "native/userdata/../../config.json",
            "native/sandbox/../userdata/SGTA50015", "legacy/../../config.json"
        }) {
            try {
                NativeSaveStorage.restoreTarget(files, game, name);
                fail("Accepted unsafe save backup entry: " + name);
            } catch (IOException expected) {}
        }
    }
}
