# Nightlane WorldStream — phased development plan

> **Planning document (not a claim of a completed world-streaming rewrite).**
> Nightlane Android is an unofficial, AI-assisted fork of the Grand V / BytArch
> Android port. Respect upstream notices, the GPL-3.0-or-later license, and
> user-supplied game-data requirements. Do not ship proprietary map assets.
>
> **Current status: Phase 0 code committed; automated build and physical
> device baseline still unverified.** No custom streaming backend has been
> implemented. We implement, build, test, and review **one phase at a time**.
> Each phase requires its exit criteria before starting the next.

## Why this is staged

The existing game already decides when to stream world assets. The current
Wasmtime data path in `src/io.rs` serves engine file-ID / byte-range requests
from `data/manifest.json` on a dedicated thread; it holds at most 1,024
recently used file handles, not 1,024 decoded world chunks. The current
`streamer_percentage` launcher setting asks the *game's* streamer to use a
smaller fraction of its reported budget (35%/25%/15%). The `src/gpu.rs`
45,000/15,000 GPU buffer policy governs only eligible recoverable GPU
allocations. **None of these controls knows the player's position or owns
mission/collision dependencies.**

A true spatial chunk manager cannot safely be introduced just by truncating
I/O reads, dropping guest assets, or discarding Vulkan handles. We must first
measure engine requests and establish safe integration points.

## Invariants (every phase)

1. Original streaming can always be selected; all new behavior is opt-in
   until measured and validated. No destructive changes to saves or game files.
2. Preserve all requested bytes and read ordering/visibility required by the
   guest. The engine's active objects, missions, collision, NPCs, audio and
   interiors must remain correct.
3. Never free a GPU resource in use by the renderer or a guest resource that
   lacks a trustworthy rehydration path. Leave existing **45K buffer cap /
   15K eviction batch / 128 per frame / 600-frame cold threshold** unchanged
   unless separately benchmarked and approved.
4. Honor explicit RAM and disk ceilings. Never cache the whole map in RAM.
   Disk cache is optional, versioned, bounded, checksum-verified, and
   independently clearable; maintain legally obtained source data in place.
5. No blocking disk I/O on the frame/input thread; background prefetch must
   yield to actual game requests, handle cancellation, and stop under memory
   or thermal pressure.
6. Compare the same save, route, render scale, graphics settings and FPS cap.
   Record FPS distribution/frame-time stalls, Android RAM PSS, native heap,
   GPU residency, I/O throughput/misses/reopens, Android thermal status and
   driver warnings. Do not claim speedups solely from fewer reads.
7. No full Android APK or on-device success claim without evidence from CI
   and an actual phone test. Roll back any regression.

## Phase 0 — Baseline and safety switch

**Goal:** establish a trustworthy baseline and a one-setting kill switch.

- Add `nightlane_worldstream_mode=original` (default) and versioned
  WorldStream configuration with conservative validation.
- Define comparison route/scenarios: initial spawn, 15–20-minute driving
  circuit, return to the same street, high-speed driving, pause/resume, and
  low-memory/thermal situations without intentionally overheating the phone.
- Capture existing renderer logs, Android PSS, current 35% streamer setting,
  45K/15K GPU eviction stats, and game streaming behavior.
- Add a baseline report template with hardware/driver, build SHA and graphics
  profile. No fabricated measurements.

**Exit:** original mode behaves as before, CI passes, baseline captures
repeatable data on the target phone; switch restores original behavior.

**Status: CODE IMPLEMENTED / VERIFICATION PENDING.**

- `src/worldstream.rs` implements a version-1, validated phase switch.
  The default is `original`. Choosing `experimental` is recorded but
  intentionally routed back to Original until another backend exists.
  Invalid values and unsupported configuration versions also use Original.
- `src/config.rs` sets startup configuration and logs build SHA (when
  available), FPS target, render scale, heap, LOD and streamer percentage.
- `src/io.rs` retains the original synchronous byte-range response code and
  handle LRU. Its I/O worker logs aggregate 10-second read/byte/open/eviction/
  error counters without caching additional bytes or reading ahead.
- Android launcher Settings exposes the honest Original/Reserved Experimental
  selector. Wasmtime logs Android model/API and existing PSS measurements.
- `src/worldstream.rs` includes configuration/fallback unit tests.
- The repeatable test checklist and blank results table are in
  [Phase 0 baseline report](WORLDSTREAM_PHASE0_BASELINE.md).
- **Not yet verified:** successful CI build/tests, playable Android APK,
  baseline captures on a physical Galaxy S25+, and comparison against the
  previous stable APK. Do **not** mark Phase 0 finished before these checks.

## Phase 1 — Instrument the actual streaming path

**Goal:** find out where time and RAM really go, without changing reads.

- Add low-overhead interval counters to `src/io.rs`: number of requests,
  bytes, latency histogram / slow-read counts, file opens/reopens, errors,
  active handles, I/O queue depth, and read size distribution.
- Separate game-request work from file opening, reading and wake/notify delay.
- Add optional diagnostic sampling (do not log every request on the hot path).
  Use coarse hashed/file-ID statistics for hot assets without leaking user
  filesystem paths.
- Correlate I/O spikes with game freezes, RAM and thermals.

**Exit:** same content/behavior as original; added logging costs negligibly
under repeatable routes; identified major I/O or streaming bottlenecks.

**Status:** NOT STARTED.

## Phase 2 — Optimize existing I/O and handle residency

**Goal:** reduce unnecessary file-open/lookup work without an asset rewrite.

- Profile the existing 1,024-entry handle LRU. Make it configurable with a
  tested safe range; consider a more efficient eviction data structure only
  if the current full-cache scan is measurable.
- Cache immutable manifest lookups and metadata where profiling supports it.
- Preserve exactly the existing byte-range responses; no speculative
  cancellation of game requests.
- Measure handle churn, I/O tail latency and memory/file-descriptor pressure.

**Exit:** no content mismatches/read errors, no regression in first-visit
load times, and repeatable improvements where Phase 1 found churn.

**Status:** NOT STARTED.

## Phase 3 — Bounded asynchronous read-ahead

**Goal:** preload likely future *file ranges*, not an imaginary full map.

- Add a small opt-in, byte-limited and time-limited read-ahead queue
  based on observed sequential/repeated file-range access. Do **not** assume
  file IDs map one-to-one to geographic GTA cells.
- Foreground game requests always take priority. Deduplicate overlapping
  requests; expire predictions, cancel on level change/teleport, pause in
  background and on Android memory/thermal pressure.
- Read on background worker(s), never blocking render/input. Do not
  retain decoded textures, world objects or GPU resources without engine
  ownership/restore contracts.

**Exit:** cache-hit rate and hit cost measured; memory stays below explicit
  limits; driving stalls improve without worse I/O contention. Fallback works.

**Status:** NOT STARTED.

## Phase 4 — Unified memory-budget controller

**Goal:** constrain optional caches and reduce redundant resident copies.

- Track guest heap, CPU buffer backups, file-range cache, staging memory and
  renderer residency as *distinct* categories; avoid treating GPU bytes as
  Android PSS. Estimate conservative total retained cache bytes.
- Define soft/hard ceilings and a prioritized response to Android memory
  callbacks: stop prefetch, discard expired file-range data, shrink optional
  caches, then request safe renderer cleanup.
- Preserve guest-owned resources, critical texture uploads and all recovery
  copies needed to restore evicted GPU buffers.

**Exit:** peak PSS and 1% lows measured on the same route, zero corrupted
  assets/saves and no increased out-of-memory crashes.

**Status:** NOT STARTED.

## Phase 5 — Spatial-cell feasibility and dependency map

**Goal:** establish whether the game exposes reliable world-position to
asset relationships before introducing Minecraft-like cells.

- Research/trace *authorized* engine streaming hooks and observable requests;
  gather sample location -> asset correlation without modifying user data.
- Define candidate geographic cells, LOD bands, interiors/mission pins, and
  a future-vehicle-direction prefetch cone. Distinguish terrain, collisions,
  audio, traffic and texture residency.
- Run in **observe-only mode**; do not forcibly unload engine assets. If
  enough dependency information cannot be obtained, retain predictive
  file-range caching instead of claiming true cell streaming.

**Exit:** repeated travel produces stable correlations, and a documented
  safe engine hand-off or an explicit no-go decision.

**Status:** NOT STARTED (feasibility dependent).

## Phase 6 — Optional persistent processed-data cache

**Goal:** avoid repeated preprocessing where data can be identified safely.

- Cache only derived reusable information for user-supplied assets, keyed by
  upstream build/hash, source metadata and Nightlane cache-format version.
- Write atomically, validate checksums, cap disk use, offer clear/rebuild,
  preserve original user files, invalidate on version/content mismatch.
- Prioritize a *measured* repeated cost (such as safe metadata parsing);
  don't duplicate or ship a prebuilt proprietary map image.

**Exit:** warm-start gains verified; cold-start and out-of-space remain safe;
  cache deletion leaves gameplay unchanged.

**Status:** NOT STARTED.

## Phase 7 — Guarded integration / selective engine-streaming replacement

**Goal:** only if Phase 5 proves ownership/dependency paths, offer a
spatially aware loading policy without breaking the original game.

- Prototype a limited map region, resident/prefetch/unloaded states and
  directional prediction behind a feature flag.
- Coordinate requests with the game's existing streamer and asset lifetime
  rules. Pin mission/physics/collision-critical assets until the engine
  releases them. Handle teleport, high-speed travel and interiors.
- Shadow-compare results and performance against the original engine,
  region-by-region. Switch back instantly when validation fails.

**Exit:** no missing terrain, collisions, stuck NPCs, broken saves or
  regressions across the test matrix; statistically meaningful and
  repeatable PSS/stutter benefit. If not achievable, keep improvements from
  Phases 1–6 and do not force a full rewrite.

**Status:** NOT STARTED (conditional).

## Phase 8 — Benchmark, tune, and staged rollout

**Goal:** make the winning pieces selectable, documented and stable.

- Compare Original vs each experimental phase over the same repeated routes,
  at realistic device temperatures and varied speeds.
- Tune only measured knobs (RAM cache, read-ahead horizon, file handles,
  target region count). Expose simple presets, plus clear rollback.
- Test loss/recreation of Vulkan surface, app lifecycle, thermal throttling,
  low storage, corruption and missing game assets.
- Include CI unit tests and final real-device test notes before release.

**Exit:** multiple runs establish the regression/benefit profile, CI passes,
  no game-file mutation, and users can select Original at all times.

**Status:** NOT STARTED.

## Execution / decision record

| Phase | Deliverable | Status | Evidence |
|---|---|---|---|
| 0 | Baseline + safety switch | Code committed; CI/device verification pending | [Baseline test template](WORLDSTREAM_PHASE0_BASELINE.md) |
| 1 | Streaming instrumentation | Not started | — |
| 2 | Existing I/O improvements | Not started | — |
| 3 | Bounded asynchronous read-ahead | Not started | — |
| 4 | RAM-budget manager | Not started | — |
| 5 | Spatial feasibility | Not started | — |
| 6 | Optional persistent derived cache | Not started | — |
| 7 | Guarded spatial integration | Not started; conditional | — |
| 8 | Benchmarks and rollout | Not started | — |

Update this table **only when implementation and corresponding test evidence
exist**. No automatic move to the next phase. Each phase lands as an
independently reviewable commit/PR with: changed files, risk, build checks,
device tests where relevant, before/after metrics and rollback instructions.

## Suggested first step

Verify **Phase 0** first: build/tests, unchanged Original-mode gameplay, and
a repeatable exported log using the [baseline report](WORLDSTREAM_PHASE0_BASELINE.md).
Then start Phase 1 if the evidence supports it. The current architecture does
not yet expose enough information for safe geographic chunk eviction.

## Related source files

- `src/io.rs`: actual manifest-file range serving and handle LRU
- `src/config.rs`: existing streamer budget configuration
- `src/gpu.rs`: GPU residency, recovery and memory-pressure handling
- `android/app/src/main/java/dev/muguet/LauncherActivity.java`: user settings
- `docs/NIGHTLANE_RENDERER.md`: separate graphics/thermal roadmap
