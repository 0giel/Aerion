//! Launcher updates from GitHub Releases. Windows, macOS and AppImage builds install a signed
//! update in place; other Linux packages (.deb) are only told that a new version exists.

use serde::Serialize;
use specta::Type;
use tauri::{AppHandle, State};
use tauri_plugin_updater::UpdaterExt;

use super::CmdResult;
use crate::state::AppState;

const LATEST: &str = "https://github.com/0giel/Aerion/releases/latest/download/latest.json";
pub const RELEASES: &str = "https://github.com/0giel/Aerion/releases/latest";

#[derive(Debug, Clone, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub struct AppUpdate {
    pub version: String,
    pub notes: Option<String>,
    /// The update can be installed from inside the launcher.
    pub can_install: bool,
    /// Download page for manual installs.
    pub url: String,
}

fn self_updating() -> bool {
    cfg!(any(windows, target_os = "macos")) || std::env::var_os("APPIMAGE").is_some()
}

/// `0.1.10` > `0.1.9`; a leading `v` is ignored, non-numeric parts count as 0.
fn newer(candidate: &str, current: &str) -> bool {
    let parts = |v: &str| -> Vec<u64> {
        v.trim_start_matches('v')
            .split(['.', '-', '+'])
            .take(3)
            .map(|p| p.parse().unwrap_or(0))
            .collect()
    };
    parts(candidate) > parts(current)
}

#[tauri::command]
#[specta::specta]
pub async fn check_app_update(
    app: AppHandle,
    state: State<'_, AppState>,
) -> CmdResult<Option<AppUpdate>> {
    let err = |e: String| launcher_core::Error::Invalid(e);
    if self_updating() {
        let update = app
            .updater()
            .map_err(|e| err(e.to_string()))?
            .check()
            .await
            .map_err(|e| err(e.to_string()))?;
        let info = update.as_ref().map(|u| AppUpdate {
            version: u.version.clone(),
            notes: u.body.clone(),
            can_install: true,
            url: RELEASES.into(),
        });
        *state.pending_update.lock().await = update;
        return Ok(info);
    }
    let latest: serde_json::Value = state.engine().await.http.get_json(LATEST).await?;
    let version = latest
        .get("version")
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_owned();
    Ok(
        newer(&version, env!("CARGO_PKG_VERSION")).then(|| AppUpdate {
            notes: latest
                .get("notes")
                .and_then(|v| v.as_str())
                .map(str::to_owned),
            version,
            can_install: false,
            url: RELEASES.into(),
        }),
    )
}

/// Download, verify (signature), install and restart.
#[tauri::command]
#[specta::specta]
pub async fn install_app_update(app: AppHandle, state: State<'_, AppState>) -> CmdResult<()> {
    let update = state
        .pending_update
        .lock()
        .await
        .take()
        .ok_or_else(|| launcher_core::Error::Invalid("no update to install".into()))?;
    tracing::info!(version = %update.version, "installing launcher update");
    update
        .download_and_install(|_, _| {}, || {})
        .await
        .map_err(|e| launcher_core::Error::Invalid(e.to_string()))?;
    app.restart();
}

#[cfg(test)]
mod tests {
    #[test]
    fn version_order() {
        assert!(super::newer("0.1.10", "0.1.9"));
        assert!(super::newer("v0.2.0", "0.1.3"));
        assert!(!super::newer("0.1.3", "0.1.3"));
        assert!(!super::newer("", "0.1.3"));
    }
}
