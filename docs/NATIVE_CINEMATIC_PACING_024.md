# Native-only 0.2.4: single-clock frame pacing and cutscene/audio timing

## Why

The earlier native Android launcher configured **both** the game's
`-frameLimit=2` 30 FPS/half-VSync mode and
`MUGUET_RENDER_FPS_LIMIT=30` in the Vulkan renderer. These are separate
clocks. The renderer can wait even after a guest game frame was internally
paced, adding inconsistent spacing under a heavy cutscene. This is a plausible
source of stutter, **not yet confirmed on a physical device**.

## Correction

- Target **30 FPS**: game mode 2 (half VSync), no extra host FPS cap.
- Target **60 FPS**: game mode 1, no extra host cap.
- Target **24 or 42 FPS** (or other intermediate limits): game mode 1 (60
  base), host renderer handles the lower requested cap. Do not combine
  guest mode 2 (30) with host 24.
- Target **0 FPS**: guest mode 0 and no extra host cap, subject to physical
  hardware and system thermal management.
- Thermal protection remains on and may lower a game's maximum frame rate.
  Original frame queue, renderer, shader compiler, game state, saves and
  audio mixer remain intact.

## Diagnostics

The renderer reports voluntary host-sleep count/average/max/total in its
10-second log, **separately** from device-poll and GPU fence waits. The
existing audio producer report now includes its **worst single-second
production rate** and count of audio-starved one-second windows over the
reporting period. This catches intermittent shortages hidden in a ten-second
mean. A normal uninterrupted audio producer supplies about 48,000 stereo
frames per second.

## On-device checks

1. At the same save and cutscene, keep video quality and render scale
   identical. Compare 0.2.3 vs 0.2.4 at 30 FPS.
2. Observe average FPS, 1% lows/p99 frame gaps, renderer host sleep time,
   audio producer 10-second average, min1s audio production, Android
   underruns, and cutscene sound/image sync.
3. If source audio stays around 48k but Android underruns increase, investigate
   AudioTrack scheduling, playback queue and pause/flush behavior.
4. If audio output is fine but 1-second production falls far below 48k when
   frame gaps grow, profile guest simulation, streaming, shader compilation,
   GPU waits or mission/cutscene logic; buffering isn't a cure.
5. If both audio production and frames remain low after removing redundant
   pacing, further engine and rendering changes depend on captured logs.

The engine is native wasm2c; no old Wasmtime runtime is re-added. No device
performance claim should be made from compilation or unit tests alone.
