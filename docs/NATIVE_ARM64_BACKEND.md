# Nightlane Android — native ARM64 backend experiment

**Status: source/build experiment, not a verified playable backend.** Wasmtime
stays the default. Do not remove it until a native build passes a real-device
startup, rendering, story, pause/menu, save, and thermal test.

## What already exists in the upstream port

- The Android launcher already has a separate native wasm2c play action,
  shown **only** when `libgta5engine.so` is actually packaged.
- `EngineActivity` runs the native engine in its own `:engine` process,
  separate from Wasmtime's `:game` process.
- The **repository-root** `vendor/fivestar/` directory contains the JNI
  host, EngineHost.c, thread/futex support, and the original native
  memory/graphics interfaces. No new unlicensed native engine was invented.
- `EngineHost.c` resolves Rust `muguet_gpu_start` via `pg_renderer_symbol`;
  both game execution backends can send the same graphics command ring to
  the existing Rust wgpu/Vulkan renderer.
- The headless graphics stub is **fallback only** when those symbols aren't
  found. A successful native engine boot does not prove any frames render.
- `app/Scripts/prepare_android.py` generates native C for the user-supplied
  `game.wasm` using wabt/wasm2c, and strict function-body patches for the
  engine's controller and memory functions.

## What this branch changes

1. **Pinned inputs:** The older native host was pinned to SHA-256
   `dcd3dc966ae8ab78637137580da990f9acec2ecfba6c47d153bdc15e4b60ca02`.
   The actual verified Nightlane resource snapshot uses
   `11ca8d2c04c5e843d18ff4aea4899d72c86973c6b031df334e67c446b2ae83e0`.
   The newer snapshot requires `--allow-bundled-engine`; no wildcard hashes
   or unknown engine builds are allowed.
2. **Strict generated-patch validation:** The existing bytecode/function
   patchers still fail if their expected function bodies are absent. Only a
   successful strict patch pass can proceed to C compilation.
3. **Automated build:** `.github/workflows/native-arm64-experiment.yml`
   builds a separate opt-in **dual-runtime** test APK, using the same verified
   `engine-resources/` snapshot already present in this repo. The normal
   Android build workflow is unchanged.
4. **Native import coverage:** The first wasm2c run generated 256 C files
   and passed the exact game-function-body patch checks, but found 10
   missing `env.__syscall_*` socket imports. These now explicitly return
   `-52` (unsupported) like the **existing Wasmtime host**. Network
   operations do not pretend to succeed; all **other** missing native imports
   still abort the experimental build rather than yielding a misleading APK.
5. **APK check:** The test APK must contain
   `lib/arm64-v8a/libgta5engine.so` **and**
   `lib/arm64-v8a/libmuguet.so`, plus the verified shaders.
6. **User-visible opt-in:** A warning appears before launching the native
   backend. The choice is written to `config.json` and logged as
   `[nightlane-engine] launching=native_wasm2c` or `wasmtime`.

## Building

GitHub Actions → **Nightlane native ARM64 (wasm2c experimental)**.
The separate artifact, **only if every step succeeds**, is
`Nightlane-ARM64-Wasmtime-plus-wasm2c-EXPERIMENTAL`.

Native code generation and ARM64 C compilation are large and resource
intensive. A build failure identifies a real integration blocker; it is
not equivalent to successful gameplay. The standard Wasmtime APK should
remain installed and available where signatures allow.

For a local build using a compatible, legally obtained game module:

```bash
python3 app/Scripts/prepare_android.py \
  --mirror /path/to/owned-game-files --allow-bundled-engine
gradle -p android testDebugUnitTest assembleDebug -PwithEngineCore=true
```

On a machine with the Android SDK/NDK, Rust and cargo-ndk installed,
add the usual `engineResourcesZip` or selected game-folder inputs.
Do not commit `app/Generated/`, native compiled proprietary game blobs,
saves, or private game archives to the repository.

## Physical Galaxy S25+ acceptance tests

- [ ] Standard Wasmtime still launches.
- [ ] Native library is packaged and JNI registration succeeds.
- [ ] Native engine loads using the *same* verified build `8b0b5899ed`.
- [ ] Rust renderer connects to the game's graphics command ring.
- [ ] Native gameplay **draws real frames**, not headless fallback or a
      stationary loading splash.
- [ ] Controller, touch input, audio, open-world driving and gameplay tested.
- [ ] Pausing/resuming and app backgrounding do not deadlock the engine.
- [ ] Saves can be loaded without corruption; first back them up.
- [ ] The Armenian3 cutscene/stall reproduces or resolves conclusively.
- [ ] Compare 20-minute consistent routes: mean and low FPS, stutter,
      CPU/GPU memory, PSS, thermals, freezes, and power behavior.
- [ ] No regression to Wasmtime's working code/asset paths.

**No FPS gain, RAM saving, thermals improvement or story-mode fix is claimed
until these measurements exist.** The native engine core and Wasmtime
share the existing game logic and graphics renderer. Replacing the
runtime cannot by itself fix a guest-side movie or mission synchronization
error.

## Build limitations

- The native compiled library contains game-module-derived machine code.
  Keep distribution within the scope of your rights and applicable licenses.
- Some native function imports may be missing despite successful code
  generation. The build must not label the game playable if this check fails.
- Native CI success verifies compilation, packaging and tests **only**.
  Phone testing remains required.
- Separate backend testing is essential because the native engine may use
  different memory ownership and event scheduling than Wasmtime.
