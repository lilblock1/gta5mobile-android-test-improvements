# Nightlane native-only pre-launch warm-up and persistent shaders

This work adds **real, skippable progress** before launching the native ARM64
engine, with two bounded preflight activities and persistent Vulkan compilation
caching where the GPU driver supports it.

## Progress bar

When Play is tapped, the launcher persists the chosen settings, validates the
existing local game installation, then opens "Preparing Nightlane":

- **0–50%: Shader preparation** — read the actual shader index, hash it to detect
  engine updates and CPU-parse up to 128 matching WGSL files with Naga. Repeat
  launches skip parsing only if a successful check of the same shader index
  was recorded. It does not claim that an arbitrary shader source is a fully
  compiled GTA Vulkan pipeline.
- **50–95%: Read-only local asset pre-cache** — stream the first 512 KiB from up
  to 96 selected game archives (maximum 48 MiB) to warm Android's filesystem
  page cache. Prefer update, common, and x64 RPF archives. No installed game
  files are modified, copied into app cache, or deleted. It does not allocate
  a large application RAM cache or guarantee the Linux page cache retains
  every page.
- **95–100%: Ready** — open the real native-only game activity.
- **Skip** cancels the optional work, preserving the original game startup.
  Disabling "Prepare shaders and pre-cache nearby game assets before Play"
  skips the dialog entirely.

This is not a GTA scene-aware streamer rewrite. Scene-dependent assets are
still loaded by the original game. Subsequent drives may benefit from OS cache
and the pre-existing native streaming read-ahead, but must be measured.

## Vulkan shader pipeline caching

The Vulkan renderer requests wgpu `Features::PIPELINE_CACHE` where supported
and uses `PipelineCache` for render, compute and presentation pipelines. The
cache is stored in the app-private `files/nightlane-gpu-cache` directory,
keyed by wgpu's adapter/driver cache key and a fingerprint of the exact shader
index. **Only wgpu-generated cache bytes** may be reused, with
`fallback: true` for incompatible driver data. The cache is limited to
32 MiB, saved atomically from a background worker after enough newly compiled
pipelines and with a cooldown to limit overhead.

This does not guarantee that the first game launch will avoid all pipeline
compilations. The engine only reveals many real pipeline configurations during
gameplay; the cache can accelerate later runs with the same shader index and
compatible driver. Driver updates may discard it.

## Validation

- Python source-policy tests and Rust test for shader-index fingerprint.
- Full native-only Rust/wasm2c/Android APK in GitHub Actions.
- Device acceptance: compare 15-minute repeat drives at same settings, monitor
  average FPS, 1% lows, p99 frame gaps, memory RSS, GPU pipeline skips,
  readback stalls, thermal severity, mission stability and controls.
- Test warm-up on/off, Skip, first/second launch, app data reinstall, external
  game folders, and interrupted/missing game data. Do not claim verified
  performance gains until tests complete on a real phone.
