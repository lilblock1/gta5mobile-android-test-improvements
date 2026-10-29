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
