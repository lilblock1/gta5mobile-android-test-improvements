// Nightlane Android: safe, diagnostic-only detection of a stuck Movie Manager.
//
// A mission retry can leave the WASM main thread spending successive 30-second
// profiling windows asleep inside Update > Render Update > Movie Manager.
// Frames can still be presented (one draw per frame) while gameplay is stuck.
// We do NOT change gameplay, skip a film, edit engine memory or end the process.
// Android can optionally offer the player a manual return to the launcher.

use std::sync::atomic::{AtomicU8, Ordering};

static CONSECUTIVE_MOVIE_SLEEP_WINDOWS: AtomicU8 = AtomicU8::new(0);
const PROFILE_MARKER: &str = "frame profile, main thread, last 30 s:";
const SLEEP_MARKER: &str = "Update>Render Update>Movie Manager [sleep]";
const MIN_MOVIE_SLEEP_PERCENT: u8 = 95;
const REQUIRED_WINDOWS: u8 = 2;

/// Returns Some(updated count) only for a main-thread profiler sample. Other
/// engine logs (audio, background worker messages) do not reset the diagnosis.
fn next_profile_count(previous: u8, line: &str) -> Option<u8> {
    let start = line.find(PROFILE_MARKER)?;
    let sample = line[start + PROFILE_MARKER.len()..].trim();
    let percent_end = sample.find('%')?;
    let percent = sample[..percent_end].trim().parse::<u8>().ok()?;
    if percent >= MIN_MOVIE_SLEEP_PERCENT && sample.contains(SLEEP_MARKER) {
        Some(previous.saturating_add(1).min(REQUIRED_WINDOWS))
    } else {
        Some(0)
    }
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
}

/// True after at least two consecutive 30-second main-thread profiler windows
/// show >=95% in the Movie Manager sleep. This is a *suspected* game-state
/// deadlock, not a proof that media decoding or Bink caused it.
pub(crate) fn suspected() -> bool {
    CONSECUTIVE_MOVIE_SLEEP_WINDOWS.load(Ordering::Relaxed) >= REQUIRED_WINDOWS
}

#[cfg(test)]
mod tests {
    use super::*;

    const STALLED: &str = "[wasm] frame profile, main thread, last 30 s: 100% Update>Render Update>Movie Manager [sleep]";
    const RUNNING: &str = "[wasm] frame profile, main thread, last 30 s: 61% Wait for render [semaphore]; 2% Update>Game Update";

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
    fn unrelated_logs_never_trigger_or_clear_stall() {
        assert_eq!(next_profile_count(1, "[eng] [Audio] Script mission fail sound"), None);
        assert_eq!(next_profile_count(1, "[wasm] frame profile, render thread, last 30 s: 100% Wait for Main [semaphore]"), None);
        assert_eq!(next_profile_count(1, "[wasm] frame profile, main thread, last 30 s: 98% Update>Render Update>Movie Manager [sleep]"), Some(2));
    }
}
