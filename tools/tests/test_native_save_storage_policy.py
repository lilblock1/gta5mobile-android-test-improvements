"""Storage regressions for native GTA savegame enumeration and backups.

These source gates are not a claim that the manual save UI works on a
physical phone. They ensure that our defensive changes aren't discarded.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

def source(path):
    return (ROOT / path).read_text(encoding="utf-8")


class NativeSaveStoragePolicy(unittest.TestCase):
    def test_backup_includes_actual_wasm2c_guest_userdata_and_sandbox(self):
        storage = source("android/app/src/main/java/dev/muguet/NativeSaveStorage.java")
        launcher = source("android/app/src/main/java/dev/muguet/LauncherActivity.java")
        engine = source("android/app/src/main/java/dev/muguet/EngineActivity.java")
        bridge = source("vendor/fivestar/EngineBridge.c")
        host = source("vendor/fivestar/EngineHost.c")
        self.assertIn('new Root("native/userdata/", new File(game, "userdata"))', storage)
        self.assertIn('new Root("native/sandbox/", new File(game, "sandbox"))', storage)
        self.assertIn('new Root("legacy/", new File(appFiles, "saves"))', storage)
        self.assertIn("NativeSaveStorage.gameRoot(getFilesDir(), picked)", launcher)
        self.assertIn("NativeSaveStorage.roots(getFilesDir(), nativeGame)", launcher)
        self.assertIn("NativeSaveStorage.restoreTarget(", launcher)
        self.assertIn("new File(dir, \"data/manifest.json\").isFile()", engine)
        self.assertIn("PGStartEngine(manifest, dir, engine_log_bridge, NULL)", bridge)
        self.assertIn("pg_files_init(filesRoot);", host)

    def test_guest_save_enumeration_closes_both_directory_file_descriptors(self):
        native = source("vendor/fivestar/NativeFiles.c")
        self.assertIn("fdopendir(copy)", native)
        self.assertIn("if(descriptors[fd].dir)closedir(descriptors[fd].dir);", native)
        self.assertIn("close(n);", native)
        self.assertIn('"/userdata/Documents"', native)
        self.assertIn('"/sandbox/Documents"', native)

    def test_save_tracing_is_bounded_without_render_or_audio_changes(self):
        native = source("vendor/fivestar/NativeFiles.c")
        self.assertIn(">= 96", native)
        self.assertIn('traceSave("enumerate-begin"', native)
        self.assertIn('traceSave("enumerate-end"', native)
        self.assertIn('traceSave("opened"', native)
        self.assertIn('traceSave("open-failed"', native)
        self.assertIn('traceSave("write"', native)
        self.assertIn('traceSave("closed"', native)
        self.assertIn("pthread_mutex_unlock(&filesLock);", native)

if __name__ == "__main__":
    unittest.main()
