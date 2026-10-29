# Nightlane Android renderer roadmap

> **Nightlane Android is an unofficial fork of the Grand V ecosystem and the
> [BytArch GTA-V Android port](https://github.com/bytarch/gta5mobile-android).
> Its modifications are AI-assisted and experimental. Original attribution,
> source history and GPL-3.0-or-later remain.**
>
> This document distinguishes what exists in code from future work.

## Architecture

- **Game runtime:** preserve the existing user-supplied game data, WebAssembly
  runtime and game logic. No game assets are bundled.
- **Current graphics translation:** the existing Rust `src/gpu.rs` wgpu
  renderer translates the game's GPU command ring to Vulkan on Android.
- **Nightlane Android output stage (phase 1, implemented):**
  `src/nightlane_render.rs` owns the final fullscreen presentation shader.
  Native (one-tap nearest) and Smooth (clamped four-tap bilinear) share the same
  bind group, render pass, surface texture and full-screen triangle.
  The selection is made **once per renderer startup**, not every frame.
  This is *not* a new low-level Vulkan engine yet.
- **Preservation rules:** keep native fallback, the 45,000 GPU-buffer cap,
  15,000 eviction target, 128 evictions/frame and the memory-pressure cleanup.
  Avoid additional full-resolution intermediates or permanent asset copies.
- **Native backend (not yet implemented):** a later, separate direct-Vulkan
  backend may replace portions of wgpu only after profiling and compatibility
  tests. Do not claim it is faster or complete in advance.

## Current user option

Launcher > Settings > **Nightlane Android output filter (experimental)**:

- **Native — original, fastest baseline (default):** original single
  `textureLoad` per output pixel.
- **Smooth — four-tap display reconstruction:** samples four neighboring input
  texels and interpolates them; it may reduce visible pixelation when internal
  render scale is below the phone's display size. It can also soften detail and
  increase texture bandwidth. It is *not* FSR, TAA, or a new texture/shadow pass.

The Wasmtime launch path loads `nightlane_present` from `config.json` into
`NIGHTLANE_PRESENT` via `src/config.rs`. The native wasm2c engine-core
launch path does not currently promise this setting will be applied; it remains
experimental. Unknown values fall back to Native.

## Roadmap (planned, not shipped)

1. **Phase 1:** separate the Android presentation backend, selectable
   non-destructive filters, explicit fallback and unit tests.
2. **Phase 2:** profile frame times / main-thread waits, GPU bandwidth,
   texture residency and shader failures on a real Galaxy S25+. Measure RAM
   and thermal conditions over sustained gameplay. Fix regressions first.
3. **Phase 3:** investigate a proven mobile upscaler and optional sharpening
   with source-format fallbacks; compare native and enhanced output under
   identical render scale, load, FPS target and thermal conditions.
4. **Phase 4:** reduce redundant Vulkan uploads, state churn and render passes
   where profiler data identifies a real bottleneck; protect shader behavior
   and cache rehydration.
5. **Phase 5:** only if phase 4 warrants it, prototype an opt-in low-level Vulkan
   backend with explicit buffer/image lifetime tracking, driver fallbacks and
   an immediate switch back to the current working wgpu path.

## Verification / release checklist

- Build `cargo test --lib` on Linux and the Android APK GitHub Actions job.
- Verify actual shader/pipeline creation on the phone (Rust-only compilation
  is **not** proof the WGSL filter works on the Vulkan driver).
- Launch with Native and Smooth from the same save/location, render scale and
  frame cap. Check 1% lows, average FPS, image sharpness and input latency.
- Drive through previously troublesome areas for 15-20 minutes and compare
  exported logs: freezes, RAM PSS, GPU buffer/texture counts and cache restores.
- Test app suspend/resume, switching back to Native, and device rotation.
- Reject changes that raise crashes, create persistent resources, cause visible
  artifacts, or increase memory pressure without sufficient image improvement.
- No claims of higher FPS, lower heat or working native Vulkan code without
  hardware evidence.

## Credits / source obligations

Nightlane is a **fork and AI-assisted modification**, not a new game. It retains
original notices and the repository's GPL-3.0-or-later license. Credit belongs
to its original web-port developers, the BytArch Android port, muguet/c22dev
and any other incorporated upstream projects as recorded in Git history.
Nightlane neither redistributes proprietary game assets nor claims to be
officially endorsed by the GTA V rights holder or upstream maintainers.
