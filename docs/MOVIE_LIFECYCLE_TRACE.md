# Nightlane — movie and cutscene lifecycle tracing (experimental)

**Scope: read-only diagnostics.** This experimental branch is stacked on
the Movie Manager manual recovery patch (PR #3), itself stacked on the
WorldStream diagnostic branch. Do not merge before build and phone testing.

## What the user's Logs2.7z actually shows

In the last Galaxy S25+ session, shortly before the frozen frame:

1. Armenian3 cutscene audio `ARMENIAN_3_MCS_3` was stopped/reset as
   a skipped track.
2. The game logged `[Audio_CutsceneAudio] Event ARMENIAN_3_MCS_4`.
3. `Cutscene Audio Prepare (new): ARMENIAN_3_MCS_4` followed.
4. The audio engine logged **`Prepared and activating cutscene track`**
   and **`replied: true`** when queried for audio readiness.
5. The cutscene created ped face animations / draw-distance events.
6. The renderer stopped receiving new present attempts. The main-thread
   profiler then repeatedly reported
   `100% Update>Render Update>Movie Manager [sleep]`.
7. The manual recovery detector reported the stalled Movie Manager and
   the user later confirmed Return to Launcher.

An earlier attempt in the same archive reached `ARM3_FAIL` and a
`replay_controller` **RETRY** button before a similar stall. The archive
does not prove that Bink unavailability caused this particular transition:
a `Bink is not available on the WebAssembly build` startup error occurs
even when other game scenes continue to run.

**Key distinction:** cutscene **audio** prepared=true is not proof of
video playback, movie-completion callbacks, scene activation or successful
script resumption.

## New structured trace

New `[nightlane-movie-trace]` records include sequence, monotonic elapsed
milliseconds, event-to-event delta, a narrowly categorized stage, and a
sanitized/length-limited mission or audio event identifier.

Tracked stages from observable existing engine messages:

| Stage | Observable engine message |
|---|---|
| `movie_open_unavailable` | The existing Wasm Bink unavailable error |
| `mission_failure` | `TRIGGER_MUSIC_EVENT: ..._FAIL` |
| `retry_selected` | RETRY frontend sound from `replay_controller` |
| `retry_clear_area` | `COMMAND_CLEAR_AREA` from replay controller |
| `audio_event` | Cutscene audio Event ID, e.g. `ARMENIAN_3_MCS_4` |
| `audio_prepare` | Cutscene Audio Prepare (new) |
| `audio_activated` | Prepared and activating cutscene audio track |
| `audio_ready` | Audio queried and replied true |
| `audio_stop`, `audio_reset` | Track stopped and reset |
| `cutscene_state` | Changes to logged cutscene state number |
| `cutscene_stopped`, `cutscene_unloaded`, `prestream_cancel` | Existing streamer/cutscene cleanup messages |
| `scene_created` | Cutscene draw-distance events created |

The module rejects unrelated game logs, throttles repeated identical
events for three seconds, bounds the dedup table to 64 entries and the
history to 24 events, and retains only sanitized 64-character identifiers.
It creates no game data cache, touches no mission state, and does not
change thread scheduling or rendering.

At a suspected Movie Manager stall:

```text
[nightlane-movie-trace-stall] count=... last_mission=... last_audio=... last_cutscene=... completion_callback=not_instrumented recent="..."
[nightlane-movie-stall] confirmed=1 ... action=manual_recovery_only ...
```

The existing GPU freeze-watchdog line also includes `movie_trace="..."`.
At startup `[nightlane-movie-trace-host]` records any imported WASM
host-call *names* containing movie, Bink, video or cutscene. It does not
claim to see all internal callbacks.

## What this cannot yet observe

The proprietary game/guest internals do not expose documented native
`movie_complete` or `script_resumed` host callbacks in this integration.
The absence of a completion log should **not** be interpreted as proof
the callback never happened. We must identify the actual guest-side
interface before trying to call or emulate it.

## Reproduction / acceptance test on Galaxy S25+

1. Build and install an APK from the successful CI run for this PR. Keep
   your previous APK and back up saves where appropriate.
2. Start the game with the same graphics, 30 FPS, same save and mode.
3. Drive/run through Armenian3 normally, then cause a mission failure
   and select Retry. Note whether the stall occurs on a transition.
4. If a stall recurs, wait for the user-confirmed recovery button (it
   appears after two 30-second main-thread profiler windows). Choose
   Return to Launcher, then export the logs.
5. Search logs for `[nightlane-movie-trace]`,
   `[nightlane-movie-trace-stall]`, `[nightlane-freeze]`, and
   `[nightlane-movie-stall]`.
6. Compare the last `audio_event`, `audio_ready`, `scene_created`,
   `cutscene_state`, `cutscene_stopped` and `retry_selected` events.
7. Run a control test where a cutscene succeeds / the main thread
   resumes, to see what additional lifecycle markers appear.
8. Do not call the bug fixed solely because a test did not stall.

## Verification

- [ ] Rust unit tests for trace stage classification, throttling, bounded
      history and sanitized IDs pass.
- [ ] Android APK CI build succeeds.
- [ ] No measurable FPS regression from log filtering.
- [ ] The trace follows the source messages without inventing completion
      events or deleting saved data.
- [ ] A healthy mission/cutscene and failed Armenian3 retry both produce
      comparable trace sequences.
- [ ] Confirm whether cutscene completion and script resumption are actually
      visible through any exposed engine import; if not, document a deeper
      guest instrumentation approach instead of claiming to bypass them.

No automatic movie skip, synthetic completion, hidden checkpoint restore,
auto-restart, or rewrite of saved assets is introduced.
