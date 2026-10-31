// Nightlane Android: safe, diagnostic-only detection of a stuck Movie Manager.
//
// A mission retry can leave the WASM main thread spending successive 30-second
// profiling windows asleep inside Update > Render Update > Movie Manager.
// Frames can still be presented (one draw per frame) while gameplay is stuck.
// We do NOT change gameplay, skip a film, edit engine memory or end the process.
// Android can optionally offer the player a manual return to the launcher.

use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};

static CONSECUTIVE_MOVIE_SLEEP_WINDOWS: AtomicU8 = AtomicU8::new(0);
// Separate main-thread signature seen after the user opens the pause menu
// during Armenian3 loading. Only the Movie Manager signature is eligible
// without the additional GPU no-present liveness gate below.
static CONSECUTIVE_PAUSE_SLEEP_WINDOWS: AtomicU8 = AtomicU8::new(0);
static PAUSE_STALL_ANNOUNCED: AtomicBool = AtomicBool::new(false);
const PROFILE_MARKER: &str = "frame profile, main thread, last 30 s:";
const SLEEP_MARKER: &str = "Update>Render Update>Movie Manager [sleep]";
const PAUSE_SLEEP_MARKER: &str = "Update>Common Main>CPauseMenu Update [sleep]";
const NO_PRESENT_FOR_PAUSE_MS: u64 = 60_000;
const MIN_MOVIE_SLEEP_PERCENT: u8 = 95;
const REQUIRED_WINDOWS: u8 = 2;

/// Returns Some(updated count) only for a main-thread profiler sample. Other
/// engine logs (audio, background worker messages) do not reset the diagnosis.
fn next_profile_count_for(previous: u8, line: &str, marker: &str) -> Option<u8> {
    let start = line.find(PROFILE_MARKER)?;
    let sample = line[start + PROFILE_MARKER.len()..].trim();
    let percent_end = sample.find('%')?;
    let percent = sample[..percent_end].trim().parse::<u8>().ok()?;
    if percent >= MIN_MOVIE_SLEEP_PERCENT && sample.contains(marker) {
        Some(previous.saturating_add(1).min(REQUIRED_WINDOWS))
    } else {
        Some(0)
    }
}

fn next_profile_count(previous: u8, line: &str) -> Option<u8> {
    next_profile_count_for(previous, line, SLEEP_MARKER)
}

fn next_pause_profile_count(previous: u8, line: &str) -> Option<u8> {
    next_profile_count_for(previous, line, PAUSE_SLEEP_MARKER)
}

fn pause_recovery_eligible(consecutive_windows: u8, no_present_for_60s: bool) -> bool {
    consecutive_windows >= REQUIRED_WINDOWS && no_present_for_60s
}

pub(crate) fn observe_engine_line(line: &str) {
    // Avoid locks, allocations, JNI, file I/O or render-thread interaction.
    if !line.contains(PROFILE_MARKER) { return; }
    let previous = CONSECUTIVE_MOVIE_SLEEP_WINDOWS.load(Ordering::Relaxed);
    if let Some(next) = next_profile_count(previous, line) {
        CONSECUTIVE_MOVIE_SLEEP_WINDOWS.store(next, Ordering::Relaxed);
        if previous < REQUIRED_WINDOWS && next >= REQUIRED_WINDOWS {
            crate::movie_trace::suspected_stall();
            eprintln!("[nightlane-movie-stall] confirmed=1 consecutive_30s_profiles={} action=manual_recovery_only save_files=untouched",
                next);
        } else if previous >= REQUIRED_WINDOWS && next == 0 {
            eprintln!("[nightlane-movie-stall] confirmed=0 reason=main_thread_resumed");
        }
    }
    let pause_previous = CONSECUTIVE_PAUSE_SLEEP_WINDOWS.load(Ordering::Relaxed);
    if let Some(pause_next) = next_pause_profile_count(pause_previous, line) {
        CONSECUTIVE_PAUSE_SLEEP_WINDOWS.store(pause_next, Ordering::Relaxed);
        if pause_previous >= REQUIRED_WINDOWS && pause_next == 0 {
            PAUSE_STALL_ANNOUNCED.store(false, Ordering::Relaxed);
            eprintln!("[nightlane-pause-stall] candidate=0 reason=main_thread_profile_changed");
        }
    }
}

/// True after at least two consecutive 30-second main-thread profiler windows
/// show >=95% in the Movie Manager sleep. This is a *suspected* game-state
/// deadlock, not a proof that media decoding or Bink caused it.
pub(crate) fn suspected() -> bool {
    CONSECUTIVE_MOVIE_SLEEP_WINDOWS.load(Ordering::Relaxed) >= REQUIRED_WINDOWS
}

/// Read-only hint for the recovery HUD: 0=none, 1=Movie Manager stall,
/// 2=Pause Menu stall after *both* 2 main-thread 30-s profiles AND 60-s
/// without any new present attempts. An ordinary working pause menu has
/// continued presentations and must not display recovery.
pub(crate) fn suspected_kind() -> u8 {
    if suspected() { return 1; }
    let windows = CONSECUTIVE_PAUSE_SLEEP_WINDOWS.load(Ordering::Relaxed);
    let no_present = crate::gpu::no_present_attempt_for_ms(NO_PRESENT_FOR_PAUSE_MS);
    if !pause_recovery_eligible(windows, no_present) {
        PAUSE_STALL_ANNOUNCED.store(false, Ordering::Relaxed);
        return 0;
    }
    if !PAUSE_STALL_ANNOUNCED.swap(true, Ordering::Relaxed) {
        eprintln!("[nightlane-pause-stall] confirmed=1 consecutive_30s_profiles={} no_present_at_least_ms={} action=manual_recovery_only save_files=untouched",
            windows, NO_PRESENT_FOR_PAUSE_MS);
        crate::movie_trace::suspected_stall();
    }
    2
}

#[cfg(test)]
mod tests {
    use super::*;

    const STALLED: &str = "[wasm] frame profile, main thread, last 30 s: 100% Update>Render Update>Movie Manager [sleep]";
    const RUNNING: &str = "[wasm] frame profile, main thread, last 30 s: 61% Wait for render [semaphore]; 2% Update>Game Update";
    const PAUSE_SLEEP: &str = "[wasm] frame profile, main thread, last 30 s: 100% Update>Common Main>CPauseMenu Update [sleep]";

    #[test]
    fn only_two_consecutive_stalled_profiles_trigger() {
        let n = next_profile_count(0, STALLED).unwrap();
        assert_eq!(n, 1);
        assert_eq!(next_profile_count(n, STALLED), Some(2));
        assert_eq!(next_profile_count(2, STALLED), Some(2));
    }

    #[test]
    fn normal_main_thread_sample_clears_suspected_stall() {
        assert_eq!(next_profile_count(2, RUNNING), Some(0));
        assert_eq!(next_profile_count(1, "[wasm] frame profile, main thread, last 30 s: 20% Update>Render Update>Movie Manager [sleep]"), Some(0));
    }

    #[test]
    fn pause_menu_requires_two_windows_and_no_present() {
        assert_eq!(next_pause_profile_count(0, PAUSE_SLEEP), Some(1));
        assert_eq!(next_pause_profile_count(1, PAUSE_SLEEP), Some(2));
        assert!(!pause_recovery_eligible(0, true));
        assert!(!pause_recovery_eligible(1, true));
        assert!(!pause_recovery_eligible(2, false)); // normal paused but rendering
        assert!(pause_recovery_eligible(2, true));
        assert_eq!(next_pause_profile_count(2, RUNNING), Some(0));
        assert_eq!(next_profile_count(1, PAUSE_SLEEP), Some(0));
    }

    #[test]
    fn unrelated_logs_never_trigger_or_clear_stall() {
        assert_eq!(next_profile_count(1, "[eng] [Audio] Script mission fail sound"), None);
        assert_eq!(next_profile_count(1, "[wasm] frame profile, render thread, last 30 s: 100% Wait for Main [semaphore]"), None);
        assert_eq!(next_profile_count(1, "[wasm] frame profile, main thread, last 30 s: 98% Update>Render Update>Movie Manager [sleep]"), Some(2));
    }
}
