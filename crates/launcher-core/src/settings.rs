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

/// One entry of a user-arranged list (sidebar items, home blocks): order is the list order.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct LayoutItem {
    pub id: String,
    pub visible: bool,
}

/// How the user arranged the sidebar and the home screen. Empty lists mean the built-in
/// default; unknown ids are ignored and new ones appended by the UI, so updates never lose items.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase", default)]
pub struct LayoutPrefs {
    pub nav: Vec<LayoutItem>,
    /// Favourite instances listed in the sidebar for one-click access.
    pub nav_favorites: bool,
    pub home: Vec<LayoutItem>,
    /// `right`, `left` or `hidden`.
    pub home_player: String,
}

impl Default for LayoutPrefs {
    fn default() -> Self {
        Self {
            nav: vec![],
            nav_favorites: true,
            home: vec![],
            home_player: "right".into(),
        }
    }
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
    /// Default initial heap (`-Xms`); `None` = the JVM's own default.
    pub min_memory_mb: Option<u32>,
    pub jvm_preset: JvmPreset,
    // Game defaults for every instance. An instance's own values are added after these (JVM and
    // game arguments, environment) or replace them (resolution, fullscreen, wrapper).
    pub jvm_args: Vec<String>,
    pub game_args: Vec<String>,
    pub resolution: Option<crate::instances::Resolution>,
    pub fullscreen: bool,
    pub env_vars: BTreeMap<String, String>,
    /// Command the game runs under, e.g. `gamemoderun` or `mangohud`.
    pub wrapper: Option<String>,
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
    /// The player model on the home screen turns its head towards the cursor.
    pub follow_cursor: bool,
    pub layout: LayoutPrefs,
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
            min_memory_mb: None,
            jvm_preset: JvmPreset::Standard,
            jvm_args: vec![],
            game_args: vec![],
            resolution: None,
            fullscreen: false,
            env_vars: BTreeMap::new(),
            wrapper: None,
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
            follow_cursor: true,
            layout: LayoutPrefs::default(),
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

    /// Reject values that would break every launch (same rules as an instance's own settings).
    pub fn validate(&self) -> Result<()> {
        crate::java::flags::validate_syntax(&self.jvm_args)?;
        if self
            .game_args
            .iter()
            .any(|a| a.contains(['\0', '\n', '\r']))
        {
            return Err(crate::Error::Invalid(
                "game arguments may not contain line breaks".into(),
            ));
        }
        for m in [self.max_memory_mb, self.min_memory_mb]
            .into_iter()
            .flatten()
        {
            if !(256..=262_144).contains(&m) {
                return Err(crate::Error::Invalid(
                    "memory must be between 256 MB and 256 GB".into(),
                ));
            }
        }
        let l = &self.layout;
        if l.nav.len() > 64
            || l.home.len() > 64
            || l.nav.iter().chain(&l.home).any(|i| {
                i.id.is_empty()
                    || i.id.len() > 32
                    || !i.id.bytes().all(|b| b.is_ascii_alphanumeric())
            })
            || !matches!(l.home_player.as_str(), "right" | "left" | "hidden")
        {
            return Err(crate::Error::Invalid("invalid layout".into()));
        }
        if self
            .env_vars
            .keys()
            .any(|k| k.is_empty() || k.contains(['=', '\0']))
        {
            return Err(crate::Error::Invalid(
                "invalid environment variable name".into(),
            ));
        }
        Ok(())
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
