#!/usr/bin/env python3
"""Reuse verified native engine libraries from the pinned earlier APK; no compiling."""
import argparse
import hashlib
from pathlib import Path
import zipfile

EXPECTED = {
    "libgta5engine.so": "6a2796afdef2578e8b2a64fd5591b347ea247dab82ab1adc2198cb43bfcbf49a",
    "libmuguet.so": "5d5fd106e19d80a271172d8ef8e9f505089fc4f2725e7ba19007f6381cdd91c0",
}
PREFIX = "lib/arm64-v8a/"

def verify_and_copy(source: Path, target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(source) as archive:
        for name, expected in EXPECTED.items():
            member = PREFIX + name
            if archive.namelist().count(member) != 1:
                raise ValueError(f"Missing or duplicate native library: {member}")
            temporary = target / (name + ".tmp")
            digest = hashlib.sha256()
            try:
                with archive.open(member) as reader, temporary.open("wb") as output:
                    while block := reader.read(1024 * 1024):
                        digest.update(block)
                        output.write(block)
                if digest.hexdigest() != expected:
                    raise ValueError(f"Prebuilt library hash mismatch: {name}")
                temporary.replace(target / name)
            finally:
                temporary.unlink(missing_ok=True)
            print(f"[native-reuse] Verified {name}: {expected}")

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    verify_and_copy(args.source, args.destination)

if __name__ == "__main__":
    main()
