use std::path::PathBuf;

use launcher_core::ErrorInfo;
use launcher_core::game::install::prepare;
use launcher_core::horizon;
use launcher_core::importers::{self, ExternalInstance};
use launcher_core::instances::launch::{ensure_version, pick_java};
use launcher_core::instances::{Instance, NewInstance, shortcut};
use launcher_core::loaders::LoaderKind;
use launcher_core::modpacks::{self, ExportOptions, ImportResult, PackFormat};
use launcher_core::net::download::VerifyMode;
use tauri::{AppHandle, Manager, State};

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
    // New instances start with the saved game settings, if any (a failure only costs that).
    let (tpl, game) = (
        state.paths.options_template_dir(),
        state.instances.game_dir(&inst.id)?,
    );
    let _ = tokio::task::spawn_blocking(move || {
        launcher_core::instances::options::apply_template(&tpl, &game)
    })
    .await;
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

/// Move the instance's game folder to `path` (empty or new folder), or back into the launcher's
/// data folder with `None`.
#[tauri::command]
#[specta::specta]
pub async fn move_instance_game_dir(
    state: State<'_, AppState>,
    id: String,
    path: Option<String>,
) -> CmdResult<Instance> {
    if state.games.is_running(&id) {
        return Err(launcher_core::Error::AlreadyRunning.into());
    }
    Ok(state
        .instances
        .move_game_dir(&id, path.map(PathBuf::from))
        .await?)
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
            if inst.loader == LoaderKind::Horizon {
                // Its own files: the build, and the resources prepared from the 26.3 files.
                let build = match inst
                    .loader_version
                    .as_deref()
                    .and_then(|b| horizon::installed_build(&engine, b))
                {
                    Some(b) => b,
                    None => install_horizon(&state, &engine, &id, &ctx).await?,
                };
                horizon::ensure_resources(&engine, &build, None, &ctx).await?;
                return Ok(None);
            }
            let version = ensure_version(&engine, &state.instances, &inst, &ctx).await?;
            let prepared =
                prepare(&engine.meta, &engine.env, &version, VerifyMode::Full, &ctx).await?;
            pick_java(&engine, &settings, &inst, &prepared.java, &ctx).await?;
            Ok(None)
        },
    ))
}

/// Install the newest Horizon build for an instance and switch the instance to it.
async fn install_horizon(
    state: &AppState,
    engine: &launcher_core::Engine,
    id: &str,
    ctx: &launcher_core::progress::TaskCtx,
) -> launcher_core::error::Result<horizon::Build> {
    let build = horizon::install_latest(engine, ctx).await?;
    let b = build.id.clone();
    state
        .instances
        .modify(id, move |i| i.loader_version = Some(b))
        .await?;
    Ok(build)
}

/// Download the newest Horizon build (if there is a newer one) and prepare it, so the next start
/// uses it. The old build stays on disk until this one is ready.
#[tauri::command]
#[specta::specta]
pub async fn update_horizon(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
) -> CmdResult<String> {
    let inst = state.instances.get(&id).await?;
    if inst.loader != LoaderKind::Horizon {
        return Err(launcher_core::Error::Invalid("not a Horizon instance".into()).into());
    }
    let app2 = app.clone();
    Ok(state.tasks.spawn(
        &app,
        "horizonUpdate",
        "task.horizonUpdate",
        Some(inst.name.clone()),
        Some(id.clone()),
        move |ctx| async move {
            use tauri::Manager;
            let state = app2.state::<AppState>();
            let engine = state.engine().await;
            let previous = state.instances.get(&id).await?.loader_version;
            let build = horizon::install_latest(&engine, &ctx).await?;
            horizon::ensure_resources(&engine, &build, None, &ctx).await?;
            let b = build.id.clone();
            state
                .instances
                .modify(&id, move |i| i.loader_version = Some(b))
                .await?;
            // Builds no instance uses go; this instance's previous one stays as a fallback
            // until the next update, as Horizon advises.
            let mut keep: Vec<String> = state
                .instances
                .list()
                .await
                .into_iter()
                .filter(|i| i.loader == LoaderKind::Horizon)
                .filter_map(|i| i.loader_version)
                .collect();
            keep.extend(previous);
            horizon::prune(&engine, &keep).await;
            Ok(Some(build.id))
        },
    ))
}

/// Import a `.mrpack`, CurseForge zip or Aerion archive (also used for drag & drop).
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
                PackFormat::Aerion => ImportResult {
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
                PackFormat::Aerion => {
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

/// Instances of other launchers found on this computer.
#[tauri::command]
#[specta::specta]
pub async fn detect_external_instances() -> CmdResult<Vec<ExternalInstance>> {
    Ok(tokio::task::spawn_blocking(importers::detect)
        .await
        .map_err(|e| launcher_core::Error::Invalid(e.to_string()))?)
}

/// Read a folder the user picked as a possible game folder.
#[tauri::command]
#[specta::specta]
pub async fn inspect_external_folder(path: String) -> CmdResult<ExternalInstance> {
    Ok(
        tokio::task::spawn_blocking(move || importers::inspect_folder(&PathBuf::from(path)))
            .await
            .map_err(|e| launcher_core::Error::Invalid(e.to_string()))??,
    )
}

/// Import another launcher's instance: copy its files (default) or use its folder in place
/// (`link`). `ext` carries the version/loader the user confirmed.
#[tauri::command]
#[specta::specta]
pub async fn import_external(
    app: AppHandle,
    state: State<'_, AppState>,
    ext: ExternalInstance,
    name: String,
    link: bool,
) -> CmdResult<String> {
    let game_version = ext
        .game_version
        .clone()
        .filter(|v| !v.trim().is_empty())
        .ok_or_else(|| launcher_core::Error::Invalid("choose the Minecraft version".into()))?;
    let app2 = app.clone();
    Ok(state.tasks.spawn(
        &app,
        "import",
        "task.import",
        Some(name.clone()),
        None,
        move |ctx| async move {
            let state = app2.state::<AppState>();
            let inst = state
                .instances
                .create(NewInstance {
                    name,
                    game_version,
                    loader: ext.loader,
                    loader_version: ext.loader_version.clone(),
                    icon: None,
                    group: None,
                    game_dir: link.then(|| ext.game_dir.clone()),
                    link_existing: link,
                    aerion_mod: false,
                    shared_options: false,
                })
                .await?;
            if !link {
                ctx.stage("stage.copyFiles", None);
                let (src, dst) = (ext.game_dir.clone(), state.instances.game_dir(&inst.id)?);
                let top = src.clone();
                tokio::task::spawn_blocking(move || {
                    // Only the game folder's own top-level entries are filtered.
                    launcher_core::util::fs::copy_dir_sync(&src, &dst, &|p| {
                        p.parent() == Some(top.as_path())
                            && p.file_name()
                                .is_some_and(|n| importers::skip_on_copy(&n.to_string_lossy()))
                    })
                })
                .await
                .map_err(|e| launcher_core::Error::Invalid(e.to_string()))??;
            }
            // Recognise mods (for updates); a failure only costs that.
            ctx.stage("stage.identify", None);
            if let Ok(content) = state.instances.content(&inst.id) {
                let _ = content.scan().await;
                let cf = state.curseforge().await;
                let _ = content.identify(&state.modrinth().await, Some(&cf)).await;
            }
            let result = ImportResult {
                instance: inst,
                manual_downloads: vec![],
            };
            Ok(serde_json::to_string(&result).ok())
        },
    ))
}
