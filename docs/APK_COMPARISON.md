# Local APK investigation and compatible changes

Inspection date: 2026-10-30 (America/Chicago). Branch base: `5f5f56c`.

## Evidence

The user-supplied `GTAV_Android_v2_arm64.apk` is 319,696,357 bytes with SHA-256
`1951ae5aa47cdd0e99cc19412de4cea1edeebd48bdcdffad219b7cf64bbae19f`.
Its decoded manifest identifies `com.playgta5.launcher`, version 2.0.0 / code 6,
minimum Android API 26 and target API 34. It contains ARM64 native libraries,
a Gecko/browser path, a `dev.muguet.GameActivity` native path, and bundled
`b/8b0b5899ed` engine/shader resources. There is no `assets/data/` tree.

The APK was not installed or executed. ZIP inventory, manifest/resources,
DEX decompilation and selected text assets were inspected locally. JADX 1.5.6
reported 21 Java reconstruction errors; the downloader coroutine was inspected
using its instruction-level fallback rather than treating failed Java output as
working source. Findings below distinguish observed calls from performance claims.
No decompiled implementation or native optimization library was copied into the
repository.

## Actual download source

`R2DownloaderActivity.T` XORs each byte with 90. Its `l()` method passes the
decoded origin to coroutine `eu0`, which appends `/r2_manifest.json`, reads
`chunks`, then downloads each relative `filename` from that origin. This is:

<https://pub-259a383567d14badb0df56ab4afe96f9.r2.dev/r2_manifest.json>

The different plain-text address under `SettingsActivity` / `r11` is a HEAD
connection test, not the active downloader origin:

<https://pub-2b3f1ba634ea439ba5984ba9535041fe.r2.dev/r2_manifest.json>

The active manifest retrieved during this investigation advertises build
`8b0b5899ed`, eight ZIPs, 4,101 data files, 15,284,072,996 extracted bytes and
14,678,795,825 compressed bytes. Every part has `compressedSize`,
`uncompressedSize` and `sha256`. HEAD confirmed the first ZIP's compressed size.
All eight central directories contain 4,101 unique flat data paths such as
`common/data/...` and `manifest.json`, rather than a complete `data/` + `b/`
installation; none includes engine resources. Their expanded totals match the
download manifest. The mirror returned 404 for `b/8b0b5899ed/game.wasm` and
`b/8b0b5899ed/shaders/index.json`.

Only metadata and two small XML configuration files were fetched during the
investigation: the download/game-data manifests, HEAD responses, ZIP directory
records, and the configuration entries needed to verify the errors below. No
bulk game archives were downloaded for testing. Distribution permission was asserted by the user;
the investigation does not independently establish the mirror's licensing.

## First-Play implementation

The new installer is independently implemented for this branch. It runs only
after Play is tapped with no compatible import or selected game folder.
It verifies the build ID, metadata bounds, unique chunk names, HTTPS responses,
resume ranges, compressed sizes, archive SHA-256, ZIP paths, uncompressed sizes,
entry CRCs, and final manifest file sizes. Extraction uses one reused 128 KiB
buffer. Checkpoints bind each extracted part to its archive hash and file CRCs;
resume rechecks staged bytes before skipping a completed part. The final rename
publishes a complete new installation. Existing imported folders, saves and
settings are not replaced. No license checks are modified.

The selected data mirror has two stale lengths in its own game-data manifest:

| File | Manifest bytes | Actual bytes |
| --- | ---: | ---: |
| `common/data/gameconfig.xml` | 58,601 | 58,590 |
| `x64/data/settings.xml` | 2,494 | 2,506 |

The other 4,096 listed files match the ZIP directory sizes. Both XML files were
read, CRC-checked and parsed successfully during inspection. Without correction,
the native reader can request beyond the first file's EOF or truncate the second
file. Setup corrects these two manifest lengths only when the path, old/new
lengths and full file SHA-256 match the inspected versions. All other mismatches
fail installation. Configuration content is preserved; there is no broad
"accept actual sizes" fallback and no change to previously imported games.

The reference APK supplies engine resources separately from its data mirror.
For an authorized private build, pass
`-PreferenceEngineApk=/absolute/path/GTAV_Android_v2_arm64.apk` to Gradle.
Only `assets/b/8b0b5899ed/game.wasm` and its shader directory are staged under
the ignored build directory and packaged. Both build and installer verify the
engine's SHA-256:
`11ca8d2c04c5e843d18ff4aea4899d72c86973c6b031df334e67c446b2ae83e0`.
The engine hash matches the existing wasm2c pin documented by this branch.
Default source-only CI builds do not include these resources; they fail setup
before downloading large archives and still accept the existing ZIP/folder flow.

Downloads are foreground-only. Keep the launcher open; Pause retains partial
downloads and verified staged parts. Activity destruction requests cancellation.
An app-private file lock prevents overlapping setup workers after recreation.
There is no new foreground service or notification permission. Full installation,
Android lifecycle behavior and gameplay still require physical-device validation.

## Optimization comparison

| APK approach observed | Branch comparison and decision |
| --- | --- |
| Device/scale-based MSAA limits | Added independently as a Wasmtime setting: default 2x, explicit 1x, or opt-in Adaptive (1x at scale 0.25–0.70 or reported physical RAM ≤6 GiB; 2x otherwise). Uses the existing native cap before renderer startup. No higher sample counts are enabled. |
| Heap clamping and SoC-based graphics tiers | Not transferred: shrinking a Wasm maximum can cause allocation failure, and the observed SoC heuristics do not establish safe rendering or memory budgets for this branch. Existing controls remain. |
| OPFS/browser block cache, memory cache and network read-ahead | Not transferred: these address HTTP/JavaScript buffering. Native `src/io.rs` already reads directly into shared memory and caches bounded open handles. Adding another byte cache has no verified benefit here. |
| Frame generation (`gtav_fg`, `lsfg-android`) | Libraries and call sites observed; source compatibility, synchronization, license terms and device behavior were not established. No binaries or matching shader blobs copied. |
| Custom-driver hooks / Adreno tools | Branch already has its own validated driver selection/proxy. No replacement with APK binaries. |
| BCn decoder plugin and shader modifications | Existing branch has a source-based BC fallback. The APK's binary ABI, shader assumptions and correctness were not established. No transfer. |
| High FPS, tiler and frame-queue environment variables | Native call sites observed, but several names are not consumed by this branch. Setting unused variables would not implement an optimization. Existing fences/queue protocol retained. |
| Automatic `gameconfig.xml` pool and density rewrites | No runtime tuning pass copied: some changes increase pools/buffers, and mission/memory compatibility was not verified. The selected mirror already contains customized configs; their content is preserved, with only the two hash-pinned manifest lengths repaired. |
| `largeHeap`, Vulkan requirement, graphics scale/FPS controls | Already present. Browser permissions, bundled certificates and browser-specific baseline profiles are not applicable renderer improvements. |

MSAA sample count and the associated storage/work reduction are deterministic;
FPS, image quality, RAM and thermal benefits are not measured on a phone. No
Repossession freeze fix is claimed.

## Additional verified branch fixes

During comparison, two native I/O issues were confirmed directly in source:
failed opens took an early completion path that bypassed read/error/latency
counters; destination validation added untrusted 64-bit addresses without overflow
checks. Failed opens now use the common completion path. Destination ranges use
checked conversions/addition and reject byte counts that cannot fit the protocol's
signed 32-bit response. Five regression tests cover boundaries and wrapping.

The existing Cargo lock file also omitted dependencies already declared by the
branch (`cargo test --lib --locked` failed before compilation). It was regenerated
to match the existing manifest; no dependency requirement was changed for this
investigation.

## Verification

- Rust library suite: 65 passed on Linux, including the five new range tests.
- Android Gradle JVM suite: 31 passed, including new installer/MSAA tests and
  existing HUD/thermal tests.
- ARM64 Rust release library: built with NDK 28.2.13676358, API 29 and the
  regenerated lockfile (`--locked`). The existing source-based Vulkan driver
  proxy and both hook libraries also compiled successfully. All four native
  libraries have 16 KiB LOAD alignment and resolved packaged/system dependencies.
- Private debug APK assembled successfully (version 0.1.7, code 8, application ID
  `dev.muguet.test`, minimum API 29 / target API 36). Packaging reused the separately
  completed Rust release build. APK signature verification and 16 KiB ZIP alignment
  checks passed. All 6,685 packaged engine/shader files were compared byte-for-byte
  against the supplied APK; no data ZIPs are embedded.
- Synthetic JVM tests exercise transfer/resume/checksum failure, ignored/wrong
  HTTP ranges, cancellation, bounded metadata, traversal/expansion rejection,
  extracted-byte checkpoint revalidation, and final data-manifest validation.
- A separate two-part synthetic installation using the local matching engine
  passed pause/resume, checkpoint validation, archive reuse, atomic publication
  and save preservation. No remote archive payload was used.
- A probe using the two inspected XML files verified the pinned size repairs
  and confirmed that configuration bytes remain unchanged. A synthetic corruption
  test ensures that unrelated files or changed content cannot receive the repair.
- Adaptive MSAA tests cover defaults, both thresholds, explicit Off, missing
  measurements and invalid scales.
- No multi-gigabyte game download or physical gameplay was run. Build results
  and final commit IDs are reported with the delivered artifacts.
