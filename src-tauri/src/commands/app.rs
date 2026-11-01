use launcher_core::ErrorInfo;
use launcher_core::accounts::{KeyringStore, dev_offline_override};
use launcher_core::java::flags::default_max_memory_mb;
use launcher_core::mods::model::ContentKind;
use launcher_core::settings::Settings;
use launcher_core::util::sys::{Arch, Os, current_arch, current_os, total_memory_mb};
use serde::{Deserialize, Serialize};
use specta::Type;
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;

use super::CmdResult;
use crate::state::AppState;

#[derive(Debug, Clone, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub version: String,
    pub os: Os,
    pub arch: Arch,
    pub root_dir: String,
    pub data_dir: String,
    pub portable: bool,
    pub total_memory_mb: f64,
    pub default_memory_mb: u32,
    pub default_memory_modded_mb: u32,
    pub keyring_available: bool,
    pub keyring_error: Option<String>,
    pub dev_offline: bool,
    pub user_agent: String,
    /// Game versions the bundled Aerion mod supports (with Fabric).
    pub aerion_mod_versions: Vec<String>,
}

#[tauri::command]
#[specta::specta]
pub async fn app_info(state: State<'_, AppState>) -> CmdResult<AppInfo> {
    let total = total_memory_mb();
    let keyring = tokio::task::spawn_blocking(KeyringStore::available)
        .await
        .map_err(|e| ErrorInfo::msg("generic", e.to_string()))?;
    Ok(AppInfo {
        version: env!("CARGO_PKG_VERSION").into(),
        os: current_os(),
        arch: current_arch(),
        root_dir: state.paths.root.display().to_string(),
        data_dir: state.paths.data.display().to_string(),
        portable: state.paths.portable,
        total_memory_mb: total as f64,
        default_memory_mb: default_max_memory_mb(total, false),
        default_memory_modded_mb: default_max_memory_mb(total, true),
        keyring_available: keyring.is_ok(),
        keyring_error: keyring.err().map(|e| e.to_string()),
        dev_offline: dev_offline_override(),
        user_agent: launcher_core::net::client::user_agent(),
        aerion_mod_versions: launcher_core::aerion_mod::GAME_VERSIONS
            .iter()
            .map(|v| (*v).to_owned())
            .collect(),
    })
}

#[tauri::command]
#[specta::specta]
pub async fn get_settings(state: State<'_, AppState>) -> CmdResult<Settings> {
    Ok(state.settings.read().await.clone())
}

/// Save settings; rebuilds the HTTP stack when network options change.
#[tauri::command]
#[specta::specta]
pub async fn save_settings(
    state: State<'_, AppState>,
    mut settings: Settings,
) -> CmdResult<Settings> {
    settings.download_concurrency = settings.download_concurrency.clamp(1, 64);
    settings.wrapper = settings
        .wrapper
        .map(|w| w.trim().to_owned())
        .filter(|w| !w.is_empty());
    settings.validate()?;
    settings.proxy = settings.proxy.filter(|p| !p.trim().is_empty());
    settings.msa_client_id = settings
        .msa_client_id
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty());
    if let Some(m) = settings.max_memory_mb
        && !(512..=262_144).contains(&m)
    {
        return Err(ErrorInfo::msg(
            "settings.memory",
            "memory must be between 512 MB and 256 GB",
        ));
    }
    let previous = state.settings.read().await.clone();
    // Keep the generated id stable no matter what the UI sends.
    settings.client_id = previous.client_id.clone();
    if previous.proxy != settings.proxy
        || previous.download_concurrency != settings.download_concurrency
    {
        let engine = launcher_core::Engine::new(state.paths.clone(), &settings)?;
        *state.engine.write().await = engine;
    }
    settings.save(&state.paths).await?;
    *state.settings.write().await = settings.clone();
    Ok(settings)
}

#[derive(Debug, Clone, Deserialize, Type)]
#[serde(rename_all = "camelCase", tag = "kind")]
pub enum FolderTarget {
    Root,
    Data,
    Logs,
    Themes,
    Instance {
        id: String,
    },
    GameDir {
        id: String,
    },
    Content {
        id: String,
        content: ContentKind,
    },
    Screenshots {
        id: String,
    },
    /// A file or folder inside the launcher's directories (crash reports, exports).
    Path {
        path: String,
    },
}

#[tauri::command]
#[specta::specta]
pub async fn open_folder(
    app: AppHandle,
    state: State<'_, AppState>,
    target: FolderTarget,
) -> CmdResult<()> {
    let p = &state.paths;
    let path = match target {
        FolderTarget::Root => p.root.clone(),
        FolderTarget::Data => p.data.clone(),
        FolderTarget::Logs => p.logs_dir(),
        FolderTarget::Themes => p.themes_dir(),
        FolderTarget::Instance { id } => state.instances.dir(&id)?,
        FolderTarget::GameDir { id } => state.instances.game_dir(&id)?,
        FolderTarget::Content { id, content } => {
            state.instances.game_dir(&id)?.join(content.folder())
        }
        FolderTarget::Screenshots { id } => state.instances.game_dir(&id)?.join("screenshots"),
        FolderTarget::Path { path } => {
            let path = std::path::PathBuf::from(path);
            let allowed = [&p.root, &p.data].iter().any(|base| path.starts_with(base));
            if !allowed
                || path
                    .components()
                    .any(|c| matches!(c, std::path::Component::ParentDir))
            {
                return Err(ErrorInfo::msg(
                    "security.unsafePath",
                    "path outside the launcher directories",
                ));
            }
            path
        }
    };
    if !path.exists() {
        let _ = tokio::fs::create_dir_all(&path).await;
    }
    let shown = if path.is_file() {
        app.opener().reveal_item_in_dir(&path)
    } else {
        app.opener()
            .open_path(path.display().to_string(), None::<&str>)
    };
    shown.map_err(|e| ErrorInfo::msg("generic", e.to_string()))
}

/// Frontend errors and lifecycle messages go to the launcher log (WebView consoles are not
/// visible to users or in release builds).
#[tauri::command]
#[specta::specta]
pub async fn frontend_log(level: String, message: String) -> CmdResult<()> {
    let message: String = message.chars().take(4000).collect();
    match level.as_str() {
        "error" => tracing::error!(target: "frontend", "{message}"),
        "warn" => tracing::warn!(target: "frontend", "{message}"),
        _ => tracing::info!(target: "frontend", "{message}"),
    }
    Ok(())
}

/// Play statistics for the Stats page.
#[tauri::command]
#[specta::specta]
pub async fn play_stats(state: State<'_, AppState>) -> CmdResult<launcher_core::stats::Stats> {
    Ok(launcher_core::stats::compute(&state.instances).await)
}
