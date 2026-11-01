//! Background mod update checks: shortly after start and then every six hours, every instance
//! with installed content is checked; the UI shows the counts as badges.

use std::collections::HashMap;
use std::time::Duration;

use launcher_core::mods::content::UpdateInfo;
use serde::Serialize;
use specta::Type;
use tauri::{AppHandle, Manager};
use tauri_specta::Event;

use crate::state::AppState;

/// Instance id → number of available updates.
#[derive(Debug, Clone, Serialize, Type, Event)]
#[serde(rename_all = "camelCase")]
pub struct ContentUpdatesEvent {
    pub counts: HashMap<String, u32>,
}

pub async fn counts(state: &AppState) -> HashMap<String, u32> {
    state
        .content_updates
        .read()
        .await
        .iter()
        .filter(|(_, u)| !u.is_empty())
        .map(|(id, u)| (id.clone(), u.len() as u32))
        .collect()
}

/// Remember an instance's updates and tell the UI.
pub async fn store(app: &AppHandle, instance_id: &str, updates: Vec<UpdateInfo>) {
    let state = app.state::<AppState>();
    state
        .content_updates
        .write()
        .await
        .insert(instance_id.to_owned(), updates);
    let _ = ContentUpdatesEvent {
        counts: counts(&state).await,
    }
    .emit(app);
}

async fn check_instance(app: &AppHandle, id: &str) -> launcher_core::Result<()> {
    let state = app.state::<AppState>();
    let inst = state.instances.get(id).await?;
    let content = state.instances.content(id)?;
    if content.entries().await.iter().all(|e| e.provider.is_none()) {
        return Ok(());
    }
    let cf = state.curseforge().await;
    let updates = content
        .check_updates(
            &state.modrinth().await,
            Some(&cf),
            &inst.game_version,
            inst.loader,
        )
        .await?;
    store(app, id, updates).await;
    Ok(())
}

pub async fn run(app: AppHandle) {
    // Let the launcher finish starting (and the user act) before using the network.
    tokio::time::sleep(Duration::from_secs(30)).await;
    loop {
        let state = app.state::<AppState>();
        if state.settings.read().await.check_mod_updates {
            let ids: Vec<String> = state
                .instances
                .list()
                .await
                .into_iter()
                .map(|i| i.id)
                .collect();
            for id in ids {
                if state.games.is_running(&id) {
                    continue;
                }
                if let Err(e) = check_instance(&app, &id).await {
                    tracing::debug!(instance = %id, error = %e, "background mod update check failed");
                }
                // Be gentle with the mod platforms.
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
        }
        tokio::time::sleep(Duration::from_secs(6 * 3600)).await;
    }
}
