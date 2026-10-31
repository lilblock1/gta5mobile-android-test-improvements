# Phase 1 — World streaming (experimental)

Status: **planning and isolation only; no new streaming backend enabled**.

## Objective
Improve world/interior transitions and bound I/O and resource residency without changing game data or saves. The original backend remains the default and fallback.

## Baseline and failure gate
- Device: Galaxy S25+ (SM-S936U); target 30 FPS; render scale 0.5; WASM heap cap 5 GiB; streamer fraction 0.35.
- Repro: enter Michael's house during the repo mission, walk down the stairs; game reportedly hangs permanently and logging stops.
- Recent log includes GPU adapter-resource panic during startup, memory pressure, GPU readback stalls and queued-frame waits. None independently proves the staircase root cause.
- **Release gate:** do not enable a new backend by default or call it fixed until the staircase test completes on device without a permanent hang.

## Incremental milestones
1. **Instrumentation:** log last successful present, render command submission/completion, stream queue depth, pending read ages, resource eviction requested/completed, and memory pressure. Use bounded counters and periodic sampling; avoid per-resource spam.
2. **Read scheduling:** measure I/O latency and priority; implement an opt-in bounded request queue, with backpressure and cancellation. Preserve original read semantics and byte-for-byte results.
3. **Residency:** add safe resource lifetime tracking and eviction only for unreferenced, GPU-completed buffers; preserve in-flight buffers and rehydration paths. Never delete files or saves.
4. **Nearby world cache:** prototype opt-in spatial grouping/preload only after the actual game asset metadata and visibility dependencies are understood. Do not assume arbitrary file offsets correspond to map chunks.
5. **Validation:** compare original vs experimental with identical settings and save; record hang/recovery, p95/p99 frame time, PSS, thermal status, I/O misses and graphics correctness. Roll back on missing geometry, corrupted assets, crash, or regression.

## Known risk
An Android low-memory callback is not proof of completed GPU eviction. A GPU adapter panic or a renderer synchronization deadlock requires its own diagnosis. Streaming optimizations cannot be assumed to fix these failures.
