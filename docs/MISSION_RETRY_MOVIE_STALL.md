# Mission failure → Movie Manager loading stall (Galaxy S25+)

**Nightlane Android, unofficial AI-assisted fork of the Grand V / BytArch port.**
This is an experimental diagnostic and player-initiated recovery feature,
not a claim that the underlying mission-retry bug has been fixed.

## Evidence: user-supplied `Logs1.7z`

- Samsung SM-S936U, Android 16/API 36, Wasmtime game path.
- The diagnostic APK reported a synthetic PR merge commit:
  `1f0389eab521e8352ed800c2603b814219fbb3ad`.
- Settings: **30 FPS**, **0.50** render scale (780×360), **35%** asset
  streamer fraction, **5 GiB** guest heap maximum.
- Mission `Armenian3`: `ARM3_FAIL` triggered; `replay_controller`
  played `RETRY` and cleaned the cutscene/audio/nearby area.
- After that, repeated profiler summaries showed:
  `100% Update>Render Update>Movie Manager [sleep]`.
- The game still reported around **30 FPS**, but the GPU submitted only
  **0–1 draw calls/frame** and the engine stopped issuing new I/O reads.
- No GPU device-lost event or fresh I/O read error was logged in the final
  stuck interval. Roughly **7.7 GiB** process PSS was present, so memory
  pressure remains a separate issue.
- Earlier attempts in this *same archive* also ended with a main-thread
  `Movie Manager [sleep]` stall during a cutscene transition.
- The game log reports Bink/video decoding unavailable in the WASM build
  at startup. **This is relevant but not proof the unavailable decoder
  caused this specific mission-retry stall.**

## Proposed containment

- Track only the main-thread 30-second profiler records.
- Declare **suspected stall** after two consecutive records show >=95%
  in `Update>Render Update>Movie Manager [sleep]`; unrelated sound,
  input, frame-rate and I/O records cannot trigger it.
- On the Android touch HUD, display a small **Loading stalled? Return to
  launcher** button when suspected. It is never an automatic action.
- A second user confirmation states that unsaved mission progress may
  be lost. Only then does the activity launch the normal launcher and
  finish the separate `:game` process. Saved games and installed game
  data are not modified by this feature.
- Any normal main-thread profiler report clears the alert.
- Do **not** forcibly advance the movie clock, patch unknown WASM addresses,
  destroy renderer resources, delete streaming files, or overwrite a save.
  Doing those without engine-level ownership may corrupt the mission state.

## What still needs fixing

This containment does **not** make the failed mission checkpoint retry work.
The actual root cause must be isolated at the game/host movie-state boundary.
Compare the transition after a normal cutscene skip vs failed mission retry,
then determine if the game is waiting for an unavailable video-completion
callback, a timing/scheduler signal, or another internal movie dependency.
Do not bypass native cutscene state blindly: scripts, audio, physics, and
mission completion may depend on legitimate callbacks.

## Verification checklist

- [ ] Rust detector unit tests pass.
- [ ] Android APK build succeeds on GitHub Actions.
- [ ] Normal gameplay shows no recovery button.
- [ ] One `100%` main-thread profile does not show a recovery button.
- [ ] Two consecutive 95%+ Movie Manager profiles do show the recovery button.
- [ ] Select **Keep waiting**: nothing restarts; game files unchanged.
- [ ] Select **Return to launcher**: `:game` process ends, launcher works,
      saves remain; a new Play starts a fresh Wasmtime runtime.
- [ ] A later healthy main-thread profiler record clears the alert.
- [ ] Manually reproduced Armenian3 failure/Retry with fresh log; determine
      whether the underlying stall persists (expected until root fix).

This branch is kept separate from the underlying WorldStream/diagnostic PR
to avoid silently changing its asset-loading experiments.
