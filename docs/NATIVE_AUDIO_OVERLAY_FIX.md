# Native ARM64 audio clipping and graphics-ring overlay correction

## Fixed in code

- Never draw ordinary *graphics ring* diagnostics, `Futex wait` messages or
  progress telemetry over gameplay. Those remain in `nightlane-native.log`.
- True startup/graphics validation failures appear in a compact, three-line
  banner at top-left instead of covering the center of the screen.
- **Joystick and gameplay HUD are untouched and remain visible.**
- Set `running=true` before starting audio, otherwise the audio worker can
  immediately exit during startup.
- Send 1024-frame stereo blocks using Android's supported GAME usage, MUSIC
  content type, and a 8192-frame output queue; write all partial blocks and
  use Android's audio thread priority. Audio output stays separate from
  gameplay frame rate and resumes after backgrounding.
- Correct capacity checks to recognize two shorts per stereo frame.
- Recover from ring overrun by skipping overwritten samples, rather than
  reading a stale window. Track cumulative underrun/overrun frames.
- Pad missing mixer frames with silence and apply short ramps at the start
  and end of audio gaps to limit clicks. Apply a bounded soft limiter with
  15% headroom to loud samples to prevent PCM hard clipping.
- Log audio output frames, short writes and underrun/overrun counts. These
  counters distinguish a slow engine audio producer from a PCM peak problem.

## Device acceptance

1. Confirm that the movement joystick remains visible and responsive.
2. Confirm ordinary graphics ring messages do not cover the game.
3. Play sustained dialogue, driving radio and explosions at moderate volume.
4. Check whether crackles coincide with audio underflow in exported logs.
5. Background the game and resume; audio should return without freezing.
6. Compare performance and latency against the previous native-only version.

The **source fixes require a new APK**. GitHub Actions checks build and
regression tests; audible performance requires a physical handset test.
