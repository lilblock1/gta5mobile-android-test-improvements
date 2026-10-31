// Nightlane: bounded movie/cutscene lifecycle breadcrumbs from observable
// engine logs. Diagnostic only: does not inject callbacks, alter guest memory,
// advance cutscene clocks, skip movies, rewrite saves, or touch game assets.
//
// Important: the host currently sees *engine log messages*, not the native
// movie completion callback. "audio_ready" is NOT "movie_finished".
use std::collections::{HashMap, VecDeque};
use std::sync::{LazyLock, Mutex};
use std::sync::atomic::{AtomicI32, Ordering};
use std::time::Instant;

const MAX_EVENTS: usize = 24;
const MAX_KEYS: usize = 64;
const DEDUP_MS: u64 = 3_000;
const SNAPSHOT_EVENTS: usize = 8;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
enum Kind {
    MovieOpenUnavailable,
    MissionFailure,
    RetryChosen,
    RetryClearArea,
    AudioEvent,
    AudioPrepare,
    AudioActivated,
    AudioReady,
    AudioPlay,
    AudioStopped,
    AudioReset,
    CutsceneState,
    CutsceneStopped,
    CutsceneUnloaded,
    PrestreamCancelled,
    SceneCreated,
    TvChannelDirty,
    MissionConversationReset,
    TvScriptEmitter,
}
impl Kind {
    fn name(self) -> &'static str {
        match self {
            Self::MovieOpenUnavailable => "movie_open_unavailable",
            Self::MissionFailure => "mission_failure",
            Self::RetryChosen => "retry_selected",
            Self::RetryClearArea => "retry_clear_area",
            Self::AudioEvent => "audio_event",
            Self::AudioPrepare => "audio_prepare",
            Self::AudioActivated => "audio_activated",
            Self::AudioReady => "audio_ready",
            Self::AudioPlay => "audio_play",
            Self::AudioStopped => "audio_stop",
            Self::AudioReset => "audio_reset",
            Self::CutsceneState => "cutscene_state",
            Self::CutsceneStopped => "cutscene_stopped",
            Self::CutsceneUnloaded => "cutscene_unloaded",
            Self::PrestreamCancelled => "prestream_cancel",
            Self::SceneCreated => "scene_created",
            Self::TvChannelDirty => "tv_channel_dirty",
            Self::MissionConversationReset => "mission_conversation_reset",
            Self::TvScriptEmitter => "tv_script_emitter",

        }
    }
}
#[derive(Clone, Debug, Eq, PartialEq)]
struct Event {
    kind: Kind,
    context: String,
}

fn token_after<'a>(line: &'a str, needle: &str) -> Option<&'a str> {
    Some(line.split_once(needle)?.1.split(|ch: char| ch == ',' || ch.is_ascii_whitespace()).next()?.trim())
}

/// Only emit simple engine-defined asset/mission identifiers, never arbitrary
/// filesystem paths, logs of chat text, or long unbounded user-supplied strings.
fn safe_token(raw: &str) -> String {
    let token: String = raw.chars().filter(|ch| ch.is_ascii_alphanumeric() || matches!(*ch, '_' | '-')).take(64).collect();
    if token.is_empty() { "none".into() } else { token }
}

fn classify(line: &str) -> Option<Event> {
    let (kind, context): (Kind, &str) =
        if line.contains("Unable to open movie: Bink is not available") {
            (Kind::MovieOpenUnavailable, "bink_unavailable_wasm")
        } else if line.contains("TRIGGER_MUSIC_EVENT:") && line.contains("_FAIL") {
            (Kind::MissionFailure, token_after(line, "TRIGGER_MUSIC_EVENT: ")?)
        } else if line.contains("Sound:RETRY") && line.contains("replay_controller") {
            (Kind::RetryChosen, "replay_controller")
        } else if line.contains("COMMAND_CLEAR_AREA from \"replay_controller\"") {
            (Kind::RetryClearArea, "replay_controller")
        } else if line.contains("[Audio_CutsceneAudio] Event ") {
            (Kind::AudioEvent, token_after(line, "[Audio_CutsceneAudio] Event ")?)
        } else if line.contains("Cutscene Audio Prepare (new): ") {
            (Kind::AudioPrepare, token_after(line, "Cutscene Audio Prepare (new): ")?)
        } else if line.contains("Prepared and activating cutscene track") {
            (Kind::AudioActivated, "track")
        } else if line.contains("Cutscene audio was asked if it was prepared and it replied: true") {
            (Kind::AudioReady, "true")
        } else if line.contains("Cutscene Audio Play: ") {
            (Kind::AudioPlay, token_after(line, "Cutscene Audio Play: ")?)
        } else if line.contains("Cutscene Audio Stop: ") {
            (Kind::AudioStopped, token_after(line, "Cutscene Audio Stop: ")?)
        } else if line.contains("Cutscene track reset") {
            (Kind::AudioReset, "track")
        } else if line.contains("[cutscene] Updating cutscene time. state: ") {
            (Kind::CutsceneState, token_after(line, "state: ")?)
        } else if line.contains("Cutscene stopped - removing SRL") {
            (Kind::CutsceneStopped, "srl")
        } else if line.contains("Cutscene unloaded - removing SRL") {
            (Kind::CutsceneUnloaded, "srl")
        } else if line.contains("Cutscene prestreaming canceled - removing SRL") {
            (Kind::PrestreamCancelled, "srl")
        } else if line.contains("[Cutscene] Created ") && line.contains(" Draw Distance events") {
            (Kind::SceneCreated, "draw_distance_events")
        } else if line.contains("CTVChannelInfo::SetDirty(true) called.") {
            (Kind::TvChannelDirty, "tv_channel")
        } else if line.contains("STOP_SCRIPTED_CONVERSATION called from SCRIPT: Script Name = Armenian3") {
            (Kind::MissionConversationReset, "Armenian3")
        } else if line.contains("SET_STATIC_EMITTER_ENABLED is disabling emitter SE_MICHAELS_HOUSE_RADIO")
            && line.contains("Script Name = ob_tv") {
            (Kind::TvScriptEmitter, "ob_tv")
        } else {
            return None;
        };
    Some(Event { kind, context: safe_token(context) })
}

#[derive(Default)]
struct Trace {
    sequence: u64,
    events: VecDeque<String>,
    dedup: HashMap<(Kind, String), u64>,
    last_audio: String,
    last_audio_play: String,
    last_mission: String,
    last_cutscene: String,
    last_activity_ms: u64,
    // These are one-shot breadcrumbs only while the current cutscene is an
    // Armenian3 MCS segment. TV/mission script chatter outside that window
    // must not drown out the useful cutscene lifecycle history.
    armenian_mcs_active: bool,
    tv_dirty_seen: bool,
    mission_conversation_reset_seen: bool,
    tv_script_emitter_seen: bool,
}
impl Trace {
    fn record(&mut self, ev: Event, ms: u64) -> Option<String> {
        if matches!(ev.kind, Kind::TvChannelDirty | Kind::MissionConversationReset | Kind::TvScriptEmitter) {
            if !self.armenian_mcs_active { return None; }
            let already_seen = match ev.kind {
                Kind::TvChannelDirty => &mut self.tv_dirty_seen,
                Kind::MissionConversationReset => &mut self.mission_conversation_reset_seen,
                Kind::TvScriptEmitter => &mut self.tv_script_emitter_seen,
                _ => unreachable!(),
            };
            if *already_seen { return None; }
            *already_seen = true;
        }
        let key = (ev.kind, ev.context.clone());
        if let Some(previous) = self.dedup.get(&key) {
            if ms.saturating_sub(*previous) < DEDUP_MS {
                return None;
            }
        }
        if self.dedup.len() >= MAX_KEYS {
            // No permanent per-resource map; bounded diagnostics only.
            self.dedup.clear();
        }
        self.dedup.insert(key, ms);
        self.sequence = self.sequence.saturating_add(1);
        let age = ms.saturating_sub(self.last_activity_ms);
        self.last_activity_ms = ms;
        match ev.kind {
            Kind::MissionFailure => self.last_mission = ev.context.clone(),
            Kind::RetryChosen => self.last_mission = "retry_selected".into(),
            Kind::AudioEvent => {
                // Reset for the new audio event: playback of a *previous*
                // cutscene does not establish that this one started. Also
                // scope TV/script breadcrumbs to the current Armenian3 MCS.
                self.last_audio = ev.context.clone();
                self.last_audio_play.clear();
                self.armenian_mcs_active = ev.context.starts_with("ARMENIAN_3_MCS_");
                self.tv_dirty_seen = false;
                self.mission_conversation_reset_seen = false;
                self.tv_script_emitter_seen = false;
            },
            Kind::AudioPrepare | Kind::AudioStopped => self.last_audio = ev.context.clone(),
            Kind::AudioPlay => self.last_audio_play = ev.context.clone(),
            Kind::CutsceneStopped | Kind::CutsceneUnloaded => {
                self.armenian_mcs_active = false;
                self.last_cutscene = format!("{}:{}", ev.kind.name(), ev.context);
            },
            Kind::CutsceneState | Kind::PrestreamCancelled | Kind::SceneCreated =>
                self.last_cutscene = format!("{}:{}", ev.kind.name(), ev.context),
            _ => {}
        }
        let line = format!("seq={} t_ms={} delta_ms={} stage={} context={}",
            self.sequence, ms, age, ev.kind.name(), ev.context);
        if self.events.len() == MAX_EVENTS { self.events.pop_front(); }
        self.events.push_back(line.clone());
        Some(line)
    }

    fn summary(&self) -> String {
        let age_ms = START.elapsed().as_millis() as u64;
        let since_last_stage_ms = age_ms.saturating_sub(self.last_activity_ms);
        let tail = self.events.iter().rev().take(SNAPSHOT_EVENTS).rev()
            .cloned().collect::<Vec<_>>().join(" | ");
        format!("count={} since_last_stage_ms={} last_mission={} last_audio={} last_audio_play_observed={} last_cutscene={} tv_dirty={} conversation_reset={} tv_script_emitter={} completion_callback=not_instrumented recent={:?}",
            self.sequence,
            since_last_stage_ms,
            if self.last_mission.is_empty() { "none" } else { &self.last_mission },
            if self.last_audio.is_empty() { "none" } else { &self.last_audio },
            if self.last_audio_play.is_empty() { "none" } else { &self.last_audio_play },
            if self.last_cutscene.is_empty() { "none" } else { &self.last_cutscene },
            self.tv_dirty_seen,
            self.mission_conversation_reset_seen,
            self.tv_script_emitter_seen,
            tail)
    }
}

static START: LazyLock<Instant> = LazyLock::new(Instant::now);
// Engine prints identical cutscene timing states many times per second.
// Filter before allocating an Event or locking the shared trace.
static LAST_CUTSCENE_CLOCK_STATE: AtomicI32 = AtomicI32::new(-1);
fn cutscene_clock_changed(previous: i32, next: i32) -> bool { previous != next }
static TRACE: LazyLock<Mutex<Trace>> = LazyLock::new(|| Mutex::new(Trace::default()));

/// Called only for coarse engine messages. Very frequent engine update/timer
/// lines are rejected before locking; repeat events are throttled. This
/// callback is used in both engine print and fd_write logging routes.
pub(crate) fn observe_engine_line(line: &str) {
    if !line.contains("Cutscene") && !line.contains("cutscene")
        && !line.contains("RETRY") && !line.contains("_FAIL")
        && !line.contains("movie: Bink")
        && !line.contains("CTVChannelInfo::SetDirty(true)")
        && !line.contains("STOP_SCRIPTED_CONVERSATION")
        && !line.contains("SET_STATIC_EMITTER_ENABLED") {
        return;
    }
    if line.contains("[cutscene] Updating cutscene time. state: ") {
        let Some(state) = token_after(line, "state: ").and_then(|t| t.parse::<i32>().ok()) else { return; };
        if !cutscene_clock_changed(LAST_CUTSCENE_CLOCK_STATE.swap(state, Ordering::Relaxed), state) { return; }
    }
    let Some(event) = classify(line) else { return; };
    if matches!(event.kind, Kind::CutsceneStopped | Kind::CutsceneUnloaded) {
        LAST_CUTSCENE_CLOCK_STATE.store(-1, Ordering::Relaxed);
    }
    let ms = START.elapsed().as_millis() as u64;
    let updated = {
        let mut state = TRACE.lock().unwrap();
        state.record(event, ms)
    };
    if let Some(line) = updated {
        eprintln!("[nightlane-movie-trace] {line}");
    }
}

/// Read-only state snapshot for the already-existing GPU no-present watchdog.
pub(crate) fn snapshot() -> String {
    TRACE.lock().unwrap().summary()
}

/// Called once when the existing independent Movie Manager sleep detector
/// becomes confident. A missing movie-completed callback cannot be inferred
/// without instrumenting the actual game/movie interface.
pub(crate) fn suspected_stall() {
    eprintln!("[nightlane-movie-trace-stall] {}", snapshot());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn classified(s: &str) -> Event { classify(s).expect(s) }

    #[test]
    fn recognizes_mission_failure_and_retry_without_inventing_completions() {
        assert_eq!(classified("[Audio] TRIGGER_MUSIC_EVENT: ARM3_FAIL").kind, Kind::MissionFailure);
        assert_eq!(classified("[SFX] PLAY_SOUND_FRONTEND - Sound:RETRY - Set:HUD_FRONTEND_DEFAULT_SOUNDSET: Played successfully (SCRIPT: Script Name = replay_controller)").kind, Kind::RetryChosen);
        assert_eq!(classified("COMMAND_CLEAR_AREA from \"replay_controller\" Position (-806.2,178.9,76.8)").kind, Kind::RetryClearArea);
        assert!(classify("[eng] regular draw call").is_none());
    }

    #[test]
    fn differentiates_audio_ready_from_movie_completion() {
        assert_eq!(classified("[Audio_CutsceneAudio] Event ARMENIAN_3_MCS_4, Start offset 12034").context, "ARMENIAN_3_MCS_4");
        assert_eq!(classified("Cutscene Audio Prepare (new): ARMENIAN_3_MCS_4, type 1").kind, Kind::AudioPrepare);
        assert_eq!(classified("Prepared and activating cutscene track at load index 0").kind, Kind::AudioActivated);
        assert_eq!(classified("[Audio_CutsceneAudio] Cutscene Audio Play: ARMENIAN_3_INT_SEQ_MASTERED_ONLY, type 1").kind, Kind::AudioPlay);
        assert_eq!(classified("[Audio_CutsceneAudio] Cutscene audio was asked if it was prepared and it replied: true").kind, Kind::AudioReady);
        assert!(classify("[Audio_CutsceneAudio] Cutscene audio was asked if it was prepared and it replied: false").is_none());
        assert_eq!(classified("Error: Unable to open movie: Bink is not available on the WebAssembly build").kind, Kind::MovieOpenUnavailable);
    }

    #[test]
    fn remembers_only_bounded_and_throttled_events() {
        let mut trace = Trace::default();
        let ev = classified("[Audio_CutsceneAudio] Event ARMENIAN_3_MCS_4, Start offset 12034");
        assert!(trace.record(ev.clone(), 100).is_some());
        assert!(trace.record(ev.clone(), 1000).is_none());
        assert!(trace.record(ev, 3200).is_some());
        assert_eq!(trace.sequence, 2);
        for i in 0..120u64 {
            let event = Event { kind: Kind::AudioEvent, context: format!("track{i}") };
            assert!(trace.record(event, 10_000 + i * 3000).is_some());
        }
        assert_eq!(trace.events.len(), MAX_EVENTS);
        assert!(trace.dedup.len() <= MAX_KEYS);
        assert!(trace.summary().contains("completion_callback=not_instrumented"));
    }

    #[test]
    fn new_cutscene_audio_ready_is_not_mistaken_for_previous_audio_play() {
        let mut trace = Trace::default();
        trace.record(classified("[Audio_CutsceneAudio] Event ARMENIAN_3_INT_SEQ, Start offset 36000"), 100);
        trace.record(classified("[Audio_CutsceneAudio] Cutscene Audio Play: ARMENIAN_3_INT_SEQ_MASTERED_ONLY, type 1"), 150);
        assert_eq!(trace.last_audio_play, "ARMENIAN_3_INT_SEQ_MASTERED_ONLY");
        trace.record(classified("[Audio_CutsceneAudio] Event ARMENIAN_3_MCS_3, Start offset 10367"), 200);
        assert_eq!(trace.last_audio_play, "");
        trace.record(classified("[Audio_CutsceneAudio] Cutscene audio was asked if it was prepared and it replied: true"), 240);
        assert_eq!(trace.last_audio_play, "");
    }

    #[test]
    fn suppresses_raw_file_paths_and_unbounded_names() {
        assert_eq!(safe_token("/game/private-name?.rpf"), "gameprivate-namerpf");
        assert!(safe_token(&"A".repeat(200)).len() <= 64);
        assert_eq!(safe_token(""), "none");
    }

    #[test]
    fn tv_and_script_events_are_recorded_once_per_armenian_mcs_only() {
        let mut trace = Trace::default();
        let tv = classified("[eng] CTVChannelInfo::SetDirty(true) called. Dumping C++ and script stacks...");
        let conversation = classified("[eng] [Audio_Conversation] STOP_SCRIPTED_CONVERSATION called from SCRIPT: Script Name = Armenian3 : Program Counter = 216328");
        let emitter = classified("[eng] SET_STATIC_EMITTER_ENABLED is disabling emitter SE_MICHAELS_HOUSE_RADIO (from script SCRIPT: Script Name = ob_tv : Program Counter = 1025)");
        assert_eq!(tv.kind, Kind::TvChannelDirty);
        assert_eq!(conversation.kind, Kind::MissionConversationReset);
        assert_eq!(emitter.kind, Kind::TvScriptEmitter);
        assert!(trace.record(tv.clone(), 100).is_none());
        trace.record(classified("[Audio_CutsceneAudio] Event ARMENIAN_3_MCS_3, Start offset 10367"), 110);
        assert!(trace.record(tv.clone(), 200).is_some());
        assert!(trace.record(tv, 3_500).is_none()); // still one per segment
        assert!(trace.record(conversation, 4_500).is_some());
        assert!(trace.record(emitter, 5_500).is_some());
        assert!(trace.summary().contains("tv_dirty=true"));
        assert!(trace.summary().contains("conversation_reset=true"));
        assert!(trace.summary().contains("tv_script_emitter=true"));
        trace.record(classified("[Audio_CutsceneAudio] Event ARMENIAN_3_MCS_4, Start offset 10367"), 7_000);
        assert!(!trace.tv_dirty_seen);
        assert!(!trace.mission_conversation_reset_seen);
        assert!(!trace.tv_script_emitter_seen);
    }

    #[test]
    fn repeated_cutscene_state_is_filtered_without_locking() {
        assert!(!cutscene_clock_changed(8, 8));
        assert!(cutscene_clock_changed(7, 8));
        assert!(cutscene_clock_changed(-1, 7));
    }

    #[test]
    fn replay_and_cutscene_state_visibility_are_separate() {
        assert_eq!(classified("[cutscene] Updating cutscene time. state: 8, initalTime: 47.4").context, "8");
        assert_eq!(classified("[streaming] Cutscene unloaded - removing SRL").kind, Kind::CutsceneUnloaded);
        assert_eq!(classified("[Audio_CutsceneAudio] Cutscene Audio Stop: ARMENIAN_3_MCS_3_MASTERED_ONLY, type 1").context, "ARMENIAN_3_MCS_3_MASTERED_ONLY");
    }
}
