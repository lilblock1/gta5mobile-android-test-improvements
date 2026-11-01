# Native-only performance and compatibility plan — 0.2.0

The native-only branch uses the **compiled ARM64 wasm2c game engine**, not the old
Wasmtime runtime. The Rust library `libmuguet.so` remains the Vulkan renderer.
The wasm2c C runtime remains needed and is **not** Wasmtime.

## Implemented in this candidate
- Native launcher graphics, CPU workers, streaming budget, FPS mapping to native
  frame modes (0/1/2), renderer frame queue, shader fixes, Vulkan filters,
  thermal controls and advanced GPU compatibility options forwarded through JNI.
- Vulkan shader compiler workers adjustable 1/2/4, defaulting to 2 on Android.
  GPU readback and explicit guest fence semantics are retained for correctness.
- Dynamic internal SurfaceView scale separate from full-resolution touch HUD.
- Optimized ARM64 C compilation: `-O1` generated wasm2c and `-O2` host/JNI.
  Full workflow validates actual Clang options rather than trusting the label.
- No duplicate game.wasm payload in native-only APK or installer. The source
  bundle still contains the hash-pinned original for rebuilding and rollback.
- Native `:engine` crash history and periodic memory/thermal diagnostic capture.
- Old 0.1.13 fast repackage is retired because its C JNI library lacks the new
  `nativeConfigure()` ABI; use the optimized full engine build.

## Known limitations
- Legacy GameActivity and Wasmtime sources are intentionally kept in Git for
  rollback and are not registered as an Android activity in this APK.
- Multiplayer relay is unavailable in the native offline engine.
- Custom Vulkan driver switching requires a separate compatible proxy; the
  stock driver remains the only known reliable choice.
- Experimental WorldStream is a reserved label and does **not** replace the
  existing streamer. Asset preloading still uses the existing implementation.
- Bink cutscenes have not been implemented by this change.
- GPU error-scope waits and explicit guest fences remain; skipping them without
  validation could silently corrupt resources.
- No device FPS, gameplay or crash fix can be proven through GitHub CI alone.

## Final test
Install/update without deleting saves. Test download/import, launch, touch,
controller, audio, save/load, long drive, mission progression, restart, low
memory and thermals. Compare fixed-location FPS between CPU workers 2/4,
shader workers 1/2/4 and render scale 0.50/0.75. Export logs after any crash,
freeze or sub-10-FPS result and check `:engine` process exit history.
