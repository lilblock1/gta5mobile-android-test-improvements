# Pinned engine and shader snapshot

This bundle supplies the resources missing from the previous GitHub APK.
It contains `assets/b/8b0b5899ed/game.wasm` and 6,684 shader files copied
byte-for-byte from the user-supplied `GTAV_Android_v2_arm64.apk`. It contains no
bulk game-data archives, Gecko libraries, frame-generation plugins or driver binaries.
Original resource content and notices are preserved.

Source APK SHA-256:
`1951ae5aa47cdd0e99cc19412de4cea1edeebd48bdcdffad219b7cf64bbae19f`

Engine SHA-256:
`11ca8d2c04c5e843d18ff4aea4899d72c86973c6b031df334e67c446b2ae83e0`

The deterministic TAR/XZ snapshot is split into 512 KiB parts. `bundle.json`
records the ordered parts, sizes, SHA-256 checksums, complete archive checksum,
engine/shader-index checksums and resource count. The preparation tool validates
these before creating an assets ZIP. It accepts only regular files under the
fixed engine/shader paths, rejects duplicates and links, bounds expansion, and
publishes the prepared ZIP only after complete validation.

GitHub Actions prepares this bundle and passes it to Gradle automatically. It
compares every engine/shader file in the finished APK against the prepared ZIP
before publishing the artifact. Both local build scripts also prepare the bundle.
Python 3.9+ is required; no additional Python packages are needed.

For a direct Gradle build from the repository root:

```sh
python3 tools/engine_resources.py prepare --output work/engine-resources.zip
gradle -p android testDebugUnitTest assembleDebug \
  "-PengineResourcesZip=$PWD/work/engine-resources.zip"
python3 tools/engine_resources.py verify-apk \
  --apk android/app/build/outputs/apk/debug/app-debug.apk \
  --resources work/engine-resources.zip
```

The first-Play installer copies the bundled engine/shaders into private storage,
then downloads and verifies the separate data archives. Keep the launcher open;
its Pause button retains progress. Existing imported data and saves are preserved.
