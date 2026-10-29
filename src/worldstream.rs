// Nightlane WorldStream — Phase 0 configuration and safe fallback.
// AI-assisted, unofficial modification of the original BytArch/muguet port.
// This module intentionally DOES NOT replace any game/world streaming logic.

use serde_json::Value;

pub(crate) const CONFIG_VERSION: u32 = 1;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum StreamMode {
    Original,
    Experimental,
}

impl StreamMode {
    pub(crate) fn name(self) -> &'static str {
        match self {
            Self::Original => "original",
            Self::Experimental => "experimental",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct Selection {
    pub(crate) version: u32,
    pub(crate) requested: StreamMode,
    pub(crate) active: StreamMode,
    pub(crate) reason: &'static str,
}

impl Selection {
    /// Invalid settings must always fall back to the original streaming path.
    /// Version 1 is the initial *configuration format*, not an experimental
    /// streaming engine. Experimental is reserved for future guarded phases.
    pub(crate) fn from_json(config: &Value) -> Self {
        let raw_version = config.get("nightlane_worldstream_config_version")
            .map(|v| v.as_u64()).unwrap_or(Some(CONFIG_VERSION as u64));
        let valid_version = raw_version == Some(CONFIG_VERSION as u64);
        let version = raw_version.and_then(|v| u32::try_from(v).ok()).unwrap_or(0);
        let mode = config.get("nightlane_worldstream_mode")
            .and_then(Value::as_str).unwrap_or("original");

        let (requested, reason) = if !valid_version {
            (StreamMode::Original, "unsupported configuration version; using original")
        } else if mode.eq_ignore_ascii_case("experimental") {
            (StreamMode::Experimental, "Phase 0: experimental backend not implemented; using original")
        } else if mode.eq_ignore_ascii_case("original") {
            (StreamMode::Original, "original backend selected")
        } else {
            (StreamMode::Original, "unknown mode; using original")
        };

        Self {
            version,
            requested,
            // Never route a request to a nonexistent backend.
            active: StreamMode::Original,
            reason,
        }
    }

    pub(crate) fn from_environment() -> Self {
        let mode = std::env::var("NIGHTLANE_WORLDSTREAM_MODE")
            .unwrap_or_else(|_| "original".into());
        let version = std::env::var("NIGHTLANE_WORLDSTREAM_CONFIG_VERSION")
            .unwrap_or_else(|_| CONFIG_VERSION.to_string());
        let version: Value = version.parse::<u64>().ok()
            .map(Value::from).unwrap_or(Value::Null);
        let mut config = serde_json::Map::new();
        config.insert("nightlane_worldstream_mode".into(), Value::from(mode));
        config.insert("nightlane_worldstream_config_version".into(), version);
        Self::from_json(&Value::Object(config))
    }

    pub(crate) fn log_line(self) -> String {
        format!(
            "[worldstream] phase=0 config_version={} requested={} active={} reason={}",
            self.version, self.requested.name(), self.active.name(), self.reason
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn default_is_original_without_a_config_migration() {
        let selection = Selection::from_json(&json!({}));
        assert_eq!(selection.version, CONFIG_VERSION);
        assert_eq!(selection.active, StreamMode::Original);
        assert_eq!(selection.requested, StreamMode::Original);
    }

    #[test]
    fn experimental_is_reserved_and_safely_uses_original() {
        let selection = Selection::from_json(&json!({
            "nightlane_worldstream_config_version": 1,
            "nightlane_worldstream_mode": "experimental"
        }));
        assert_eq!(selection.requested, StreamMode::Experimental);
        assert_eq!(selection.active, StreamMode::Original);
        assert!(selection.log_line().contains("active=original"));
        assert!(selection.log_line().contains("not implemented"));
    }

    #[test]
    fn unrecognized_mode_falls_back_without_mutating_game_data() {
        for mode in ["", "chunks", "../new", "Experimental2"] {
            let selection = Selection::from_json(&json!({
                "nightlane_worldstream_config_version": 1,
                "nightlane_worldstream_mode": mode
            }));
            assert_eq!(selection.active, StreamMode::Original);
            assert_eq!(selection.requested, StreamMode::Original);
        }
    }

    #[test]
    fn unknown_config_versions_cannot_enable_a_new_backend() {
        for value in [json!(0), json!(2), json!(null), json!(-1), json!("1")] {
            let selection = Selection::from_json(&json!({
                "nightlane_worldstream_config_version": value,
                "nightlane_worldstream_mode": "experimental"
            }));
            assert_eq!(selection.active, StreamMode::Original);
            assert_eq!(selection.requested, StreamMode::Original);
        }
    }

    #[test]
    fn mode_spelling_is_case_insensitive() {
        let selection = Selection::from_json(&json!({
            "nightlane_worldstream_config_version": 1,
            "nightlane_worldstream_mode": "EXPERIMENTAL"
        }));
        assert_eq!(selection.requested, StreamMode::Experimental);
        assert_eq!(selection.active, StreamMode::Original);
    }
}
