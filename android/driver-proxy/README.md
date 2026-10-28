# Experimental application-local Vulkan driver proxy

This is an opt-in, unverified Android ARM64 integration, adapted from
[vaduur/LibertyRecompAndroid](https://github.com/vaduur/LibertyRecompAndroid).
It uses the [libadrenotools](https://github.com/bylaws/libadrenotools) loader,
not a root-only system driver replacement. See the upstream proxy's
[provenance](https://github.com/vaduur/LibertyRecompAndroid/blob/main/os/android/native/PROVENANCE.md).
The original proxy is MIT licensed; libadrenotools is BSD-2-Clause.

## Build

From the Android Gradle project:

```sh
./gradlew :app:assembleDebug -PwithDriverProxy=true
```

CMake downloads the pinned libadrenotools revision (including its submodules),
builds the proxy and helper libraries for arm64-v8a, and packages the native
libraries as extracted files. Without the flag, the ordinary build stays on the
stock Android Vulkan driver.

The launcher can import AdrenoTools driver ZIPs and choose `custom:NAME`.
The game and engine activities attempt to initialize the proxy in their own
processes before native renderer startup. The proxy probes the actual Vulkan
physical device and compares the selected driver identity against the stock
driver. If custom initialization fails, it attempts the stock Vulkan loader.

## Important limitations

- The proxy path is **experimental and not device-tested**. A successful CMake
  build is not proof that wgpu resolves `libvulkan.so` through the proxy.
  Verify the runtime logs (`MuguetDriver`) and actual device driver identity.
- A selected driver ZIP must support the phone's GPU and Android ABI. A valid
  ARM64 ELF is not evidence of Adreno 830 compatibility.
- Driver selection is process-local and must occur before the first Vulkan
  instance. Changing the driver requires restarting the game process.
- If the proxy fails before it can establish a working fallback, a native
  renderer launch may still fail; do not advertise this as a tested crash-safe
  recovery path.
- No system driver files or game data are modified.
