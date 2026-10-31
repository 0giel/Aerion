//! Launcher-wide settings (`settings.json` in the root directory).

use std::collections::BTreeMap;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::error::Result;
use crate::java::flags::JvmPreset;
use crate::paths::Paths;
use crate::util::fs::{read_json, write_json};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub enum AfterLaunch {
    /// Leave the launcher as it is.
    Keep,
    /// Minimise the window.
    #[default]
    Minimize,
    /// Hide the window (tray icon stays) and bring it back when the game exits.
    Hide,
    /// Quit the launcher.
    Close,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub enum ModSource {
    #[default]
    Modrinth,
    Curseforge,
    Both,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    /// `pl`, `en`, or `None` to follow the system language.
    pub language: Option<String>,
    pub theme: String,
    /// Switch between `theme_light` / `theme_dark` with the OS appearance.
    pub theme_follow_system: bool,
    pub theme_light: String,
    pub theme_dark: String,
    /// Where instances, libraries, assets and runtimes live (`None` = next to settings).
    pub data_dir: Option<PathBuf>,
    /// Default Java per major version (`"21"` → path); empty = managed runtimes.
    pub java_overrides: BTreeMap<String, PathBuf>,
    pub java_auto_install: bool,
    /// Default maximum heap; `None` = derived from installed RAM.
    pub max_memory_mb: Option<u32>,
    pub jvm_preset: JvmPreset,
    pub download_concurrency: u32,
    pub proxy: Option<String>,
    pub after_launch: AfterLaunch,
    pub close_to_tray: bool,
    pub show_console_on_launch: bool,
    /// Azure application (client) ID; `LAUNCHER_MSA_CLIENT_ID` overrides it.
    pub msa_client_id: Option<String>,
    pub mod_source: ModSource,
    /// Anonymous per-installation id passed as `--clientId`.
    pub client_id: String,
    pub onboarding_done: bool,
    pub last_instance: Option<String>,
    pub selected_account: Option<String>,
    pub check_updates: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            language: None,
            theme: "nocturne".into(),
            theme_follow_system: false,
            theme_light: "light".into(),
            theme_dark: "nocturne".into(),
            data_dir: None,
            java_overrides: BTreeMap::new(),
            java_auto_install: true,
            max_memory_mb: None,
            jvm_preset: JvmPreset::Standard,
            download_concurrency: 12,
            proxy: None,
            after_launch: AfterLaunch::Minimize,
            close_to_tray: false,
            show_console_on_launch: false,
            msa_client_id: None,
            mod_source: ModSource::Modrinth,
            client_id: String::new(),
            onboarding_done: false,
            last_instance: None,
            selected_account: None,
            check_updates: true,
        }
    }
}

impl Settings {
    pub async fn load(paths: &Paths) -> Result<Self> {
        let file = paths.settings_file();
        let mut s = match read_json::<Settings>(&file).await {
            Ok(Some(s)) => s,
            Ok(None) => Settings::default(),
            Err(e) => {
                // Keep the broken file for the user and start from defaults.
                tracing::warn!(error = %e, "settings.json is invalid; using defaults");
                let _ = tokio::fs::rename(&file, file.with_extension("json.broken")).await;
                Settings::default()
            }
        };
        if s.client_id.is_empty() {
            use base64::Engine as _;
            let id = uuid::Uuid::new_v4().to_string();
            s.client_id = base64::engine::general_purpose::STANDARD.encode(id);
            s.save(paths).await?;
        }
        s.download_concurrency = s.download_concurrency.clamp(1, 64);
        Ok(s)
    }

    pub async fn save(&self, paths: &Paths) -> Result<()> {
        write_json(paths.settings_file(), self).await
    }

    /// Client ID from the environment (or build) first, then settings.
    pub fn effective_client_id(&self) -> Option<String> {
        std::env::var("LAUNCHER_MSA_CLIENT_ID")
            .ok()
            .or_else(|| option_env!("LAUNCHER_MSA_CLIENT_ID").map(str::to_owned))
            .filter(|s| !s.trim().is_empty())
            .or_else(|| self.msa_client_id.clone())
            .map(|s| s.trim().to_owned())
            .filter(|s| !s.is_empty())
    }
}
