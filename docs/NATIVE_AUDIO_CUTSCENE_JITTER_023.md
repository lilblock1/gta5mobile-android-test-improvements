# Native-only 0.2.3: choppy sound and cutscene diagnostics/stability

The prior release improved the PCM output limiter and increased the Android
AudioTrack output queue, but **a larger queue cannot create mixer samples when
the native game engine produces too few frames**. Cutscene video may likewise
stutter when the engine, renderer or storage falls behind.

## Changes

- Native C exposes the actual ring occupancy/capacity and cumulative producer
  frame counter to the Java audio worker, without consuming mixer samples.
- The audio thread waits (at most 1.5 seconds) for the native mixer to fill
  one-third of the ring (up to 3072 stereo frames) before priming playback.
  It only starts AudioTrack after real game sound has reached the output queue.
- Output is bounded to approximately 4096 stereo frames rather than filling
  the entire AudioTrack queue. When there's sufficient output buffered, wait
  briefly for the engine to produce another block instead of immediately
  inserting full 1024-frame silence blocks.
- Partial native blocks are read as partial blocks. During a serious shortage,
  small silence blocks preserve the output device clock; the previous
  underrun crossfade and soft limiter remain.
- Every 10 seconds the Android log reports the *game-produced* audio
  frames/s, audio submitted frames/s, source-ring fill, output queue,
  AudioTrack underrun count and producer waiting count. The required output
  production for normal 48-kHz playback is 48000 stereo frames per second.
- Android 48-kHz audio callback thread retains audio scheduling priority.
  Vulkan background shader compilation threads use a *mild* lower priority
  on Android to reduce contention with game simulation and playback.
- Routine engine graphics and streaming log lines stay in the native log
  but do not pass through the JNI activity status callback, avoiding
  unnecessary UI-thread wakeups during cutscenes.
- Vulkan pipeline-cache serialization now waits for **8 seconds without new
  shader requests** and no outstanding compiler jobs, reducing the chance
  of driver cache extraction competing with busy cutscene rendering.

## What this does NOT fix automatically

The native game may fail to produce 48000 stereo frames/s during a
particularly demanding cutscene. No amount of AudioTrack buffering can
repair an ongoing producer slowdown. Similarly, some cutscene stutters
may come from game assets, shaders, GPU readbacks or timing in the compiled
game engine; these require correlated logs and device measurements.

## Validation

1. Run the GitHub native-only ARM64 workflow and unit tests.
2. Play the same cutscene and export logs. In Logcat, look for
   `NightlaneAudio gameProducedFps=` and
   `Native audio: submitted ... underruns ... overruns`.
3. Compare those against the renderer's rolling p99 frame-time gaps,
   skipped draws, shader-pipeline counts, Vulkan readback wait times
   and native file-server slow reads.
4. Test headphones vs phone speaker at moderate volume, beginning and
   ending a cutscene, driving radio, pause/resume, and mission continuation.
5. If game audio production is consistently below 48k stereo frames/s,
   prioritize engine/main-thread profiling and movie/video decode rather
   than adding more buffered silence. If the game produces 48k but Android
   underruns, focus on the output thread and scheduling.

No physical Android device test is performed by GitHub Actions.
