#!/usr/bin/env python3
"""Package, validate and prepare the pinned engine resources using only stdlib."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tarfile
import tempfile
import zipfile

BUILD = "8b0b5899ed"
ENGINE_SHA256 = "11ca8d2c04c5e843d18ff4aea4899d72c86973c6b031df334e67c446b2ae83e0"
SOURCE_SHA256 = "1951ae5aa47cdd0e99cc19412de4cea1edeebd48bdcdffad219b7cf64bbae19f"
PREFIX = f"assets/b/{BUILD}/"
ENGINE = PREFIX + "game.wasm"
INDEX = PREFIX + "shaders/index.json"
BUNDLE = Path(__file__).resolve().parents[1] / "engine-resources"
BLOCK = 1024 * 1024
PART_SIZE = 512 * 1024
MAX_EXPANDED = 512 * BLOCK
MAX_ARCHIVE = 128 * BLOCK


def digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as source:
        while data := source.read(BLOCK):
            result.update(data)
    return result.hexdigest()


def allowed(name):
    return (not name.startswith("/") and "\\" not in name and ":" not in name
            and all(part not in ("", ".", "..") for part in name.split("/"))
            and (name == ENGINE or name.startswith(PREFIX + "shaders/")))


def package(apk, directory):
    if digest(apk) != SOURCE_SHA256:
        raise ValueError("Reference APK does not match the inspected source hash")
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise ValueError("Bundle output directory must be empty")
    with tempfile.TemporaryFile(dir=directory) as compressed, zipfile.ZipFile(apk) as source:
        names = sorted(name for name in source.namelist() if allowed(name) and not name.endswith("/"))
        if len(names) != len(set(names)) or ENGINE not in names or INDEX not in names:
            raise ValueError("Missing or duplicate reference resources")
        if hashlib.sha256(source.read(ENGINE)).hexdigest() != ENGINE_SHA256:
            raise ValueError("Reference engine hash mismatch")
        total = sum(source.getinfo(name).file_size for name in names)
        if total > MAX_EXPANDED:
            raise ValueError("Reference resources exceed size limit")
        with tarfile.open(fileobj=compressed, mode="w:xz", preset=6, format=tarfile.USTAR_FORMAT) as archive:
            for name in names:
                info = tarfile.TarInfo(name)
                info.size = source.getinfo(name).file_size
                info.mode = 0o644
                info.mtime = 0
                with source.open(name) as content:
                    archive.addfile(info, content)
        compressed.seek(0)
        whole = hashlib.sha256()
        parts = []
        while data := compressed.read(PART_SIZE):
            name = f"engine-resources.tar.xz.part-{len(parts):03d}"
            (directory / name).write_bytes(data)
            whole.update(data)
            parts.append({"name": name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                          "git_blob_sha1": hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()})
        manifest = {"version": 1, "build_id": BUILD, "source_apk_sha256": SOURCE_SHA256,
                    "engine_sha256": ENGINE_SHA256,
                    "shader_index_sha256": hashlib.sha256(source.read(INDEX)).hexdigest(),
                    "file_count": len(names), "expanded_bytes": total,
                    "archive_sha256": whole.hexdigest(), "parts": parts}
        (directory / "bundle.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf8")
        return manifest


def prepare(directory, output):
    directory, output = Path(directory), Path(output)
    manifest = json.loads((directory / "bundle.json").read_text(encoding="utf8"))
    if (manifest.get("version") != 1 or manifest.get("build_id") != BUILD
            or manifest.get("engine_sha256") != ENGINE_SHA256):
        raise ValueError("Unsupported engine bundle")
    parts = manifest["parts"]
    if not 1 <= len(parts) <= 128 or not 1 <= manifest["file_count"] <= 20000:
        raise ValueError("Invalid bundle counts")
    if not 1 <= manifest["expanded_bytes"] <= MAX_EXPANDED:
        raise ValueError("Invalid expanded size")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.TemporaryFile(dir=output.parent) as joined:
            whole = hashlib.sha256()
            length = 0
            for i, part in enumerate(parts):
                name = part["name"]
                if name != f"engine-resources.tar.xz.part-{i:03d}" or not 1 <= part["size"] <= PART_SIZE:
                    raise ValueError("Invalid bundle part")
                path = directory / name
                if not path.is_file() or path.stat().st_size != part["size"]:
                    raise ValueError(f"Missing or incomplete engine part: {name}")
                data = path.read_bytes()
                if hashlib.sha256(data).hexdigest() != part["sha256"]:
                    raise ValueError(f"Engine part checksum mismatch: {name}")
                length += len(data)
                if length > MAX_ARCHIVE:
                    raise ValueError("Bundle exceeds archive limit")
                whole.update(data)
                joined.write(data)
            if whole.hexdigest() != manifest["archive_sha256"]:
                raise ValueError("Engine archive checksum mismatch")
            joined.seek(0)
            with tempfile.NamedTemporaryFile(dir=output.parent, suffix=".zip", delete=False) as target:
                temporary = Path(target.name)
            seen, expanded, hashes = set(), 0, {}
            with tarfile.open(fileobj=joined, mode="r|xz") as archive, zipfile.ZipFile(
                    temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as target:
                for member in archive:
                    if not member.isfile() or not allowed(member.name) or member.name in seen:
                        raise ValueError(f"Unsafe or duplicate resource: {member.name}")
                    seen.add(member.name)
                    expanded += member.size
                    if member.size < 0 or expanded > manifest["expanded_bytes"] or len(seen) > manifest["file_count"]:
                        raise ValueError("Resource size/count exceeds manifest")
                    info = zipfile.ZipInfo(member.name, date_time=(1980, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.external_attr = 0o100644 << 16
                    checksum = hashlib.sha256()
                    written = 0
                    with archive.extractfile(member) as content, target.open(info, "w", force_zip64=True) as dest:
                        while data := content.read(BLOCK):
                            written += len(data)
                            if written > member.size:
                                raise ValueError("Expanded resource exceeds declared size")
                            checksum.update(data)
                            dest.write(data)
                    if written != member.size:
                        raise ValueError("Truncated resource")
                    if member.name in (ENGINE, INDEX):
                        hashes[member.name] = checksum.hexdigest()
            if expanded != manifest["expanded_bytes"] or len(seen) != manifest["file_count"]:
                raise ValueError("Incomplete engine resource bundle")
            if hashes.get(ENGINE) != ENGINE_SHA256 or hashes.get(INDEX) != manifest["shader_index_sha256"]:
                raise ValueError("Engine or shader index hash mismatch")
            os.replace(temporary, output)
            temporary = None
        return len(seen)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_apk(apk, resources):
    with zipfile.ZipFile(apk) as built, zipfile.ZipFile(resources) as expected:
        wanted = expected.namelist()
        if len(wanted) != len(set(wanted)) or ENGINE not in wanted or INDEX not in wanted:
            raise ValueError("Invalid prepared resource ZIP")
        actual = [name for name in built.namelist() if name.startswith(PREFIX) and not name.endswith("/")]
        if len(actual) != len(set(actual)) or set(actual) != set(wanted):
            raise ValueError("APK is missing engine/shader resources or contains duplicates")
        for name in wanted:
            if not allowed(name):
                raise ValueError("Invalid resource path")
            with built.open(name) as left, expected.open(name) as right:
                while True:
                    a, b = left.read(BLOCK), right.read(BLOCK)
                    if a != b:
                        raise ValueError(f"APK resource differs from verified bundle: {name}")
                    if not a:
                        break
        if hashlib.sha256(built.read(ENGINE)).hexdigest() != ENGINE_SHA256:
            raise ValueError("APK engine hash mismatch")
        return len(wanted)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    pack = commands.add_parser("pack")
    pack.add_argument("--apk", required=True, type=Path)
    pack.add_argument("--bundle", type=Path, default=BUNDLE)
    ready = commands.add_parser("prepare")
    ready.add_argument("--bundle", type=Path, default=BUNDLE)
    ready.add_argument("--output", required=True, type=Path)
    verify = commands.add_parser("verify-apk")
    verify.add_argument("--apk", required=True, type=Path)
    verify.add_argument("--resources", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "pack":
        result = package(args.apk, args.bundle)
        print(f"Packaged {result['file_count']} resources in {len(result['parts'])} checked parts")
    elif args.command == "prepare":
        print(f"Prepared {prepare(args.bundle, args.output)} verified engine/shader resources")
    else:
        print(f"Verified {verify_apk(args.apk, args.resources)} packaged APK resources")


if __name__ == "__main__":
    main()
