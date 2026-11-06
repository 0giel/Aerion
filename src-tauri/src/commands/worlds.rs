//! Worlds (with backups), screenshots and the server list of an instance.

use launcher_core::instances::options::{self, KeyBinding, OptionsTemplate};
use launcher_core::instances::screenshots::{self, GalleryShot, Screenshot};
use launcher_core::instances::servers::{self, SavedServer};
use launcher_core::instances::worlds::{self, WorldBackup, WorldInfo};
use launcher_core::net::mcping::{self, ServerStatus};
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;

use super::CmdResult;
use crate::state::AppState;

/// Run blocking file work off the async runtime.
async fn blocking<T: Send + 'static>(
    f: impl FnOnce() -> launcher_core::Result<T> + Send + 'static,
) -> CmdResult<T> {
    Ok(tokio::task::spawn_blocking(f)
        .await
        .map_err(|e| launcher_core::Error::Invalid(e.to_string()))??)
}

fn not_running(state: &AppState, id: &str) -> CmdResult<()> {
    if state.games.is_running(id) {
        return Err(launcher_core::Error::AlreadyRunning.into());
    }
    Ok(())
}

#[tauri::command]
#[specta::specta]
pub async fn list_worlds(state: State<'_, AppState>, id: String) -> CmdResult<Vec<WorldInfo>> {
    let game = state.instances.game_dir(&id)?;
    blocking(move || Ok(worlds::list_worlds(&game))).await
}

#[tauri::command]
#[specta::specta]
pub async fn list_world_backups(
    state: State<'_, AppState>,
    id: String,
) -> CmdResult<Vec<WorldBackup>> {
    let dir = state.instances.dir(&id)?;
    blocking(move || Ok(worlds::list_backups(&dir))).await
}

#[tauri::command]
#[specta::specta]
pub async fn backup_world(
    state: State<'_, AppState>,
    id: String,
    folder: String,
) -> CmdResult<WorldBackup> {
    let (dir, game) = (state.instances.dir(&id)?, state.instances.game_dir(&id)?);
    blocking(move || worlds::backup_world(&dir, &game, &folder, false)).await
}

/// Restore a backup (the world's current state is backed up first). Returns the world folder.
#[tauri::command]
#[specta::specta]
pub async fn restore_world_backup(
    state: State<'_, AppState>,
    id: String,
    file: String,
) -> CmdResult<String> {
    not_running(&state, &id)?;
    let (dir, game) = (state.instances.dir(&id)?, state.instances.game_dir(&id)?);
    blocking(move || worlds::restore(&dir, &game, &file)).await
}

#[tauri::command]
#[specta::specta]
pub async fn delete_world_backup(
    state: State<'_, AppState>,
    id: String,
    file: String,
) -> CmdResult<()> {
    let dir = state.instances.dir(&id)?;
    blocking(move || worlds::delete_backup(&dir, &file)).await
}

#[tauri::command]
#[specta::specta]
pub async fn list_screenshots(
    state: State<'_, AppState>,
    id: String,
) -> CmdResult<Vec<Screenshot>> {
    let game = state.instances.game_dir(&id)?;
    blocking(move || Ok(screenshots::list(&game))).await
}

#[tauri::command]
#[specta::specta]
pub async fn delete_screenshot(
    state: State<'_, AppState>,
    id: String,
    name: String,
) -> CmdResult<()> {
    let game = state.instances.game_dir(&id)?;
    blocking(move || screenshots::delete(&game, &name)).await
}

/// Show a screenshot selected in the system file manager.
#[tauri::command]
#[specta::specta]
pub async fn reveal_screenshot(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    name: String,
) -> CmdResult<()> {
    let path = screenshots::path(&state.instances.game_dir(&id)?, &name)?;
    app.opener()
        .reveal_item_in_dir(&path)
        .map_err(|e| launcher_core::Error::Invalid(e.to_string()))?;
    Ok(())
}

/// Copy a screenshot to a place the player picked in the save dialog (to share it).
#[tauri::command]
#[specta::specta]
pub async fn save_screenshot_copy(
    state: State<'_, AppState>,
    id: String,
    name: String,
    dest: String,
) -> CmdResult<()> {
    let path = screenshots::path(&state.instances.game_dir(&id)?, &name)?;
    blocking(move || {
        std::fs::copy(&path, &dest)
            .map_err(|e| launcher_core::Error::Invalid(format!("{dest}: {e}")))?;
        Ok(())
    })
    .await
}

/// Screenshots of every instance, newest first (the gallery page).
#[tauri::command]
#[specta::specta]
pub async fn all_screenshots(state: State<'_, AppState>) -> CmdResult<Vec<GalleryShot>> {
    let mut dirs = Vec::new();
    for i in state.instances.list().await {
        if let Ok(dir) = state.instances.game_dir(&i.id) {
            dirs.push((i.id, i.name, dir));
        }
    }
    blocking(move || Ok(screenshots::gallery(&dirs))).await
}

/// Open a screenshot in the system image viewer.
#[tauri::command]
#[specta::specta]
pub async fn open_screenshot(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    name: String,
) -> CmdResult<()> {
    let path = screenshots::path(&state.instances.game_dir(&id)?, &name)?;
    app.opener()
        .open_path(path.display().to_string(), None::<&str>)
        .map_err(|e| launcher_core::Error::Invalid(e.to_string()))?;
    Ok(())
}

#[tauri::command]
#[specta::specta]
pub async fn list_servers(state: State<'_, AppState>, id: String) -> CmdResult<Vec<SavedServer>> {
    let game = state.instances.game_dir(&id)?;
    blocking(move || servers::list(&game)).await
}

#[tauri::command]
#[specta::specta]
pub async fn add_server(
    state: State<'_, AppState>,
    id: String,
    name: String,
    address: String,
) -> CmdResult<()> {
    mcping::parse_address(&address)?;
    let game = state.instances.game_dir(&id)?;
    blocking(move || servers::add(&game, &name, &address)).await
}

#[tauri::command]
#[specta::specta]
pub async fn remove_server(
    state: State<'_, AppState>,
    id: String,
    address: String,
) -> CmdResult<()> {
    let game = state.instances.game_dir(&id)?;
    blocking(move || servers::remove(&game, &address)).await
}

/// Live status of a server (players, MOTD, icon, ping).
#[tauri::command]
#[specta::specta]
pub async fn server_status(address: String) -> CmdResult<ServerStatus> {
    Ok(mcping::status(&address).await?)
}

/// Copy game settings (options.txt …) from one instance to others. Returns how many received them.
#[tauri::command]
#[specta::specta]
pub async fn copy_game_options(
    state: State<'_, AppState>,
    from: String,
    to: Vec<String>,
) -> CmdResult<u32> {
    let src = state.instances.game_dir(&from)?;
    let mut targets = Vec::new();
    for id in to.iter().filter(|id| **id != from) {
        not_running(&state, id)?;
        targets.push(state.instances.game_dir(id)?);
    }
    blocking(move || {
        let mut n = 0;
        for t in &targets {
            if options::copy(&src, t)? > 0 {
                n += 1;
            }
        }
        Ok(n)
    })
    .await
}

#[tauri::command]
#[specta::specta]
pub async fn save_options_template(
    state: State<'_, AppState>,
    from: String,
) -> CmdResult<OptionsTemplate> {
    let inst = state.instances.get(&from).await?;
    let (src, dir) = (
        state.instances.game_dir(&from)?,
        state.paths.options_template_dir(),
    );
    blocking(move || options::save_template(&src, &dir, &inst.name)).await
}

#[tauri::command]
#[specta::specta]
pub async fn options_template(state: State<'_, AppState>) -> CmdResult<Option<OptionsTemplate>> {
    Ok(options::template(&state.paths.options_template_dir()))
}

#[tauri::command]
#[specta::specta]
pub async fn clear_options_template(state: State<'_, AppState>) -> CmdResult<()> {
    let dir = state.paths.options_template_dir();
    blocking(move || options::clear_template(&dir)).await
}

/// Upload the crash report (or the latest log) of an instance to mclo.gs and return the link.
/// Only files inside the instance's game folder are accepted.
#[tauri::command]
#[specta::specta]
pub async fn share_game_log(
    state: State<'_, AppState>,
    id: String,
    report: Option<String>,
) -> CmdResult<String> {
    let game = state.instances.game_dir(&id)?;
    let report = report.map(std::path::PathBuf::from).filter(|p| {
        p.starts_with(&game)
            && !p
                .components()
                .any(|c| matches!(c, std::path::Component::ParentDir))
    });
    let Some(path) = launcher_core::game::share::pick_log(&game, report.as_deref()) else {
        return Err(launcher_core::Error::Invalid("no log to share".into()).into());
    };
    let http = state.engine.read().await.http.clone();
    let url = launcher_core::game::share::upload(&http, &path).await?;
    tracing::info!(%url, "shared game log");
    Ok(url)
}

/// Where key bindings are read and written: an instance's game folder, or the shared settings.
async fn bindings_dir(
    state: &AppState,
    instance_id: Option<&str>,
) -> launcher_core::error::Result<std::path::PathBuf> {
    match instance_id {
        Some(id) => state.instances.game_dir(id),
        None => Ok(state.paths.shared_options_dir()),
    }
}

/// The key bindings of an instance (`instanceId`), or of the shared settings (`null`).
#[tauri::command]
#[specta::specta]
pub async fn key_bindings(
    state: State<'_, AppState>,
    instance_id: Option<String>,
) -> CmdResult<Vec<KeyBinding>> {
    let dir = bindings_dir(&state, instance_id.as_deref()).await?;
    Ok(
        tokio::task::spawn_blocking(move || options::read_bindings(&dir))
            .await
            .unwrap_or_default(),
    )
}

/// Change key bindings. Refused while a game uses those settings: it would write its own back.
#[tauri::command]
#[specta::specta]
pub async fn set_key_bindings(
    state: State<'_, AppState>,
    instance_id: Option<String>,
    bindings: Vec<KeyBinding>,
) -> CmdResult<()> {
    let busy = match &instance_id {
        Some(id) => state.games.is_running(id),
        None => {
            let mut any = false;
            for g in state.games.running() {
                if state
                    .instances
                    .get(&g.instance_id)
                    .await
                    .is_ok_and(|i| i.shared_options)
                {
                    any = true;
                }
            }
            any
        }
    };
    if busy {
        return Err(launcher_core::Error::AlreadyRunning.into());
    }
    let dir = bindings_dir(&state, instance_id.as_deref()).await?;
    tokio::task::spawn_blocking(move || options::write_bindings(&dir, &bindings))
        .await
        .map_err(|e| launcher_core::Error::Invalid(e.to_string()))??;
    Ok(())
}
