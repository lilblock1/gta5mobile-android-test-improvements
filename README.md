# Nightlane Android

> **UNOFFICIAL, AI-ASSISTED FORK — NOT THE ORIGINAL GRAND V PROJECT.**
> Nightlane Android is an independent experimental fork of the
> [BytArch GTA-V Android port](https://github.com/bytarch/gta5mobile-android),
> which adapts the Grand V web game. The fork adds Android-specific fixes,
> diagnostics, performance work and experimental graphics improvements developed
> **with AI assistance**. Original code and upstream work are not claimed as ours.
> **Not affiliated with Rockstar Games or any original project authors.**

This project retains upstream author notices, source-code history and its
**GPL-3.0-or-later** license. Credit includes the original web-port developers,
BytArch's Android-port contributors, Constantin Clerc (c22dev) / muguet portions,
and the [gta5-ios-port](https://github.com/kevinerikjs/gta5-ios-port)
engine-core work where incorporated. Consult the source history for detailed
authorship. AI-assisted changes require review and testing like any other code.

Nightlane runs user-supplied web-port game data through the existing WebAssembly
runtime ([Wasmtime](https://wasmtime.dev)) and a Rust [wgpu](https://wgpu.rs)
renderer using Vulkan on Android (Metal on Apple platforms). The renderer is
being optimized **incrementally** for Android; it is **not yet a fully rewritten
native Vulkan engine**. The original presentation path remains available.

The repository includes a verified snapshot of the user-supplied engine and
shaders for build `8b0b5899ed`. GitHub APKs package these resources; the large data
archives download on first Play. Users must be entitled to use their data.
This is experimental software; compatibility, FPS,
graphics improvements and device stability are not guaranteed.

- **Android (working target)** — ARM64, Android 10+, Vulkan. See below.
- **iOS / macOS** — Metal, JIT on macOS, AOT on iOS. Build scripts at the bottom.

## Android

**Experimental builds:** Download `Nightlane-Android-arm64-experimental-debug`
from the [Nightlane Android CI runs](https://github.com/lilblock1/gta5mobile-android-test-improvements/actions/workflows/android.yml)
**only after a successful build**. ARM64, Android 10+, Vulkan; debug-signed and
sideloaded. GitHub Actions currently uses a runner debug key unless a persistent
signing keystore is configured; in that case, upgrading an older installation
may require matching its certificate or backing up saves before reinstalling.

Push to `main` or `phase1-world-streaming` and GitHub Actions builds an APK with
the matching engine and shaders. Locally: `sh make-android.sh` or
`.\make-android.ps1`, output at
`android/app/build/outputs/apk/debug/app-debug.apk`.

### Game data

The launcher can use files you already have or run first-Play setup. The layout
is the folder holding `data/` and `b/<build>/`:

```
data/manifest.json
b/<build>/shaders/index.json
b/<build>/game.wasm        (required unless game code is embedded in the build)
```

**First-Play setup:** with no existing game folder, Play downloads the eight
SHA-256-checked ZIPs from the configured-in-code reference manifest and installs
them into private storage. Keep the launcher open. Pause stops at the next read;
tap Play again to resume. Downloads use HTTPS, verify complete archive hashes,
reject unsafe ZIP paths, check extracted sizes and the game manifest, and publish
the staged folder only when complete. Existing imports and saves are preserved.
The current manifest advertises about **13.7 GiB compressed / 14.2 GiB extracted**;
allow extra room for one archive and the bundled engine resources.

The mirror supplies **data only**. The matching engine and 6,684 shader files are
stored as a checked bundle under `engine-resources/`. GitHub Actions prepares
that bundle, verifies its hashes, and packages it automatically. It also compares
every packaged resource against the bundle before publishing the APK, so an APK
missing those files cannot pass the build. The local build scripts do the same
preparation and require Python 3.9+. No bulk game archive is downloaded by the
build. See [bundle provenance and local commands](engine-resources/README.md)
and [the APK investigation](docs/APK_COMPARISON.md).

- **Use a game files folder** — pick a folder that already holds `data/` and
  `b/`. It is used where it lies, so nothing is copied and no free space is
  needed. The game folder does not have to be the picked one: the launcher
  looks inside what you picked, so the release folder (`GrandV-assets`, which
  holds `mirror/playgta5.com/data/` and `.../b/`) works as it is.
- **Import game ZIP** — pick a `.zip` of the same tree. It is extracted into app
  storage, which needs free space for the archive and the extracted data alike.
  A wrapper folder inside the archive is stripped, so the release ZIP works too.

The folder must be on internal storage (`/storage/emulated/0/...`). Android
hands an app a folder permission but still refuses to `open()` files another app
owns, which is exactly what data copied over by a downloader or torrent app
looks like — so the launcher offers **Grant file access** ("All files access")
when that happens.

### Graphics

Nightlane Android's **experimental graphics and thermal controls** are
available in the launcher Settings:

- **Native / Smooth / Sharp:** Native preserves original output (default);
  Smooth reduces pixelation; Sharp adds contrast-adaptive sharpening using the
  same four texture loads. The Gentle, Balanced and Strong sharpening presets
  do **not** implement AMD FSR, TAA or higher-resolution game textures.
- **Wasmtime anti-aliasing:** keeps the existing 2x cap by default. Adaptive
  lowers the cap to 1x when render scale is 0.25–0.70 or reported physical RAM
  is at most 6 GiB; MSAA Off always caps at 1x. This reduces sample storage/work
  at the cost of rougher edges. FPS and thermal gains need phone measurement.
- **Adaptive thermal protection:** enabled by default. The Android thermal
  service's *severity* signal (not Celsius) limits rendering to **42 FPS at
  Severe**, **30 FPS at Critical**, **24 FPS at Emergency or worse**. It
  never raises a player's existing lower FPS cap. Can be disabled in Settings.
- **Distant-detail profiles:** Performance preserves the previous lowest LOD
  behavior by default; Balanced and Detailed raise LOD modestly, including
  overriding Low-preset `-lodScale` flags. Higher LOD may increase draw calls
  and heat. Engine defaults removes the automatic phone LOD pin but may still
  be affected by other user graphics settings. This does not repair missing
  shader assets or texture-data errors.
- **Compact touch HUD:** reduced-size, subtler controls and a secondary
  **MORE** menu by default; adjustable button size/opacity in Settings.
  Existing full-layout option remains available.

These are **AI-assisted experimental enhancements** to the existing wgpu
Vulkan renderer, *not* a completed independent Vulkan game engine. APK build,
driver-side shader validation, image quality, RAM, FPS and thermal gains
must still be verified on a physical Android device.
See [renderer roadmap](docs/NIGHTLANE_RENDERER.md).

Every graphics knob the web launcher exposes is in the launcher, under
**Graphics**, with the same names, plus Low / Engine default / Max presets.
They are sent to the engine as arguments rather than written into the save file,
because the engine rewrites its own settings on every save.

Texture, reflection, water, particle and grass quality; scenery, pedestrian and
vehicle detail; city density; variety; post FX; motion blur; depth of field;
shadow quality and softness; ambient occlusion; render distance; fog volumes;
particle and long shadows; loading ahead; distant lights.

For Wasmtime black-screen reports, two independent switches are available
under Graphics:

- **Force software clip distances** emulates clipping in the fragment stage.
  Adreno devices get this automatically: their Vulkan driver loses the device
  on hardware clip distances, which is what left the world black.
- **Decode BC textures on CPU** tests the existing decompression fallback even
  when the GPU advertises native BC support. This uses more memory.

Export logs for each run. The logs record the selected diagnostics, GPU driver,
pipeline creation failures and device-loss reason.

**Low (phone default)** turns shadows off entirely, pins detail to the floor and
pulls the render distance to 500 m. Measured on a Dimensity 8000, pinning LOD
alone cut draw calls from 2082 to 579 per frame; shadow and reflection passes
account for most of the rest.

### Multiplayer

Multiplayer uses the Grand-V relay — the same transport as the web version, so
a room can be shared with browser players. In the launcher: enable
**Multiplayer**, then fill in the relay address (`ws://host:8012/relay`), the
room name, and the access code from whoever is hosting. Plain `ws://` for now, no
TLS.

The relay carries pose only: position, rotation and model at 20 Hz. Rooms hold
two players. That is the whole protocol — see `src/relay.rs` for the client and
[bytarch/Grand-V-Android-Port](https://github.com/bytarch/Grand-V-Android-Port)
for the server.

### World streaming development plan

The [Nightlane WorldStream roadmap](docs/NIGHTLANE_WORLDSTREAM.md) describes
an incremental, experimental path toward improved file I/O, bounded read-ahead,
RAM budgeting, and (only if feasible) geographic asset streaming.

**Phase 0 code is implemented, with CI and phone verification pending.** In
Settings, `WorldStream backend` now offers **Original (default)** or
**Experimental (reserved; currently uses Original)**. The reserved option
cannot alter asset loading yet: the game continues to use its original I/O
path and memory rules. The native Wasmtime I/O worker records aggregate
`[worldstream-baseline]` measurements roughly every 10 seconds, with build,
graphics/heap settings and device metadata elsewhere in the exported log.

Use the [Phase 0 baseline test report](docs/WORLDSTREAM_PHASE0_BASELINE.md)
to record repeatable runs on Android, then review CI and real gameplay results
before starting Phase 1. No speedup is claimed or expected from Phase 0.

### Native engine core

A separate build path compiles the upstream
[gta5-ios-port](https://github.com/kevinerikjs/gta5-ios-port) engine core with
wasm2c, running `game.wasm` as native ARM64 C instead of interpreting it. It is
not the default build, and it is still the rougher of the two.

Graphics go through the same wgpu renderer as the wasmtime path: the engine
core's graphics worker hands its command ring to `muguet_gpu_start` in the Rust
library, and the engine's own futex (wait on the ring head, wake the producer)
is registered with the renderer, so the ring protocol is the one the browser's
wgpu worker speaks. The renderer is resolved with `dlsym`, so the engine core
never links against it and a build without the renderer still boots (headless).

## Building for Apple platforms

macOS:

```
./make-app.sh
```

gives `build/Muguet.app`.

iOS:

```
TEAM=<your team id> ./make-ios.sh game.zip --install
```

`--ipa` produces `build/Muguet.ipa`. With a free account add `FREE=1` plus a
`BUNDLE_ID` of your own. Install through AltStore; Sideloadly and SideStore are
known to be problematic.

### Requirements

Apple Silicon Mac, macOS 14+, Rust toolchain, Xcode 26+. For iOS also
`rustup toolchain install 1.98.1`, `rustup target add aarch64-apple-ios
--toolchain 1.98.1`, `xcodegen` (`brew install xcodegen`), an Apple developer
account, and a device on iOS 15+ with ideally 8 GB of RAM.

## Limitations

- ARM64 only on Android, Vulkan required, Android 10+.
- First launch compiles the game and takes several minutes; early stutter is
  mostly loading from storage.
- The native wasm2c engine renders through the same wgpu renderer, but it is
  pinned to game build `8b0b5899ed` (wasm sha256 `11ca8d2c…`): a newer web
  build of the game needs the wasm2c pins re-checked first.
- Multiplayer is two-player pose replication over a relay you do not host.
- iOS needs the increased memory limit entitlement; the minimap does not show;
  story missions are single player only.

## Repository layout

```
src/            the port itself: engine host, graphics, audio, input, netcode
android/        Gradle app, the Java launcher UI, NDK build
app/            native wasm2c engine core (work in progress)
ios/, launcher/ iOS and macOS shells
vendor/         patched wgpu-core and the wasm2c bridge
```

## Reporting bugs

- Nightlane issue tracker: https://github.com/lilblock1/gta5mobile-android-test-improvements/issues
- Original BytArch port issues: https://github.com/bytarch/gta5mobile-android/issues
- Direct contact: https://x.com/MikeJohndlvl

The launcher has both links at the bottom of its screen. Include your phone
model, Android version and Vulkan driver, plus the exported logs from the
`Export logs` button when the crash is not reproducible.

## Legal

This repository includes the user-supplied engine/shader bundle documented in
`engine-resources/`; it does not contain the bulk game-data archives. Users must
be entitled to use their web-port data and engine resources. The optional
installer contacts a third-party mirror; a manifest hash does not establish
ownership or redistribution rights. No license checks are bypassed.

Nightlane is an AI-assisted modified fork, not the official Grand V, GTA V, or
BytArch Android release. Upstream copyright notices remain in their files.
The GPL-3.0-or-later license remains applicable to this modified source.

Contributions welcome.
