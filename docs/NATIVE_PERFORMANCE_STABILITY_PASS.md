# Native-only performance and stability pass (experimental)

Source branch: `experimental/native-only-renderer`. These changes target the
compiled ARM64 wasm2c engine plus Vulkan/wgpu renderer, not Wasmtime. **Code
landing or GitHub Actions success is not evidence of device FPS improvement.**

## Implemented in this pass

1. **Reused mapped GPU staging**: map-async readback buffers are eligible for
   bounded reuse only after a successful map, the mapped range is dropped and
   `unmap()` has completed. Maximum 4 buffers and 8 MiB, or disable the feature via the launcher setting. Failed and
   outstanding buffers are never recycled. Original readback completion flags
   and engine waits remain in place. Report reuse hits, misses, pending maps,
   completions and 50 ms+ stalls.
2. **Less expensive safe GPU eviction selection**: retains the 45,000 physical
   GPU buffer soft limit, 15,000 oldest eligible candidates, 128 evictions per
   frame, 600-frame cold test and the current CPU-backed rehydration checks.
   Partial selection replaces sorting the entire candidate vector. Logs max
   scan time. Unbacked/guest-owned resources are never force-freed.
3. **Earlier shader readiness**: when a draw finds a pending pipeline, first
   drain already finished worker results without blocking, potentially avoiding
   a missed draw in the current frame. Incomplete shaders still skip draws;
   driver error-scope validation and device loss handling are unchanged.
4. **Frame and memory metrics**: 600-sample p50/p99 presentation-frame gaps,
   Android process RSS/anonymous/file-backed/swap breakdown once per ten seconds,
   and a clear reminder that the native game heap is not the renderer's
   separate Rust runtime heap. Do not interpret RSS as dedicated GPU VRAM.
5. **Native file server**: aggregate open-cache hits, disk reads, slow reads,
   bytes and errors once every ten seconds. Detect consecutive file reads and
   provide a 64-KiB bounded `posix_fadvise(WILLNEED)` hint to the OS page cache.
   The existing launcher **Experimental storage read-ahead** toggle maps to
   `PG_HTTPFS_READAHEAD_KB=64` or `0`; the process accepts 0-256 KiB.
   Foreground game reads are unchanged, and game files are never modified.
   Missing files are retried instead of permanently caching missing FDs.
6. **Touch aiming**: drag free space on the right, or drag from AIM/FIRE. A short decaying right-stick hold keeps swipes visible to the gamepad input thread. Full deflection is reached at 96 scaled pixels (previously 320). A toggleable free-aim preference is enabled by default.
7. **Protection for the existing renderer**: no unsupported Vulkan driver
   overrides, no dropped GPU fences, no guest-memory truncation, no forced
   guest texture destruction, no new engine chunk manager.

## Not yet resolved

- The earlier log showed RSS climbing toward 3.8 GiB, tens of thousands of
  engine-owned GPU allocations, shader-pipeline skips, and a 585 ms exposure
  readback stall. The new code improves retention, scan cost and visibility
  but does **not** prove that all retained objects are leaks, nor eliminate
  mandatory texture readbacks.
- Lowering the game's RAM heap or force-dropping guest textures without their
  lifecycle semantics could break missions, graphics or saves. Keep original
  management until a validated release/restore path is available.
- True scene-cell streaming, full shader warm-up, renderer-pass elimination,
  and a driver-specific low-level Vulkan rewrite require separate profiling
  and compatibility work, not unchecked shortcuts.

## CI and on-device acceptance

- GitHub Actions: run Python safety-policy unit tests, Rust native-renderer
  tests, build the complete native-only ARM64 core and Vulkan library, verify
  both are in the APK. No new game-data distribution is permitted.
- On device: back up saves, use the same game install and identical save/drive
  circuit for **15-20 minutes**, compare before/after FPS, p99 frame gaps,
  thermal status, process RSS and its trend, GPU objects, resource-eviction
  restorations, draw skips, readback-map waits and I/O slow-read counts.
- Compare shader workers 2 vs 4, native scale 0.50 vs 0.75, and OS read-ahead
  On vs Off without changing other settings. A 45/50 FPS cap can reduce heat
  even if it does not improve peak FPS.
- Re-test touch camera/buttons, driving, audio, mission progression, save/load,
  game restart, long drive and background/resume.
- Revert this pass if it creates rendering artifacts, readback mismatches,
  more freezes/crashes, or a persistent memory/heat regression.

**No physical-device test has been performed by CI.**
