# Muguet Android — experimental

This adds an **ARM64 native Android application**, not an iOS emulator or a
WebView. It uses Wasmtime for `game.wasm`, wgpu/Vulkan for graphics, CPAL/AAudio
for audio, Android views for touch controls, and the existing UDP multiplayer.
The APK includes the matching engine and shaders; bulk game data downloads on first Play.
This fork installs as **Grand V Android Test** under application ID
`dev.muguet.test`. It can be installed alongside the original `dev.muguet` app
without replacing it. Each app has separate private game files, saves and config;
the test app must import or select compatible game files separately. Updates to
the test app require the same signing key; fresh CI debug keys may require
uninstalling only the existing test app (which deletes its private game files
and saves). This is not an official Rockstar app.

**Not yet verified in-game.** Building and testing with an actual device and
compatible game files is required before calling this a working port.

## Build with GitHub Actions

Push to `main` or run **Actions → Android APK → Run workflow**. The workflow
installs the SDK/NDK, Rust, JDK 17 and Gradle, builds the ARM64 APK, and uploads
**Muguet-Android-arm64-debug** in the run's **Artifacts** section. Download and
extract it to get `app-debug.apk`. A separate job runs Rust unit tests.
The first build may be slow. Artifacts expire after 14 days; rebuild as needed.
No game ZIP, signing credentials, or game-code cache belongs in the repository.

## Local build prerequisites

- Current stable Rust supporting the existing Wasmtime 49 dependencies.
- JDK **17** (set `JAVA_HOME` accordingly) and **Gradle 8.13** on `PATH`.
- Python **3.9+** for the checked engine-resource bundle.
- Android SDK with `platforms;android-36`, `build-tools;36.0.0`, and
  `ndk;28.2.13676358` installed from Android Studio's SDK Manager.
- `ANDROID_HOME` pointing to the SDK, or `android/local.properties` with `sdk.dir`.
- `cargo install cargo-ndk --version 4.1.2 --locked`.

Windows / PowerShell:

```powershell
.\make-android.ps1
# With a USB-debugging device connected:
.\make-android.ps1 -Install
```

Linux / macOS:

```sh
sh make-android.sh
sh make-android.sh --install
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk` (debug signed).
The build downloads public Cargo/Gradle dependencies, builds `libmuguet.so` for
`arm64-v8a`, and packages it. Native library alignment is set to 16 KiB; this
still needs verification on a 16 KiB-page Android device. Release distribution
requires your own signing configuration; never publish the debug key.
Native compilation targets API 29 (the app's minimum Android version), separately
from Java's compile/target SDK 36. NDK r28 supports native APIs through 35; the
Gradle build checks that the requested native API is supported before compiling.

## On the device

1. Use Android 10+ on an ARM64 device with Vulkan 1.1. A high-end phone with
   **8–12 GB RAM** is recommended, but does not guarantee compatibility.
2. Tap **Play — download game data**, or select **Import game ZIP**
   with your own compatible web-port archive: `data/manifest.json`,
   `b/<build>/game.wasm`, and `b/<build>/shaders/index.json`. A regular Steam/PC
   installation alone is not compatible with this runtime. See the download
   details below before starting.
3. For ZIP import, keep the launcher open. Allow space for a temporary copy
   of the ZIP plus the extracted files (and the old installation if replacing
   it). Imported data, saves and config live in app-private storage; uninstalling
   the app deletes them. Import cannot run as a background service.
4. Start at **30 FPS, 0.5 render scale, 5 GB maximum heap**. The heap cap is
   not total app memory: textures, compilation and native workers need more.
5. On Android, MSAA is capped at **2x** (desktop keeps the adapter maximum of
   up to 16x). MSAA is the largest single fragment-cost lever on phone GPUs,
   so 4x costs far more than the image quality returns. Set `MUGUET_MSAA_MAX`
   in `config.json`'s `extra_args` environment or rebuild with it set to
   override. The surface presents with `Fifo` (vsync-matched) instead of
   `Mailbox`, which avoids rendering frames the compositor never displays when
   the game outruns the display.
6. Set touch camera speed with **Look sensitivity** (0.25x–3.75x, default 1x,
   matching the previously fixed gain). It scales camera-drag look and
   select-wheel drag on the touch HUD. Movement stick strength and the game's
   own mouse setting (`mouse_sensitivity` in `config.json`) are separate and
   unchanged; controller look speed is unchanged.
5. First Play patches and compiles the Wasm on a worker thread and caches the
   generated native code. This can take several minutes and consume substantial
   RAM. Later launches reuse the cache. Do not import untrusted game archives
   or replace the private native-code cache with untrusted files.

### First-Play setup

The active source identified in the user-supplied APK is
<https://pub-259a383567d14badb0df56ab4afe96f9.r2.dev/r2_manifest.json>.
It advertises eight parts, about 13.7 GiB compressed and 14.2 GiB extracted.
Use it only if entitled to download and use that data.

With no imported or selected game folder, Play starts setup. HTTPS transfers
resume partial ZIPs, verify SHA-256 and size, and extract into staging with path,
size and CRC checks. Verified part receipts allow retries and recheck staged
content before skipping it. A complete game-data manifest is required before the
staged folder is renamed into place. Saves and existing imports are preserved.
Two verified stale XML file lengths are repaired only for exact known hashes;
unrelated size mismatches fail setup.
Keep the launcher open; **Pause download**, then Play resumes. This is not a
background service and does not request notification permission.

The mirror contains data. GitHub Actions and both local build scripts now prepare
the checked `engine-resources/` snapshot and include the matching engine/shaders.
To run Gradle directly, prepare the bundle and pass its ZIP:

```sh
python3 tools/engine_resources.py prepare --output work/engine-resources.zip
gradle -p android testDebugUnitTest assembleDebug \
  "-PengineResourcesZip=$PWD/work/engine-resources.zip"
```

The build checks the engine hash and packages only `b/8b0b5899ed/game.wasm`
and its shader directory. The workflow verifies every resource in the finished
APK before uploading it. `-PreferenceEngineApk=/absolute/path/reference.apk`
remains available for a separately supplied local APK. See
[bundle provenance](../engine-resources/README.md) and
[APK comparison](../docs/APK_COMPARISON.md).

The multi-gigabyte download, lifecycle behavior, final installation and gameplay
require phone testing. Automated tests use synthetic archives and mocked HTTP.

The left touch stick moves/steers; dragging the right area looks around.
Buttons change when entering vehicles. AIM toggles, MENU reveals navigation
keys, and dragging WEAPON/SWITCH/RADIO selects within wheels. A connected
controller hides the touch HUD. Android Back returns to the launcher; the
game runs in a separate process so subsequent Play starts a clean runtime.

For multiplayer, enable it and choose Host or enter another Muguet player's
address; the existing server uses UDP **47520**. This is not GTA Online.

## Known risks / test checklist

- Most phone GPUs lack BC desktop texture compression. BC1–BC7 uploads fall
  back to CPU decoding (including half-float HDR for BC6H), increasing loading
  time and texture memory considerably. This is usually the dominant hitching
  source during world streaming on mid-range Mali/Adreno GPUs, and it is
  unchanged by render scale. Raw block reinterpretation/copies
  produced by GPU shaders are **not emulated** by this upload fallback and may
  cause rendering errors; that path needs device/game testing.
- Vulkan limits, shader features, and drivers vary. Unsupported depth formats
  fall back to Depth24PlusStencil8, but this is not a universal GPU compatibility
  layer. No OpenGL ES fallback is offered for this renderer.
- Background/resume releases and recreates Vulkan surfaces with a GPU-thread
  acknowledgement; audio is paused. Exercise Home, lock/unlock, rotation,
  controller disconnection, and repeated Play/Back cycles on a real device.
- Check Story and Sandbox startup, vehicle controls, menus, audio, saves/reload,
  texture fidelity and a two-device LAN session.
- Large games may exceed the OS memory budget even with `largeHeap`. Android
  can terminate the process; no equivalent to Apple's increased-memory
  entitlement is available. Check thermal throttling during longer sessions.
- Original limitations remain (including missing minimap and single-player
  story missions).

Logs:

```sh
adb logcat --pid=<game-process-pid>
# Obtain the PID:
adb shell pidof dev.muguet:game
```

The exported launcher log also carries `[hud]` lines describing the touch HUD
window (attached, dropped, or why attaching failed), which is the first place
to look when the touch buttons are missing.

Rust unit tests (on a supported Unix development host): `cargo test --lib`.
Installer JVM tests: `gradle -p android testDebugUnitTest` (requires the build
prerequisites above). GitHub Actions runs these before uploading the APK.
Compile-only Android check after configuring the NDK:
`cargo ndk -t arm64-v8a --platform 29 check --lib`.
