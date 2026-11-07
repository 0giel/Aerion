use std::collections::HashSet;
use std::path::PathBuf;

use launcher_core::ErrorInfo;
use launcher_core::accounts::SecretStore;
use launcher_core::instances::ModpackInfo;
use launcher_core::loaders::LoaderKind;
use launcher_core::modpacks::{self, ImportResult};
use launcher_core::mods::content::{BackupInfo, ContentEntry, ContentRef, UpdateInfo, UpgradePlan};
use launcher_core::mods::curseforge::CurseForge;
use launcher_core::mods::merge_pages;
use launcher_core::mods::model::*;
use launcher_core::mods::modrinth::Modrinth;
use launcher_core::mods::resolve::{InstallPlan, Target, plan};
use launcher_core::mods::select::{best_version, loaders_for};
use launcher_core::net::download::{DownloadItem, VerifyMode};
use launcher_core::settings::ModSource;
use launcher_core::util::fs::safe_file_name;
use serde::Serialize;
use specta::Type;
use tauri::{AppHandle, Manager, State};

use super::CmdResult;
use crate::state::{AppState, CF_KEY_ENTRY};

#[tauri::command]
#[specta::specta]
pub async fn search_projects(
    state: State<'_, AppState>,
    query: SearchQuery,
    source: ModSource,
) -> CmdResult<SearchPage> {
    let mr = state.modrinth().await;
    let cf = state.curseforge().await;
    Ok(match source {
        ModSource::Modrinth => mr.search(&query).await?,
        ModSource::Curseforge => cf.search(&query).await?,
        ModSource::Both if !cf.has_key() => mr.search(&query).await?,
        ModSource::Both => {
            let half = SearchQuery {
                limit: query.limit.div_ceil(2),
                offset: query.offset / 2,
                ..query.clone()
            };
            let (a, b) = tokio::join!(mr.search(&half), cf.search(&half));
            match (a, b) {
                (Ok(a), Ok(b)) => merge_pages(a, b),
                (Ok(a), Err(_)) => a,
                (Err(_), Ok(b)) => b,
                (Err(e), Err(_)) => return Err(e.into()),
            }
        }
    })
}

#[tauri::command]
#[specta::specta]
pub async fn get_project(
    state: State<'_, AppState>,
    provider: Provider,
    id: String,
) -> CmdResult<ProjectDetails> {
    Ok(match provider {
        Provider::Modrinth => state.modrinth().await.project(&id).await?,
        Provider::Curseforge => state.curseforge().await.project(&id).await?,
    })
}

#[tauri::command]
#[specta::specta]
pub async fn get_project_versions(
    state: State<'_, AppState>,
    provider: Provider,
    id: String,
    game_version: Option<String>,
    loader: Option<LoaderKind>,
    kind: ContentKind,
) -> CmdResult<Vec<ProjectVersion>> {
    let gv = game_version.unwrap_or_default();
    let loaders = loader
        .map(|l| loaders_for(kind, l, &gv))
        .unwrap_or_default();
    let gvo = Some(gv.as_str()).filter(|g| !g.is_empty());
    Ok(match provider {
        Provider::Modrinth => state.modrinth().await.versions(&id, gvo, &loaders).await?,
        Provider::Curseforge => {
            state
                .curseforge()
                .await
                .versions(&id, gvo, &loaders)
                .await?
        }
    })
}

#[tauri::command]
#[specta::specta]
pub async fn get_categories(
    state: State<'_, AppState>,
    provider: Provider,
    kind: ContentKind,
) -> CmdResult<Vec<Category>> {
    Ok(match provider {
        Provider::Modrinth => state.modrinth().await.categories(kind).await?,
        Provider::Curseforge => state.curseforge().await.categories(kind).await?,
    })
}

async fn make_plan(
    state: &AppState,
    instance_id: &str,
    provider: Provider,
    project_id: &str,
    version_id: Option<&str>,
    kind: ContentKind,
    already: &HashSet<(Provider, String)>,
) -> launcher_core::Result<InstallPlan> {
    let inst = state.instances.get(instance_id).await?;
    let loaders = loaders_for(kind, inst.loader, &inst.game_version);
    let target = Target {
        game_version: &inst.game_version,
        loaders: &loaders,
    };
    let content = state.instances.content(instance_id)?;
    let mut installed = content.installed_projects().await;
    installed.extend(already.iter().cloned());
    match provider {
        Provider::Modrinth => {
            plan(
                &state.modrinth().await,
                project_id,
                version_id,
                &target,
                &installed,
            )
            .await
        }
        Provider::Curseforge => {
            plan(
                &state.curseforge().await,
                project_id,
                version_id,
                &target,
                &installed,
            )
            .await
        }
    }
}

/// What will be installed: the best compatible file plus required dependencies; optional
/// dependencies, conflicts and files needing a manual download are listed separately.
#[tauri::command]
#[specta::specta]
pub async fn plan_content_install(
    state: State<'_, AppState>,
    instance_id: String,
    provider: Provider,
    project_id: String,
    version_id: Option<String>,
    kind: ContentKind,
) -> CmdResult<InstallPlan> {
    Ok(make_plan(
        &state,
        &instance_id,
        provider,
        &project_id,
        version_id.as_deref(),
        kind,
        &HashSet::new(),
    )
    .await?)
}

#[tauri::command]
#[specta::specta]
#[allow(clippy::too_many_arguments)]
pub async fn install_content(
    app: AppHandle,
    state: State<'_, AppState>,
    instance_id: String,
    provider: Provider,
    project_id: String,
    version_id: Option<String>,
    kind: ContentKind,
    optional: Vec<String>,
    title: Option<String>,
) -> CmdResult<String> {
    let app2 = app.clone();
    let iid = instance_id.clone();
    Ok(state.tasks.spawn(
        &app,
        "install",
        "task.install",
        title,
        Some(instance_id),
        move |ctx| async move {
            let state = app2.state::<AppState>();
            ctx.stage("stage.resolve", None);
            let mut main = make_plan(
                &state,
                &iid,
                provider,
                &project_id,
                version_id.as_deref(),
                kind,
                &HashSet::new(),
            )
            .await?;
            let mut planned: HashSet<(Provider, String)> = main
                .install
                .iter()
                .map(|f| (provider, f.project_id.clone()))
                .collect();
            for opt in optional {
                if let Ok(extra) = make_plan(
                    &state,
                    &iid,
                    provider,
                    &opt,
                    None,
                    ContentKind::Mod,
                    &planned,
                )
                .await
                {
                    for f in extra.install {
                        if planned.insert((provider, f.project_id.clone())) {
                            main.install.push(f);
                        }
                    }
                }
            }
            let content = state.instances.content(&iid)?;
            let engine = state.engine().await;
            let installed = content
                .install(&engine.downloader, &main, kind, &ctx)
                .await?;
            Ok(serde_json::to_string(&installed.len()).ok())
        },
    ))
}

#[tauri::command]
#[specta::specta]
pub async fn list_content(
    state: State<'_, AppState>,
    instance_id: String,
) -> CmdResult<Vec<ContentEntry>> {
    Ok(state.instances.content(&instance_id)?.scan().await?)
}

#[tauri::command]
#[specta::specta]
pub async fn set_content_enabled(
    state: State<'_, AppState>,
    instance_id: String,
    kind: ContentKind,
    file_name: String,
    enabled: bool,
) -> CmdResult<()> {
    Ok(state
        .instances
        .content(&instance_id)?
        .set_enabled(kind, &file_name, enabled)
        .await?)
}

#[tauri::command]
#[specta::specta]
pub async fn remove_content(
    state: State<'_, AppState>,
    instance_id: String,
    kind: ContentKind,
    file_name: String,
) -> CmdResult<()> {
    Ok(state
        .instances
        .content(&instance_id)?
        .remove(kind, &file_name)
        .await?)
}

/// Recognise manually added files by hash (Modrinth) or fingerprint (CurseForge).
#[tauri::command]
#[specta::specta]
pub async fn identify_content(state: State<'_, AppState>, instance_id: String) -> CmdResult<u32> {
    let content = state.instances.content(&instance_id)?;
    let cf = state.curseforge().await;
    let n = content.identify(&state.modrinth().await, Some(&cf)).await?;
    Ok(n as u32)
}

#[tauri::command]
#[specta::specta]
pub async fn check_content_updates(
    app: AppHandle,
    state: State<'_, AppState>,
    instance_id: String,
) -> CmdResult<Vec<UpdateInfo>> {
    let inst = state.instances.get(&instance_id).await?;
    let content = state.instances.content(&instance_id)?;
    let cf = state.curseforge().await;
    let updates = content
        .check_updates(
            &state.modrinth().await,
            Some(&cf),
            &inst.game_version,
            inst.loader,
        )
        .await?;
    crate::content_watch::store(&app, &instance_id, updates.clone()).await;
    Ok(updates)
}

/// Problems in the instance's mods folder (wrong loader, duplicates, missing dependencies,
/// conflicts, other game versions), read from the jars themselves.
#[tauri::command]
#[specta::specta]
pub async fn check_mod_health(
    state: State<'_, AppState>,
    instance_id: String,
) -> CmdResult<Vec<launcher_core::mods::health::ModIssue>> {
    let inst = state.instances.get(&instance_id).await?;
    let mods = state.instances.game_dir(&instance_id)?.join("mods");
    Ok(launcher_core::util::blocking(move || {
        Ok(launcher_core::mods::health::check(
            &mods,
            &inst.game_version,
            inst.loader,
        ))
    })
    .await?)
}

#[tauri::command]
#[specta::specta]
pub async fn set_content_pinned(
    state: State<'_, AppState>,
    instance_id: String,
    kind: ContentKind,
    file_name: String,
    pinned: bool,
) -> CmdResult<()> {
    Ok(state
        .instances
        .content(&instance_id)?
        .set_pinned(kind, &file_name, pinned)
        .await?)
}

/// Known successors and similar Modrinth projects for content the instance can't use.
#[tauri::command]
#[specta::specta]
pub async fn find_replacements(
    state: State<'_, AppState>,
    instance_id: String,
    kind: ContentKind,
    title: String,
    provider: Option<Provider>,
    project_id: Option<String>,
    // Another version than the instance's (moving the instance to it).
    game_version: Option<String>,
) -> CmdResult<Vec<launcher_core::mods::replace::Replacement>> {
    let inst = state.instances.get(&instance_id).await?;
    let game_version = game_version.unwrap_or(inst.game_version);
    let modrinth = state.modrinth().await;
    // Modrinth projects tell their slug and categories, which sharpen the search.
    let (slug, categories) = match (provider, project_id.as_deref()) {
        (Some(Provider::Modrinth), Some(id)) => match modrinth.project(id).await {
            Ok(p) => (Some(p.summary.slug), p.summary.categories),
            Err(_) => (None, Vec::new()),
        },
        _ => (None, Vec::new()),
    };
    Ok(launcher_core::mods::replace::find(
        &modrinth,
        kind,
        &title,
        slug.as_deref(),
        project_id.as_deref(),
        categories,
        &game_version,
        inst.loader,
    )
    .await?)
}

/// Updates found for one instance by the last check (no network).
#[tauri::command]
#[specta::specta]
pub async fn pending_content_updates(
    state: State<'_, AppState>,
    instance_id: String,
) -> CmdResult<Vec<UpdateInfo>> {
    Ok(state
        .content_updates
        .read()
        .await
        .get(&instance_id)
        .cloned()
        .unwrap_or_default())
}

/// Instance id → mod updates found so far (background check or the Content tab).
#[tauri::command]
#[specta::specta]
pub async fn content_update_counts(
    state: State<'_, AppState>,
) -> CmdResult<std::collections::HashMap<String, u32>> {
    Ok(crate::content_watch::counts(&state).await)
}

#[tauri::command]
#[specta::specta]
pub async fn apply_content_updates(
    app: AppHandle,
    state: State<'_, AppState>,
    instance_id: String,
    updates: Vec<UpdateInfo>,
    pin: bool,
) -> CmdResult<String> {
    let inst = state.instances.get(&instance_id).await?;
    let app2 = app.clone();
    Ok(state.tasks.spawn(
        &app,
        "update",
        "task.update",
        Some(inst.name),
        Some(instance_id.clone()),
        move |ctx| async move {
            let state = app2.state::<AppState>();
            let content = state.instances.content(&instance_id)?;
            // Worlds first: a broken mod update can corrupt them on the next launch.
            ctx.stage("stage.worldBackup", None);
            state.instances.backup_worlds(&instance_id).await?;
            let engine = state.engine().await;
            let backup: BackupInfo = content
                .apply_updates(&engine.downloader, &updates, &ctx)
                .await?;
            // A version chosen by hand stays: later update checks leave it alone.
            if pin {
                for u in &updates {
                    if let Some(f) = u.version.primary_file() {
                        content.set_pinned(u.kind, &f.filename, true).await?;
                    }
                }
            }
            // Applied updates are no longer pending.
            let left: Vec<UpdateInfo> = state
                .content_updates
                .read()
                .await
                .get(&instance_id)
                .map(|all| {
                    all.iter()
                        .filter(|a| !updates.iter().any(|u| u.file_name == a.file_name))
                        .cloned()
                        .collect()
                })
                .unwrap_or_default();
            crate::content_watch::store(&app2, &instance_id, left).await;
            Ok(serde_json::to_string(&backup).ok())
        },
    ))
}

#[tauri::command]
#[specta::specta]
pub async fn undo_content_update(
    state: State<'_, AppState>,
    instance_id: String,
) -> CmdResult<bool> {
    Ok(state
        .instances
        .content(&instance_id)?
        .undo_last_update()
        .await?)
}

#[tauri::command]
#[specta::specta]
pub async fn list_content_backups(
    state: State<'_, AppState>,
    instance_id: String,
) -> CmdResult<Vec<BackupInfo>> {
    Ok(state.instances.content(&instance_id)?.backups().await)
}

/// Copy local files (drag & drop) into the instance.
#[tauri::command]
#[specta::specta]
pub async fn add_content_files(
    state: State<'_, AppState>,
    instance_id: String,
    kind: ContentKind,
    paths: Vec<String>,
) -> CmdResult<u32> {
    let content = state.instances.content(&instance_id)?;
    let dir = content.folder(kind);
    tokio::fs::create_dir_all(&dir)
        .await
        .map_err(|e| launcher_core::Error::io(&dir, e))?;
    let mut n = 0;
    for p in paths {
        let src = PathBuf::from(&p);
        let Some(name) = src.file_name().map(|n| n.to_string_lossy().into_owned()) else {
            continue;
        };
        safe_file_name(&name)?;
        let ext_ok = match kind {
            ContentKind::Mod => name.ends_with(".jar"),
            _ => name.ends_with(".zip"),
        };
        if !ext_ok || !src.is_file() {
            continue;
        }
        tokio::fs::copy(&src, dir.join(&name))
            .await
            .map_err(|e| launcher_core::Error::io(&src, e))?;
        n += 1;
    }
    let cf = state.curseforge().await;
    let _ = content.identify(&state.modrinth().await, Some(&cf)).await;
    Ok(n)
}

/// Install a modpack from Modrinth or CurseForge as a new instance.
#[tauri::command]
#[specta::specta]
pub async fn install_modpack(
    app: AppHandle,
    state: State<'_, AppState>,
    provider: Provider,
    project_id: String,
    version_id: Option<String>,
    title: Option<String>,
) -> CmdResult<String> {
    let app2 = app.clone();
    Ok(state.tasks.spawn(
        &app,
        "modpack",
        "task.modpack",
        title.clone(),
        None,
        move |ctx| async move {
            let state = app2.state::<AppState>();
            let engine = state.engine().await;
            let modrinth: Modrinth = state.modrinth().await;
            let cf: CurseForge = state.curseforge().await;
            ctx.stage("stage.resolve", None);
            let versions = match (&version_id, provider) {
                (Some(v), Provider::Modrinth) => {
                    modrinth.versions_by_id(std::slice::from_ref(v)).await?
                }
                (Some(v), Provider::Curseforge) => {
                    cf.versions_by_id(std::slice::from_ref(v)).await?
                }
                (None, Provider::Modrinth) => modrinth.versions(&project_id, None, &[]).await?,
                (None, Provider::Curseforge) => cf.versions(&project_id, None, &[]).await?,
            };
            let version = match &version_id {
                Some(_) => versions.first(),
                None => best_version(&versions, "", &[]),
            }
            .cloned()
            .ok_or_else(|| launcher_core::Error::NoCompatibleFile {
                project: project_id.clone(),
                game_version: "-".into(),
                loader: "-".into(),
            })?;
            let file = version
                .primary_file()
                .cloned()
                .ok_or_else(|| launcher_core::Error::Invalid("pack has no file".into()))?;
            let Some(url) = file.url.clone() else {
                return Err(launcher_core::Error::DistributionDisabled {
                    project: title.clone().unwrap_or_default(),
                    file_name: file.filename.clone(),
                    url: version.page_url.clone().unwrap_or_default(),
                });
            };
            safe_file_name(&file.filename)?;
            let dest = engine.paths.temp_dir().join(&file.filename);
            let hash = file.hash();
            ctx.stage("stage.download", Some(file.filename.clone()));
            engine
                .downloader
                .run(
                    vec![
                        DownloadItem::new(url, &dest)
                            .hash(hash)
                            .size(Some(file.size)),
                    ],
                    VerifyMode::Full,
                    &ctx,
                )
                .await?;
            let origin = ModpackInfo {
                provider: match provider {
                    Provider::Modrinth => "modrinth".into(),
                    Provider::Curseforge => "curseforge".into(),
                },
                project_id: Some(project_id.clone()),
                version_id: Some(version.id.clone()),
                name: title.clone().unwrap_or_else(|| version.name.clone()),
                version_name: Some(version.version_number.clone()),
            };
            let result = match provider {
                Provider::Modrinth => ImportResult {
                    instance: modpacks::mrpack::import(
                        &engine,
                        &state.instances,
                        &modrinth,
                        &dest,
                        Some(origin),
                        &ctx,
                    )
                    .await?,
                    manual_downloads: vec![],
                },
                Provider::Curseforge => {
                    modpacks::curseforge::import(
                        &engine,
                        &state.instances,
                        &cf,
                        &modrinth,
                        &dest,
                        Some(origin),
                        &ctx,
                    )
                    .await?
                }
            };
            let _ = tokio::fs::remove_file(&dest).await;
            Ok(serde_json::to_string(&result).ok())
        },
    ))
}

#[derive(Debug, Clone, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub struct CurseForgeStatus {
    pub configured: bool,
    /// The key comes from `CURSEFORGE_API_KEY` and cannot be changed in the UI.
    pub from_env: bool,
}

#[tauri::command]
#[specta::specta]
pub async fn curseforge_status(state: State<'_, AppState>) -> CmdResult<CurseForgeStatus> {
    Ok(CurseForgeStatus {
        configured: state.cf_key.read().await.is_some(),
        from_env: state.cf_key_from_env,
    })
}

/// Validate and store (or remove) the user's CurseForge API key in the OS keyring.
#[tauri::command]
#[specta::specta]
pub async fn set_curseforge_key(
    state: State<'_, AppState>,
    key: Option<String>,
) -> CmdResult<CurseForgeStatus> {
    if state.cf_key_from_env {
        return Err(ErrorInfo::msg(
            "curseforge.fromEnv",
            "the key is set by CURSEFORGE_API_KEY",
        ));
    }
    let key = key.map(|k| k.trim().to_owned()).filter(|k| !k.is_empty());
    let store = state.keyring.clone();
    match &key {
        Some(k) => {
            let engine = state.engine().await;
            CurseForge::new(engine.http.clone(), Some(k.clone()))
                .test_key()
                .await
                .map_err(|e| {
                    let mut info = e.info();
                    if matches!(
                        e,
                        launcher_core::Error::Http {
                            status: 401 | 403,
                            ..
                        }
                    ) {
                        info.code = "curseforge.invalidKey".into();
                    }
                    info
                })?;
            let k = k.clone();
            tokio::task::spawn_blocking(move || store.set(CF_KEY_ENTRY, &k))
                .await
                .map_err(|e| ErrorInfo::msg("generic", e.to_string()))??;
        }
        None => {
            tokio::task::spawn_blocking(move || store.delete(CF_KEY_ENTRY))
                .await
                .map_err(|e| ErrorInfo::msg("generic", e.to_string()))??;
        }
    }
    *state.cf_key.write().await = key.clone();
    Ok(CurseForgeStatus {
        configured: key.is_some(),
        from_env: false,
    })
}

#[derive(Debug, Clone, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub struct UpgradeCheck {
    pub plan: UpgradePlan,
    /// The instance's mod loader exists for the target version (always true for vanilla).
    pub loader_available: bool,
}

/// What moving an instance to `target` would do to its mods.
#[tauri::command]
#[specta::specta]
pub async fn instance_upgrade_plan(
    state: State<'_, AppState>,
    instance_id: String,
    target: String,
) -> CmdResult<UpgradeCheck> {
    let inst = state.instances.get(&instance_id).await?;
    let content = state.instances.content(&instance_id)?;
    let cf = state.curseforge().await;
    let plan = content
        .upgrade_plan(&state.modrinth().await, Some(&cf), &target, inst.loader)
        .await?;
    let loader_available = inst.loader == LoaderKind::Vanilla
        || !launcher_core::loaders::versions(&state.engine().await, inst.loader, &target)
            .await
            .unwrap_or_default()
            .is_empty();
    Ok(UpgradeCheck {
        plan,
        loader_available,
    })
}

/// Move an instance (or a copy named `copy_name`) to another Minecraft version: back up worlds,
/// update mods, disable the chosen unavailable ones, switch version (latest loader for it).
/// The task result is the upgraded instance's id.
#[tauri::command]
#[specta::specta]
pub async fn upgrade_instance(
    app: AppHandle,
    state: State<'_, AppState>,
    instance_id: String,
    target: String,
    updates: Vec<UpdateInfo>,
    disable: Vec<ContentRef>,
    copy_name: Option<String>,
) -> CmdResult<String> {
    if state.games.is_running(&instance_id) {
        return Err(launcher_core::Error::AlreadyRunning.into());
    }
    let inst = state.instances.get(&instance_id).await?;
    let app2 = app.clone();
    Ok(state.tasks.spawn(
        &app,
        "upgrade",
        "task.upgrade",
        Some(format!("{} → {target}", inst.name)),
        Some(instance_id.clone()),
        move |ctx| async move {
            let state = app2.state::<AppState>();
            let id = match copy_name {
                Some(name) => {
                    ctx.stage("stage.copyInstance", None);
                    state
                        .instances
                        .clone_instance(&instance_id, &name)
                        .await?
                        .id
                }
                None => instance_id,
            };
            ctx.stage("stage.worldBackup", None);
            state.instances.backup_worlds(&id).await?;
            let content = state.instances.content(&id)?;
            if !updates.is_empty() {
                let engine = state.engine().await;
                content
                    .apply_updates(&engine.downloader, &updates, &ctx)
                    .await?;
            }
            for c in &disable {
                content.set_enabled(c.kind, &c.file_name, false).await?;
            }
            ctx.check()?;
            let t = target.clone();
            state
                .instances
                .modify(&id, move |i| {
                    i.game_version = t;
                    i.loader_version = None;
                    i.version_id = None;
                })
                .await?;
            Ok(serde_json::to_string(&id).ok())
        },
    ))
}
