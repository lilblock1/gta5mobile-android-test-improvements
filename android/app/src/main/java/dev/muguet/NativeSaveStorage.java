package dev.muguet;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Resolves the real native-wasm2c save roots without changing game storage. */
final class NativeSaveStorage {
    static final class Root {
        final String prefix;
        final File directory;
        Root(String prefix, File directory) {
            this.prefix = prefix;
            this.directory = directory;
        }
    }

    private NativeSaveStorage() {}

    // Keep exactly in sync with EngineActivity.gameFolder(). The native
    // EngineHost calls pg_files_init(gameDir), which maps guest /userdata
    // and /sandbox underneath this directory, not app/files/saves.
    static File gameRoot(File appFiles, String pickedPath) {
        if (pickedPath != null && !pickedPath.trim().isEmpty()) {
            File picked = new File(pickedPath.trim());
            if (new File(picked, "data/manifest.json").isFile()) return picked;
        }
        return new File(appFiles, "game");
    }

    static Root[] roots(File appFiles, File game) {
        return new Root[] {
            new Root("native/userdata/", new File(game, "userdata")),
            new Root("native/sandbox/", new File(game, "sandbox")),
            new Root("legacy/", new File(appFiles, "saves"))
        };
    }

    /** Paths before this change were relative to legacy app/files/saves. */
    static File restoreTarget(File appFiles, File game, String path) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("Empty save ZIP entry");
        for (Root root : roots(appFiles, game)) {
            if (path.startsWith(root.prefix)) {
                return contained(root.directory, path.substring(root.prefix.length()));
            }
        }
        // Backwards-compatible restoration of previous Nightlane exports.
        return contained(new File(appFiles, "saves"), path);
    }

    static File contained(File root, String relative) throws IOException {
        if (relative == null || relative.isEmpty()) throw new IOException("Empty save ZIP entry");
        File out = new File(root, relative);
        if (!out.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
            throw new IOException("Unsafe save ZIP path: " + relative);
        }
        return out;
    }

    static List<File> directoriesWithSaves(File appFiles, File game) {
        ArrayList<File> found = new ArrayList<>();
        for (Root root : roots(appFiles, game)) {
            if (root.directory.isDirectory()) found.add(root.directory);
        }
        return found;
    }
}
