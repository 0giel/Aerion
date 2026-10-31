use launcher_core::instances::LoaderKind;
use launcher_core::java::JavaInstall;
use launcher_core::java::detect::detect_system;
use launcher_core::java::flags::probe_args;
use launcher_core::loaders::{self, LoaderVersion};
use serde::Serialize;
use specta::Type;
use tauri::State;

use super::CmdResult;
use crate::state::AppState;

#[derive(Debug, Clone, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub struct GameVersion {
    pub id: String,
    /// `release`, `snapshot`, `old_beta`, `old_alpha`.
    pub kind: String,
    pub release_time: String,
}

#[derive(Debug, Clone, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub struct GameVersions {
    pub latest_release: String,
    pub latest_snapshot: String,
    pub versions: Vec<GameVersion>,
}

#[tauri::command]
#[specta::specta]
pub async fn list_game_versions(state: State<'_, AppState>) -> CmdResult<GameVersions> {
    let m = state.engine().await.meta.manifest().await?;
    Ok(GameVersions {
        latest_release: m.latest.release.clone(),
        latest_snapshot: m.latest.snapshot.clone(),
        versions: m
            .versions
            .into_iter()
            .map(|v| GameVersion {
                id: v.id,
                kind: v.kind,
                release_time: v.release_time,
            })
            .collect(),
    })
}

#[tauri::command]
#[specta::specta]
pub async fn list_loader_versions(
    state: State<'_, AppState>,
    loader: LoaderKind,
    game_version: String,
) -> CmdResult<Vec<LoaderVersion>> {
    Ok(loaders::versions(&state.engine().await, loader, &game_version).await?)
}

/// System Java installations plus runtimes managed by Aerion.
#[tauri::command]
#[specta::specta]
pub async fn list_java(state: State<'_, AppState>) -> CmdResult<Vec<JavaInstall>> {
    let engine = state.engine().await;
    let mut all = engine.runtimes().list_installed().await;
    all.extend(detect_system().await);
    Ok(all)
}

#[tauri::command]
#[specta::specta]
pub async fn test_jvm_args(java: String, args: Vec<String>) -> CmdResult<()> {
    Ok(probe_args(std::path::Path::new(&java), &args).await?)
}
