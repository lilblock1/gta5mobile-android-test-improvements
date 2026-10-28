# Stage 2: Vulkan driver compatibility and memory tuning

This experimental branch starts with a safe Vulkan driver diagnostics change in `src/gpu.rs`.

## Driver support on Android

The renderer uses wgpu's Vulkan backend. The Vulkan driver is normally selected by Android's Vulkan loader and the system's authorized graphics-driver configuration, **before** wgpu creates an instance/device. A regular unprivileged Android app cannot safely load an arbitrary desktop or third-party driver package by passing a filename to wgpu.

The new log entry records the selected GPU, driver, and driver version, along with whether the Adreno-specific software clip workaround is applicable. This does not install, replace, or switch drivers. A future optional driver-selection UI must first verify OS-level support and device compatibility; it must never silently load untrusted shared libraries.

## Performance guardrails

- Keep the current 42 FPS pacing target.
- Keep the proven 45,000 / 15,000 cache eviction policy until profiling demonstrates a better threshold.
- Never delete game asset files to free GPU memory.
- Benchmark bind-group hit/miss rate and resource restorations before changing the 4,096-entry cache.
- Compare FPS stability, RAM, GPU cache evictions, and device temperatures over the same route on the same device.
- Fall back to the system Vulkan driver whenever an optional graphics driver fails initialization.

## Verification

Build the Android debug APK in GitHub Actions, then inspect the exported log for `[wgpu] driver support:`. Confirm that the renderer initializes and the Adreno software clip workaround remains active. **No physical-device benchmark or APK build has been completed for this change yet.**
