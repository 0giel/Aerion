use launcher_core::error::AuthError;
use launcher_core::game::logs::LogLine;
use launcher_core::instances::launch::plan;
use launcher_core::net::download::VerifyMode;
use tauri::{AppHandle, Manager, State};

use super::CmdResult;
use crate::games::{self, RunningGame};
use crate::state::AppState;

/// Launch an instance with the given (or selected) account. Returns the preparation task id;
/// the game itself is reported through `GameStateEvent` / `GameLogEvent`.
#[tauri::command]
#[specta::specta]
pub async fn launch_instance(app: AppHandle, state: State<'_, AppState>, id: String, account_id: Option<String>) -> CmdResult<String> {
    start_launch(&app, &state, id, account_id).await
}

pub async fn start_launch(app: &AppHandle, state: &AppState, id: String, account_id: Option<String>) -> CmdResult<String> {
    if state.games.is_running(&id) || state.tasks.running_for(&id, "launch") {
        return Err(launcher_core::Error::AlreadyRunning.into());
    }
    let inst = state.instances.get(&id).await?;
    {
        let mut s = state.settings.write().await;
        s.last_instance = Some(id.clone());
        s.save(&state.paths).await?;
    }
    let app2 = app.clone();
    Ok(state.tasks.spawn(app, "launch", "task.launch", Some(inst.name.clone()), Some(id.clone()), move |ctx| async move {
        let state = app2.state::<AppState>();
        let settings = state.settings.read().await.clone();
        let engine = state.engine().await;
        let account = match account_id {
            Some(a) => a,
            None => state.accounts.state().await.selected.ok_or(AuthError::NoAccount)?,
        };
        ctx.stage("stage.account", None);
        let (_, auth) = state
            .accounts
            .launch_auth(&account, &engine.http, &state.endpoints, settings.effective_client_id().as_deref())
            .await?;
        let plan = plan(&engine, &settings, &state.instances, &id, auth, VerifyMode::Fast, &ctx).await?;
        ctx.check()?;
        games::start(app2.clone(), plan, settings.after_launch);
        Ok(None)
    }))
}

#[tauri::command]
#[specta::specta]
pub async fn stop_instance(state: State<'_, AppState>, id: String) -> CmdResult<bool> {
    Ok(state.games.stop(&id))
}

#[tauri::command]
#[specta::specta]
pub async fn running_games(state: State<'_, AppState>) -> CmdResult<Vec<RunningGame>> {
    Ok(state.games.running())
}

#[tauri::command]
#[specta::specta]
pub async fn game_logs(state: State<'_, AppState>, id: String) -> CmdResult<Vec<LogLine>> {
    Ok(state.games.logs(&id))
}

#[tauri::command]
#[specta::specta]
pub async fn clear_game_logs(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    state.games.clear_logs(&id);
    Ok(())
}
