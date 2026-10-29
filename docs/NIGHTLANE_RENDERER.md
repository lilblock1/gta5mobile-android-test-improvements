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

Launcher > Settings > **Nightlane graphics reconstruction**:

- **Native (default):** unmodified single `textureLoad` final surface shader.
- **Smooth:** four neighboring source texels per output pixel with interpolation.
- **Sharp:** the same four texture loads plus bounded contrast-adaptive RGB
  sharpening. Additional sharpening math has a nonzero but unmeasured GPU
  cost; Gentle/Balanced/Strong controls compile-time shader strength.
  **Not** AMD FSR/EASU/CAS or TAA; no motion vectors, increased geometry
  quality or proprietary game textures are introduced.

Other Android-only, experimental user settings:

- **Thermal FPS protection (default on):** Java reads Android's temperature
  **severity status** once per second and forwards it to Rust. Renderer FPS
  ceiling changes to 42 at Severe, 30 at Critical, 24 at Emergency and higher.
  A user-defined lower FPS cap is preserved. This can increase frame times
  and cannot guarantee lower temperatures; actual effect needs device tests.
- **LOD mode (default Performance):** Performance retains original zero-LOD
  phone profile; Balanced raises world/ped/vehicle to 0.25/0.10/0.10;
  Detailed raises them to 0.45/0.20/0.20. Engine defaults disables the
  automatic phone LOD XML override; explicit graphics overrides may remain.
  Changes apply at launch, not live. The flat building in the screenshot
  might also indicate a rendering bug; LOD is only one possible cause.
- **Touch HUD (default Compact):** the primary PAUSE, MORE, CAM and WEAPON
  toolbar sits below the tutorial region. MORE temporarily exposes PHONE,
  SWITCH, RADIO/COVER, LOOK BACK/CROUCH, and navigation actions.
  Button size and opacity have separate saved options.


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
- Compare Native, Smooth and Sharp at the same save/location, render scale
  and frame cap. Check 1% lows, average FPS, softness/ringing and latency.
- Check Java -> JNI thermal status from Normal through Severe using on-device
  reported status; do not simulate unsafe heat conditions just for testing.
- Compare Performance and Balanced LOD draw calls; stop if heat, CPU stalls or
  missing textures increase. Verify the compact HUD still allows every action.
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
