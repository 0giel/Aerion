use std::path::PathBuf;

use launcher_core::ErrorInfo;
use launcher_core::game::install::prepare;
use launcher_core::instances::launch::{ensure_version, pick_java};
use launcher_core::instances::{Instance, NewInstance, shortcut};
use launcher_core::modpacks::{self, ExportOptions, ImportResult, PackFormat};
use launcher_core::net::download::VerifyMode;
use tauri::{AppHandle, State};

use super::CmdResult;
use crate::state::AppState;

#[tauri::command]
#[specta::specta]
pub async fn list_instances(state: State<'_, AppState>) -> CmdResult<Vec<Instance>> {
    Ok(state.instances.list().await)
}

#[tauri::command]
#[specta::specta]
pub async fn get_instance(state: State<'_, AppState>, id: String) -> CmdResult<Instance> {
    Ok(state.instances.get(&id).await?)
}

#[tauri::command]
#[specta::specta]
pub async fn create_instance(state: State<'_, AppState>, spec: NewInstance) -> CmdResult<Instance> {
    let inst = state.instances.create(spec).await?;
    let mut settings = state.settings.write().await;
    settings.last_instance = Some(inst.id.clone());
    settings.save(&state.paths).await?;
    Ok(inst)
}

#[tauri::command]
#[specta::specta]
pub async fn update_instance(
    state: State<'_, AppState>,
    instance: Instance,
) -> CmdResult<Instance> {
    Ok(state.instances.update(instance).await?)
}

#[tauri::command]
#[specta::specta]
pub async fn set_instance_favorite(
    state: State<'_, AppState>,
    id: String,
    favorite: bool,
) -> CmdResult<Instance> {
    Ok(state.instances.set_favorite(&id, favorite).await?)
}

/// Built-in icon key, or `None` for the default icon.
#[tauri::command]
#[specta::specta]
pub async fn set_instance_icon(
    state: State<'_, AppState>,
    id: String,
    icon: Option<String>,
) -> CmdResult<Instance> {
    Ok(state.instances.set_icon(&id, icon).await?)
}

/// Use an image file picked by the user as the icon.
#[tauri::command]
#[specta::specta]
pub async fn import_instance_icon(
    state: State<'_, AppState>,
    id: String,
    path: String,
) -> CmdResult<Instance> {
    Ok(state
        .instances
        .import_icon(&id, std::path::Path::new(&path))
        .await?)
}

#[tauri::command]
#[specta::specta]
pub async fn delete_instance(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    if state.games.is_running(&id) {
        return Err(launcher_core::Error::AlreadyRunning.into());
    }
    Ok(state.instances.delete(&id).await?)
}

#[tauri::command]
#[specta::specta]
pub async fn clone_instance(
    state: State<'_, AppState>,
    id: String,
    name: String,
) -> CmdResult<Instance> {
    Ok(state.instances.clone_instance(&id, &name).await?)
}

#[tauri::command]
#[specta::specta]
pub async fn instance_groups(state: State<'_, AppState>) -> CmdResult<Vec<String>> {
    Ok(state.instances.groups().await)
}

#[tauri::command]
#[specta::specta]
pub async fn create_shortcut(state: State<'_, AppState>, id: String) -> CmdResult<String> {
    let inst = state.instances.get(&id).await?;
    let exe = std::env::current_exe().map_err(|e| ErrorInfo::msg("generic", e.to_string()))?;
    let path = shortcut::create(&exe, &inst, None)?;
    Ok(path.display().to_string())
}

/// Re-check every file with full hashing and repair what is broken ("Verify files").
#[tauri::command]
#[specta::specta]
pub async fn verify_instance(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
) -> CmdResult<String> {
    let inst = state.instances.get(&id).await?;
    let app2 = app.clone();
    Ok(state.tasks.spawn(
        &app,
        "verify",
        "task.verify",
        Some(inst.name.clone()),
        Some(id.clone()),
        move |ctx| async move {
            use tauri::Manager;
            let state = app2.state::<AppState>();
            let engine = state.engine().await;
            let settings = state.settings.read().await.clone();
            let inst = state.instances.get(&id).await?;
            let version = ensure_version(&engine, &state.instances, &inst, &ctx).await?;
            let prepared =
                prepare(&engine.meta, &engine.env, &version, VerifyMode::Full, &ctx).await?;
            pick_java(&engine, &settings, &inst, &prepared.java, &ctx).await?;
            Ok(None)
        },
    ))
}

/// Import a `.mrpack`, CurseForge zip or Luncher archive (also used for drag & drop).
#[tauri::command]
#[specta::specta]
pub async fn import_pack(
    app: AppHandle,
    state: State<'_, AppState>,
    path: String,
) -> CmdResult<String> {
    let path = PathBuf::from(path);
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned());
    let p = path.clone();
    let format = tokio::task::spawn_blocking(move || modpacks::detect_format(&p))
        .await
        .map_err(|e| ErrorInfo::msg("generic", e.to_string()))??;
    let app2 = app.clone();
    Ok(state.tasks.spawn(
        &app,
        "import",
        "task.import",
        name,
        None,
        move |ctx| async move {
            use tauri::Manager;
            let state = app2.state::<AppState>();
            let engine = state.engine().await;
            let modrinth = state.modrinth().await;
            let result = match format {
                PackFormat::Mrpack => ImportResult {
                    instance: modpacks::mrpack::import(
                        &engine,
                        &state.instances,
                        &modrinth,
                        &path,
                        None,
                        &ctx,
                    )
                    .await?,
                    manual_downloads: vec![],
                },
                PackFormat::Curseforge => {
                    let cf = state.curseforge().await;
                    modpacks::curseforge::import(
                        &engine,
                        &state.instances,
                        &cf,
                        &modrinth,
                        &path,
                        None,
                        &ctx,
                    )
                    .await?
                }
                PackFormat::Luncher => ImportResult {
                    instance: modpacks::native::import(&state.instances, &path).await?,
                    manual_downloads: vec![],
                },
            };
            Ok(serde_json::to_string(&result).ok())
        },
    ))
}

#[tauri::command]
#[specta::specta]
pub async fn export_instance(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    path: String,
    options: ExportOptions,
) -> CmdResult<String> {
    let inst = state.instances.get(&id).await?;
    let app2 = app.clone();
    Ok(state.tasks.spawn(
        &app,
        "export",
        "task.export",
        Some(inst.name.clone()),
        Some(id.clone()),
        move |_ctx| async move {
            use tauri::Manager;
            let state = app2.state::<AppState>();
            let dest = PathBuf::from(&path);
            match options.format {
                PackFormat::Mrpack => {
                    let engine = state.engine().await;
                    let modrinth = state.modrinth().await;
                    modpacks::mrpack::export(
                        &engine,
                        &state.instances,
                        &modrinth,
                        &id,
                        &dest,
                        &options,
                    )
                    .await?
                }
                PackFormat::Curseforge => {
                    modpacks::curseforge::export(&state.instances, &id, &dest, &options).await?
                }
                PackFormat::Luncher => {
                    modpacks::native::export(&state.instances, &id, &dest).await?
                }
            }
            Ok(Some(serde_json::to_string(&path).unwrap_or_default()))
        },
    ))
}

/// Top-level entries of the game directory (export dialog checklist).
#[tauri::command]
#[specta::specta]
pub async fn list_game_dir(state: State<'_, AppState>, id: String) -> CmdResult<Vec<String>> {
    let dir = state.instances.game_dir(&id)?;
    let mut out = Vec::new();
    if let Ok(mut rd) = tokio::fs::read_dir(&dir).await {
        while let Ok(Some(e)) = rd.next_entry().await {
            let name = e.file_name().to_string_lossy().into_owned();
            if !modpacks::excluded_from_export(&name) {
                out.push(name);
            }
        }
    }
    out.sort();
    Ok(out)
}
