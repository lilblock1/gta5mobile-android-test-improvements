import hashlib
import importlib.util
import io
import json
from pathlib import Path
import random
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("engine_resources", Path(__file__).resolve().parents[1] / "engine_resources.py")
resources = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resources)


class EngineResourcesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.engine = b"synthetic test engine"
        self.index = b'{"fixture":true}'
        self.hash_patch = patch.object(resources, "ENGINE_SHA256", hashlib.sha256(self.engine).hexdigest())
        self.hash_patch.start()
        self.addCleanup(self.hash_patch.stop)

    def bundle(self, additions=(), include_engine=True, symlink=False):
        entries = [(resources.ENGINE, self.engine)] if include_engine else []
        entries += [(resources.INDEX, self.index)] + list(additions)
        directory = self.root / "bundle"
        directory.mkdir()
        packed = io.BytesIO()
        with tarfile.open(fileobj=packed, mode="w:xz") as archive:
            for name, content in entries:
                info = tarfile.TarInfo(name)
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))
            if symlink:
                info = tarfile.TarInfo(resources.PREFIX + "shaders/link")
                info.type = tarfile.SYMTYPE
                info.linkname = "../../outside"
                archive.addfile(info)
        data = packed.getvalue()
        parts = []
        for i, start in enumerate(range(0, len(data), resources.BLOCK)):
            part = data[start:start + resources.BLOCK]
            name = f"engine-resources.tar.xz.part-{i:03d}"
            (directory / name).write_bytes(part)
            parts.append({"name": name, "size": len(part), "sha256": hashlib.sha256(part).hexdigest()})
        manifest = {"version": 1, "build_id": resources.BUILD,
                    "engine_sha256": resources.ENGINE_SHA256,
                    "shader_index_sha256": hashlib.sha256(self.index).hexdigest(),
                    "file_count": len(entries) + int(symlink), "expanded_bytes": sum(len(b) for _, b in entries),
                    "archive_sha256": hashlib.sha256(data).hexdigest(), "parts": parts}
        self.write_manifest(directory, manifest)
        return directory, manifest

    @staticmethod
    def write_manifest(directory, manifest):
        (directory / "bundle.json").write_text(json.dumps(manifest), encoding="utf8")

    def test_prepares_complete_zip_and_verifies_apk_bytes(self):
        directory, _ = self.bundle()
        output = self.root / "ready.zip"
        self.assertEqual(resources.prepare(directory, output), 2)
        self.assertEqual(resources.verify_apk(output, output), 2)
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(archive.read(resources.ENGINE), self.engine)

    def test_reassembles_multiple_ordered_parts(self):
        with patch.object(resources, "BLOCK", 512):
            directory, manifest = self.bundle([(resources.PREFIX + "shaders/large.bin", random.Random(7).randbytes(8192))])
            self.assertGreater(len(manifest["parts"]), 1)
            self.assertEqual(resources.prepare(directory, self.root / "ready.zip"), 3)

    def test_missing_part_fails_without_replacing_previous_output(self):
        directory, manifest = self.bundle()
        (directory / manifest["parts"][0]["name"]).unlink()
        output = self.root / "ready.zip"
        output.write_bytes(b"previous build")
        with self.assertRaises(ValueError):
            resources.prepare(directory, output)
        self.assertEqual(output.read_bytes(), b"previous build")

    def test_changed_part_is_rejected_before_unpacking(self):
        directory, manifest = self.bundle()
        part = directory / manifest["parts"][0]["name"]
        data = bytearray(part.read_bytes())
        data[-1] ^= 1
        part.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "checksum"):
            resources.prepare(directory, self.root / "ready.zip")

    def test_unsupported_build_is_rejected(self):
        directory, manifest = self.bundle()
        manifest["build_id"] = "unknown"
        self.write_manifest(directory, manifest)
        with self.assertRaises(ValueError):
            resources.prepare(directory, self.root / "ready.zip")

    def test_missing_engine_is_rejected(self):
        directory, _ = self.bundle(include_engine=False)
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            resources.prepare(directory, self.root / "ready.zip")

    def test_traversal_member_cannot_reach_build_input(self):
        directory, _ = self.bundle([(resources.PREFIX + "shaders/../../outside", b"invalid")])
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            resources.prepare(directory, self.root / "ready.zip")
        self.assertFalse((self.root / "ready.zip").exists())

    def test_symlinks_are_rejected(self):
        directory, _ = self.bundle(symlink=True)
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            resources.prepare(directory, self.root / "ready.zip")

    def test_duplicate_members_are_rejected(self):
        directory, _ = self.bundle([(resources.INDEX, self.index)])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            resources.prepare(directory, self.root / "ready.zip")

    def test_expansion_cannot_exceed_manifest(self):
        directory, manifest = self.bundle()
        manifest["expanded_bytes"] -= 1
        self.write_manifest(directory, manifest)
        with self.assertRaisesRegex(ValueError, "exceeds"):
            resources.prepare(directory, self.root / "ready.zip")

    def test_missing_apk_resources_fail_verification(self):
        directory, _ = self.bundle()
        expected, apk = self.root / "ready.zip", self.root / "app.apk"
        resources.prepare(directory, expected)
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr(resources.ENGINE, self.engine)
        with self.assertRaisesRegex(ValueError, "missing"):
            resources.verify_apk(apk, expected)

    def test_changed_shader_in_apk_fails_verification(self):
        directory, _ = self.bundle()
        expected, apk = self.root / "ready.zip", self.root / "app.apk"
        resources.prepare(directory, expected)
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr(resources.ENGINE, self.engine)
            archive.writestr(resources.INDEX, b"wrong shader index")
        with self.assertRaisesRegex(ValueError, "differs"):
            resources.verify_apk(apk, expected)


if __name__ == "__main__":
    unittest.main()
