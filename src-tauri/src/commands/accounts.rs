use launcher_core::ErrorInfo;
use launcher_core::accounts::Account;
use launcher_core::auth::minecraft_session;
use launcher_core::auth::msa::{LoopbackLogin, device_code_poll, device_code_start};
use launcher_core::error::AuthError;
use serde::Serialize;
use specta::Type;
use tauri::{AppHandle, State};
use tauri_plugin_opener::OpenerExt;
use tokio_util::sync::CancellationToken;

use super::CmdResult;
use crate::state::AppState;

#[derive(Debug, Clone, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub struct AccountsView {
    pub accounts: Vec<Account>,
    pub selected: Option<String>,
    pub offline_allowed: bool,
    pub client_id_configured: bool,
}

#[tauri::command]
#[specta::specta]
pub async fn get_accounts(state: State<'_, AppState>) -> CmdResult<AccountsView> {
    let s = state.accounts.state().await;
    Ok(AccountsView {
        accounts: s.accounts,
        selected: s.selected,
        offline_allowed: state.accounts.offline_allowed().await,
        client_id_configured: state.settings.read().await.effective_client_id().is_some(),
    })
}

fn new_login(state: &AppState) -> CancellationToken {
    let token = CancellationToken::new();
    if let Some(old) = state.login.lock().unwrap_or_else(|e| e.into_inner()).replace(token.clone()) {
        old.cancel();
    }
    token
}

/// Sign in with the system browser (authorization code + PKCE on a loopback port).
#[tauri::command]
#[specta::specta]
pub async fn login_microsoft(app: AppHandle, state: State<'_, AppState>) -> CmdResult<Account> {
    let client_id = state.settings.read().await.effective_client_id().ok_or(AuthError::ClientIdMissing).map_err(launcher_core::Error::from)?;
    let cancel = new_login(&state);
    let engine = state.engine().await;
    let login = LoopbackLogin::start(&state.endpoints, &client_id).await?;
    app.opener().open_url(login.auth_url.clone(), None::<&str>).map_err(|e| ErrorInfo::msg("generic", e.to_string()))?;
    let tokens = login.finish(&engine.http, &state.endpoints, &client_id, &cancel).await?;
    let session = minecraft_session(&engine.http, &state.endpoints, &tokens).await?;
    Ok(state.accounts.add_microsoft(&session).await?)
}

#[tauri::command]
#[specta::specta]
pub async fn cancel_login(state: State<'_, AppState>) -> CmdResult<()> {
    if let Some(t) = state.login.lock().unwrap_or_else(|e| e.into_inner()).take() {
        t.cancel();
    }
    Ok(())
}

#[derive(Debug, Clone, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub struct DeviceCodeView {
    pub user_code: String,
    pub verification_uri: String,
    pub expires_in: f64,
}

/// Fallback for when the browser redirect cannot reach the launcher.
#[tauri::command]
#[specta::specta]
pub async fn device_code_begin(state: State<'_, AppState>) -> CmdResult<DeviceCodeView> {
    let client_id = state.settings.read().await.effective_client_id().ok_or(AuthError::ClientIdMissing).map_err(launcher_core::Error::from)?;
    let engine = state.engine().await;
    let dc = device_code_start(&engine.http, &state.endpoints, &client_id).await?;
    let view = DeviceCodeView { user_code: dc.user_code.clone(), verification_uri: dc.verification_uri.clone(), expires_in: dc.expires_in as f64 };
    *state.device_code.lock().await = Some(dc);
    Ok(view)
}

#[tauri::command]
#[specta::specta]
pub async fn device_code_complete(state: State<'_, AppState>) -> CmdResult<Account> {
    let client_id = state.settings.read().await.effective_client_id().ok_or(AuthError::ClientIdMissing).map_err(launcher_core::Error::from)?;
    let dc = state.device_code.lock().await.take().ok_or_else(|| ErrorInfo::msg("auth.expired", "no pending device code"))?;
    let cancel = new_login(&state);
    let engine = state.engine().await;
    let tokens = device_code_poll(&engine.http, &state.endpoints, &client_id, &dc, &cancel).await?;
    let session = minecraft_session(&engine.http, &state.endpoints, &tokens).await?;
    Ok(state.accounts.add_microsoft(&session).await?)
}

#[tauri::command]
#[specta::specta]
pub async fn add_offline_account(state: State<'_, AppState>, name: String) -> CmdResult<Account> {
    Ok(state.accounts.add_offline(name.trim()).await?)
}

#[tauri::command]
#[specta::specta]
pub async fn remove_account(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    Ok(state.accounts.remove(&id).await?)
}

#[tauri::command]
#[specta::specta]
pub async fn select_account(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    Ok(state.accounts.select(&id).await?)
}
