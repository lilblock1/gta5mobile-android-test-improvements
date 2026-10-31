# Native ARM64 black screen / 0 FPS — first device report

## What was observed

First dual-engine wasm2c test APK built successfully, but the native test
on Galaxy S25+ showed a **black viewport**, **0 FPS**, **GPU buf 0 MB**, and
the launcher-style touch controls on-screen. The centered message was:

`Futex wait at 0x20bc7b8 for 0.`

This is **not** a proven fatal error. The original
`vendor/fivestar/WasmFutex.c` emits that string on the *first observed
wait for each address, before it even checks whether the memory value
matches the expected value*. The last log entry is not necessarily the
thread that caused a startup stall.

A native game engine being present in an APK or reaching a futex call does
not establish that Vulkan device creation, the graphics command ring, or
gameplay has started.

## Changes in this diagnostic branch

- **Fix a real finite-timeout bug:** the engine used to add
  `CLOCK_MONOTONIC` uptime to a relative timeout, then pass the resulting
  absolute timestamp through a helper that added uptime *again*. A
  timed wait could last far longer than intended. The native futex now uses
  one absolute monotonic deadline for the entire wait, including spurious
  wakeups. This does **not** prove the reported indefinite wait was caused by
  that finite-timeout bug.
- **Improve useful startup status:** ordinary first-observed futex logs are
  still recorded, but do not overwrite the last meaningful launch status.
  After 45 seconds with zero completed renderer frames, the UI prompts the
  user to export logs.
- **Export actual native diagnostics:** the JNI bridge writes bounded
  `nightlane-native.log` (up to 8 MiB), containing native engine events and
  Rust Vulkan `stderr` collected by the existing stderr pump. The launcher
  now exports that together with the existing `muguet.log`, when available,
  and includes Android process exit information. A new native session
  replaces the previous native-specific log.
- **Graphics ring checkpoints:** the engine writes startup ring head/tail
  and ready/done values at 10, 30 and 60 seconds, so a stationary ring is
  reported even if nothing changed. Missing renderer futex hooks are reported.

## Test instructions for the next experimental APK

1. **Back up your GTA V saves first.** The native core is still experimental.
2. Select **TEST NATIVE ARM64 (wasm2c)** (not Wasmtime).
3. If the screen stays black for 45–60 seconds, exit via Back and return
   to the launcher. Do **not** delete game files.
4. Tap **Export logs** and send the generated `gta5-logs.txt`. It now includes
   a `nightlane-native.log` section, with native startup and Rust GPU logs.
5. Compare the logs for:
   - `[native] Constructors completed; calling the game entry point.`
   - `[native] Connecting game graphics commands to the native GPU renderer.`
   - `[native] Native graphics startup after ... ready=...`
   - `[renderer] [wgpu] device ready:`, `init failed:`, `running` or
     `Graphics initialization failed`
   - `Native boot stopped`, `Engine stopped at unported host function`,
     and Android process exit reasons.

If the graphics ring never starts, inspect native thread startup and
futex signaling. If it starts but `wgpu` fails, inspect Vulkan/surface and
shader setup. If it renders frames but the screen stays black, investigate
present/surface lifecycle rather than the futex. These are hypotheses until
confirmed by the exported logs.

**No claim that gameplay is fixed or faster is made by this patch.**
A physical-device test is required. Wasmtime remains the default fallback.
