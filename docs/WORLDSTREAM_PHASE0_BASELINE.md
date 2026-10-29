# WorldStream Phase 0 — baseline test report

> **Fill this out with measured results.** Phase 0 does **not** introduce
> a new streamer, predictive prefetching, extra map chunks, or a RAM cache.
> The Experimental option is reserved and currently falls back to Original.
> A result of "no change" is expected until later phases.

## Build and device

| Field | Result |
|---|---|
| Date / time | _ |
| Nightlane version / GitHub Actions run | _ |
| Source commit SHA (look for `[worldstream-baseline] ... build=`) | _ |
| Android device / model | _ |
| Android version / API | _ |
| Vulkan driver name / version (from GPU log) | _ |
| Play path | Wasmtime (Phase 0 baseline); wasm2c separately if used |
| Game data build ID (no file uploads required) | _ |
| WorldStream setting | Original / Experimental (both currently use Original) |
| Renderer filter | Native / Smooth / Sharp |
| Internal render scale | _ |
| Graphics preset and LOD profile | _ |
| FPS target and adaptive thermal setting | _ |
| Game heap setting | _ GiB |
| Streamer percentage | _ (default 35%) |
| GPU buffer policy | 45,000 cap / 15,000 target / 128 per frame (unchanged) |

## Reproducible driving route

1. Start at a fixed in-game spawn / save. Record the location in words.
2. Record 1 minute stationary while the initial load settles.
3. Drive the **same route** for 10–15 minutes. Include one high-speed
   section and a dense neighborhood if available.
4. Return along part of the same path for 2–3 minutes to see whether
   repeated traversal behaves differently.
5. Record any visible missing textures, collisions, slow loading, hitching
   or crashes. Do not intentionally overheat the phone.
6. Export logs in the launcher **after** gameplay. Save the GitHub Actions
   build link and the log alongside this report.

**Note:** First-run shader compilation/asset initialization can skew results.
Keep separate cold-start and warm-start runs; don't average unlike scenarios.

## Measurements

| Measurement | First run | Repeat run |
|---|---|---|
| Play time | _ | _ |
| Average FPS (measured) | _ | _ |
| Lowest repeated / 1% low FPS (only if captured) | _ | _ |
| Main-thread freeze count (>= 1 sec) | _ | _ |
| Largest freeze (ms) | _ | _ |
| Android process PSS (start/peak/end, MiB) | _ | _ |
| Reported native heap allocations (MiB) | _ | _ |
| I/O read count / bytes (`[worldstream-baseline]`) | _ | _ |
| Open file handles / opens / evictions | _ | _ |
| File read errors | _ | _ |
| Live GPU buffers / backup MiB | _ | _ |
| Highest observed Android thermal severity | _ | _ |
| Crash, low-memory exit or missing assets | _ | _ |

**Do not confuse:** game heap cap, Android PSS, CPU backup allocations, GPU
buffer memory, and device memory. They are different measurements. Android
thermal severity is **not a temperature in Celsius**.

## Log markers

Search the exported `muguet.log` for:

```text
[worldstream] phase=0
[worldstream-baseline] phase=0 build=
[worldstream-baseline] phase=0 io=original
[worldstream-baseline] phase=0 mode=original
[hud] worldstream-baseline device=
[hud] ram: pss=
gpu cache ownership:
cpu ram copies:
fps:
[wasm] freeze
[android-exit]
```

The exact availability of memory, FPS and freeze markers depends on the run
and logging path. The file-I/O baseline counters are emitted from
`src/io.rs` approximately every 10 seconds while the Wasmtime I/O
worker is active, including when idle.

## Acceptance checklist

- [ ] GitHub Actions Rust unit tests succeeded for this commit.
- [ ] GitHub Actions Android APK build succeeded for this commit.
- [ ] Original mode starts and plays as before with the same game files.
- [ ] Experimental selection is explicitly logged as **active=original**.
- [ ] Logs contain device/build/settings and regular I/O summaries.
- [ ] No extra persistent cache files, unexpected asset changes, or new crashes.
- [ ] Repeated driving route data captured and saved for later comparison.
- [ ] Phase 0 rollback confirmed: select Original and launch again.

**Decision:** _Pending / Ready for Phase 1 / Must fix regression_

**Known limitations:** _

**Raw gameplay log location:** _

**Build/run link:** _


## Observed baseline: 2026-10-29 — Galaxy S25+ (user-provided gameplay log)

**Evidence:** exported `Logs (1).txt` provided in the project conversation.
The private/raw log is **not** committed to GitHub. These summary values
were extracted from it, not estimated benchmarks or a successful CI report.

| Field | Recorded value |
|---|---|
| Build SHA | `924a1ca647af3a5cb1d19963bdf7d07f13ce9843` |
| Device | Samsung SM-S936U, Android 15 / API 35 |
| Play path | Wasmtime; Grand V game data build `8b0b5899ed` |
| GPU / driver | Adreno 830, Turnip Mesa 27.0.0-devel (git-f28352d117) |
| Stream mode | Requested Original; actual Original |
| Streaming memory fraction | 25% |
| Game heap configuration | 8 GiB *maximum*; game logged 3072 MiB initialized |
| Render scale | 0.50, game render target 780×360; surface 1560×720 |
| Graphics / LOD | Low-style flags, Performance LOD; no shadows/reflections |
| Nightlane filter | Sharp (four-tap) |
| Target FPS | 60, subject to adaptive thermal limiter |
| Runtime duration | Approximately 20 minutes after initial loading, based on 119 ten-second I/O summaries |

### Measurements captured from this run

| Metric | Observation |
|---|---|
| FPS logs | 119 ten-second samples; mean **49.2 FPS**, observed range **20.9–61.9 FPS** |
| Late FPS | Last 30 samples averaged approximately **46.5 FPS** |
| Android process PSS | Increased throughout gameplay; peak **10,847 MiB (~10.59 GiB)** |
| Android native heap allocation | Approximately **1,147 MiB** at end (different from PSS) |
| Renderer-reported host memory | Approximately **4,181 MiB** in final FPS window, a separate metric from PSS |
| I/O | **24,553 reads**, ~**1.21 GiB** of successful read bytes |
| File handles | **1,024** open, **4,425** opens, **3,401** LRU evictions |
| Read errors | **2** logged by baseline I/O counter; cause not yet established |
| GPU buffers | ~**43,470 physically allocated**, ~**506 MiB** reported in final interval |
| CPU restore copies | ~**95 MiB / 96 MiB** restore-backup budget at end |
| GPU textures | ~**14,632 live** near end; ~**12,085 cold for 600 frames** in last residency report; no safe texture eviction mechanism yet established |
| Thermal | Android status **SEVERE (3)** entered four times; reverted to MODERATE (2) three times, with renderer cap oscillating **42 → 60 FPS** |
| Frozen frames | Six explicit `[wasm] freeze` messages total, including startup and several late freezes |
| Exit history | Android reported **LOW_MEMORY**, `rss_kib=8189968`, timestamp 2026-10-29 15:04:30 US Central |

**Interpretation:** This run confirms that Phase 0 recording works and the
thermal FPS limiter responds, but **not** that the game is stable or Phase 0
passed. The monotonic PSS growth and Android low-memory exit are a separate
and urgent optimization target. The increased texture residency is a possible
contributor, not a proven allocation breakdown: GPU buffer bytes, game heap,
native heap, and Android PSS are not interchangeable. Reducing the *maximum*
game heap from 8 GiB will not itself prove lower committed memory.

**Proposed comparison:** Preserve the same route, game data, streaming
fraction and LOD. Run a **30 FPS** test with **Native** output and the same
thermal policy, followed by a separate **42 FPS** test if stable. Start each
from comparable phone temperature, note the persistent PSS climb, and stop
if Android reports a severe thermal condition or gameplay becomes unstable.
Do not change multiple other graphics variables simultaneously if trying
to attribute a result. A user-selected 5–6 GiB heap maximum can be tested
separately, checking for allocation failures as well as PSS.

**Phase 0 exit status:** Baseline captured. Code checks were inspected, but
successful GitHub Actions execution and no-regression status are **not yet
verified**; the run ended with a low-memory process exit. Do **not** mark
Phase 0 complete or start destructive cache eviction.

**Next investigation:** distinguish guest heap growth, Vulkan texture image
allocations/driver memory, and native CPU allocations. Investigate severe
thermal status oscillation and measure sustained FPS under conservative
limits, before attempting a full spatial streaming rewrite.
