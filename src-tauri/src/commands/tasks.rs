use tauri::State;

use super::CmdResult;
use crate::state::AppState;
use crate::tasks::TaskInfo;

#[tauri::command]
#[specta::specta]
pub async fn list_tasks(state: State<'_, AppState>) -> CmdResult<Vec<TaskInfo>> {
    Ok(state.tasks.list())
}

#[tauri::command]
#[specta::specta]
pub async fn cancel_task(state: State<'_, AppState>, id: String) -> CmdResult<bool> {
    Ok(state.tasks.cancel(&id))
}

#[tauri::command]
#[specta::specta]
pub async fn dismiss_task(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    state.tasks.dismiss(&id);
    Ok(())
}
