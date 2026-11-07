//! Launcher updates from GitHub Releases. Windows, macOS and AppImage builds install a signed
//! update in place; other Linux packages (.deb) are only told that a new version exists.

use std::time::{Duration, Instant};

use serde::Serialize;
use specta::Type;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_updater::UpdaterExt;
use tauri_specta::Event;

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

/// How far the background download of an update is (bytes; `total` when the server says).
#[derive(Debug, Clone, Serialize, Type, Event)]
#[serde(rename_all = "camelCase")]
pub struct AppUpdateProgressEvent {
    pub downloaded: f64,
    pub total: Option<f64>,
    pub done: bool,
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

/// Download the update found by [`check_app_update`] while the launcher is in use; it is
/// installed later ([`install_app_update`] or on closing the launcher). Progress comes as
/// [`AppUpdateProgressEvent`].
#[tauri::command]
#[specta::specta]
pub async fn download_app_update(app: AppHandle, state: State<'_, AppState>) -> CmdResult<()> {
    let update = state
        .pending_update
        .lock()
        .await
        .clone()
        .ok_or_else(|| launcher_core::Error::Invalid("no update to download".into()))?;
    let ready = state.downloaded_update.lock().await;
    if ready.as_ref().is_some_and(|(v, _)| *v == update.version) {
        return Ok(());
    }
    drop(ready);
    let mut got = 0u64;
    let mut sent = Instant::now() - Duration::from_secs(1);
    let progress = |downloaded: u64, total: Option<u64>, done: bool| AppUpdateProgressEvent {
        downloaded: downloaded as f64,
        total: total.map(|t| t as f64),
        done,
    };
    let bytes = update
        .download(
            |chunk, total| {
                got += chunk as u64;
                // A few updates a second are plenty for a progress bar.
                if sent.elapsed() >= Duration::from_millis(150) {
                    sent = Instant::now();
                    let _ = progress(got, total, false).emit(&app);
                }
            },
            || {},
        )
        .await
        .map_err(|e| launcher_core::Error::Invalid(e.to_string()))?;
    let size = bytes.len() as u64;
    *state.downloaded_update.lock().await = Some((update.version.clone(), bytes));
    let _ = progress(size, Some(size), true).emit(&app);
    tracing::info!(version = %update.version, "launcher update downloaded");
    Ok(())
}

/// Install (the already downloaded update when there is one) and restart. Refused while a game
/// runs: restarting would cut it off from the launcher.
#[tauri::command]
#[specta::specta]
pub async fn install_app_update(app: AppHandle, state: State<'_, AppState>) -> CmdResult<()> {
    if !state.games.running().is_empty() {
        return Err(launcher_core::Error::Invalid("close the game before updating".into()).into());
    }
    let update = state
        .pending_update
        .lock()
        .await
        .take()
        .ok_or_else(|| launcher_core::Error::Invalid("no update to install".into()))?;
    tracing::info!(version = %update.version, "installing launcher update");
    let ready = state
        .downloaded_update
        .lock()
        .await
        .take()
        .filter(|(v, _)| *v == update.version);
    match ready {
        Some((_, bytes)) => update.install(bytes),
        None => update.download_and_install(|_, _| {}, || {}).await,
    }
    .map_err(|e| launcher_core::Error::Invalid(e.to_string()))?;
    app.restart();
}

/// On closing the launcher: put a downloaded update in place, so the next start is the new
/// version. Skipped while a game runs or when nothing is ready.
pub fn install_on_exit(app: &AppHandle) {
    let state = app.state::<AppState>();
    if !state.games.running().is_empty() {
        return;
    }
    let (Ok(mut pending), Ok(mut ready)) = (
        state.pending_update.try_lock(),
        state.downloaded_update.try_lock(),
    ) else {
        return;
    };
    let (Some(update), Some((version, bytes))) = (pending.take(), ready.take()) else {
        return;
    };
    if update.version != version {
        return;
    }
    tracing::info!(%version, "installing the downloaded launcher update on exit");
    if let Err(e) = update.install(bytes) {
        tracing::warn!(error = %e, "launcher update not installed");
    }
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
