# Phase 0 — repeatable Android performance baseline

Status: instrumentation specification; **not yet wired into runtime**.

## Keep test settings fixed
- Device: Samsung SM-S936U, Android 15, Adreno 830
- Backend: original; FPS cap 30; game heap cap 5120 MB
- Streamer budget 0.350; render size 780×360; GPU buffer soft limit 45k / eviction batch 15k
- Keep route, weather, traffic, brightness, battery state and test duration consistent.

## Sample telemetry every 5 seconds (and on a freeze)
Write monotonic timestamp and a single JSON line with:
- fps instantaneous/rolling average, p50/p95/p99 frame time, freeze duration, frame pacing misses
- Android process PSS, private dirty, native heap allocated, WASM committed/used/maximum
- GPU buffer active/allocated/evicted/restored, GPU cache bytes and restore rate
- IO open handles, opens, evictions, reads, bytes, failures, queue depth, read latency p50/p95
- Android thermal status (PowerManager.getCurrentThermalStatus), thermal headroom if supported, battery temperature (explicitly label battery, not CPU/GPU), battery current if available
- Renderer backend, render scale, driver version and game build SHA

## Safety and interpretation
Never treat PSS as WASM heap size or GPU VRAM. Counts of evictions are cumulative; compute rates from deltas. A 30 FPS cap masks spare capacity. Do not report temperature estimates as measured temperatures.

## Validation
1. Cold start and capture startup freezes.
2. Idle for 5 minutes.
3. Drive the same 10-minute route twice, logging coordinates only locally if needed.
4. Repeat after a device cooldown under similar conditions.
5. Compare p95/p99 frame time, RAM plateau/slope, eviction/restore rates, IO p95, thermal status and battery temperature.
6. Reject changes that increase crashes, asset corruption or thermal severity; preserve game files and saves.

## Phase 0 exit criteria
- No missing samples or unbounded logging overhead
- All metrics explicitly identify their measurement source
- Two repeatable runs with comparable summaries
- Baseline frozen before Phase 1 chunk streaming work
