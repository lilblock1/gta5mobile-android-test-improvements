# Nightlane — Armenian3 Pause Menu loading stall (Galaxy S25+)

**Status:** Experimental, non-destructive manual recovery extension.
This is **not** a fix for mission state, Bink playback, or the actual
game-thread wait condition. Changes are stacked on movie diagnostics PR #4.

## New evidence: latest user-provided Logs.7z

The 7z archive contains a 21,398,852-byte `Logs.txt` (207,165 text
lines across **eight appended game starts**). The final start is
`605d812f852db5e12d5ad0b7df6a02fb08f5e1bd` with Android 16 /
Samsung SM-S936U, Wasmtime, Turnip, 30 FPS, 0.5 render scale, 35%
streamer allocation, 5 GiB maximum guest heap, and WorldStream Original.

In the eighth game session:

1. Intro `ARMENIAN_3_INT_SEQ` progresses through `audio_play`,
   `cutscene_state`, stop, and unload.
2. At elapsed trace ~172.032s the engine receives
   `ARMENIAN_3_MCS_3`; audio preparation and activation occur.
3. At ~172.201s, `audio_ready=true`. At ~172.208s,
   `Armenian3` stops scripted conversation. Scene events are created.
4. `ob_tv` disables `SE_MICHAELS_HOUSE_RADIO` and
   `CTVChannelInfo::SetDirty(true)` occurs at ~173.436s.
5. **New crucial clue:** immediately afterward the game prints
   `[ui] PauseMenu: Activation requested - menus will now stream` and
   **`OPENING PAUSE MENU DUE TO USER INPUT`**. We cannot infer why the
   player requested it or whether the same underlying movie wait was
   already about to occur.
6. The renderer stops making present attempts at count **5,108**.
   Watchdog records 11,166ms, then 41,169ms, 71,172ms, eventually
   **221,187ms** since the last present attempt.
7. Unlike previous sessions, repeated main-thread profiler reports now
   read **`100% Update>Common Main>CPauseMenu Update [sleep]`**
   rather than `Movie Manager [sleep]`; render thread remains
   **`100% Wait for Main [semaphore]`**.
8. The old Movie Manager-only recovery detector therefore **never
   triggers** and the expected manual recovery button is absent.
9. File I/O remains normal and almost idle: approximately
   7 reads / 10 seconds, `read_errors=0`, `slow_reads_50ms=0`,
   `max_pending_slots=1`, with ~229 KiB of data per interval.
10. Final PSS is **7,578,262 KiB (~7.23 GiB)** and Android signals
    `TRIM_MEMORY_RUNNING_LOW` (level 20), with 1,393 MiB available.
    Two `LOW_MEMORY` records belong to **earlier process exits**
    in the appended Android history. This session has no confirmed
    new OOM exit.

## Change: narrowly scoped manual recovery

- The same Rust main-thread profiler checker now recognizes
  `CPauseMenu Update [sleep]` in its own independent counter.
- **PauseMenu recovery eligibility requires both:** two consecutive
  main-thread 30-second profiler windows with >=95% spent in that
  PauseMenu sleep, **and** at least 60 seconds without a GPU present
  attempt while the Vulkan surface is neither suspended nor occluded.
- Before the first successful GPU present attempt, no PauseMenu
  recovery is eligible. A normal paused-but-rendering game stays
  ineligible regardless of how long the pause menu is displayed.
- If eligible, a one-time `[nightlane-pause-stall] confirmed=1`
  breadcrumb and the existing read-only movie lifecycle snapshot
  are emitted. The existing Android recovery button appears.
- Recovery is **never automatic**. The user may choose **Keep waiting**
  or confirm **Return to launcher**; unsaved mission progress may be
  lost but no game file/saved file is deleted or edited by the patch.
- The original Movie Manager recovery detection remains intact;
  neither path changes guest code, mission behavior, GPU lifetimes,
  or any synthetic movie callback.

## Verification checklist

- [ ] Rust tests, including new PauseMenu parser/gating test, pass.
- [ ] Android APK builds and packages the new JNI declaration.
- [ ] A normal gameplay / pause menu **with render attempts** does
      not show the recovery button.
- [ ] A temporary loss of rendering <60s does not trigger it.
- [ ] An intentional app background/surface occlusion never triggers
      PauseMenu recovery.
- [ ] Reproduced Armenian3 `CPauseMenu Update [sleep]` after 60s
      offers the button, but does not restart automatically.
- [ ] Selecting Keep waiting leaves the game running.
- [ ] Selecting Return to launcher ends only the isolated game
      process; app relaunch succeeds and saves are intact.
- [ ] Original Movie Manager stall detection still works.
- [ ] Exports contain `[nightlane-pause-stall]` and
      `[hud] loading-stall suspected: kind=pause_menu_sleep_and_no_present_60s`
      when this exact condition recurs.

**Root cause unresolved:** the pause menu may itself be waiting on
movie/Scaleform/game-script state, but this log cannot prove which
semaphore, callback or guest-side component is responsible. A verified
game-side wait/wake contract is still required for a true fix.
