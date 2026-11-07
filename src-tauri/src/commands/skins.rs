//! Skin library and Minecraft services skin/cape changes.

use launcher_core::accounts::Account;
use launcher_core::auth::Skin;
use launcher_core::error::AuthError;
use launcher_core::net::client::check;
use launcher_core::skins::{self, SavedSkin, SkinVariant};
use tauri::State;

use super::CmdResult;
use crate::state::AppState;

/// Skins are small; anything bigger is rejected by `validate_skin` anyway.
const MAX_READ: u64 = 1024 * 1024;

async fn token(state: &AppState, account_id: &str) -> launcher_core::Result<String> {
    let client_id = state.settings.read().await.effective_client_id();
    let engine = state.engine.read().await;
    state
        .accounts
        .microsoft_token(
            account_id,
            &engine.http,
            &state.endpoints,
            client_id.as_deref(),
        )
        .await
}

async fn account(state: &AppState, id: &str) -> launcher_core::Result<Account> {
    state
        .accounts
        .get(id)
        .await
        .ok_or_else(|| AuthError::NoAccount.into())
}

#[tauri::command]
#[specta::specta]
pub async fn list_skins(state: State<'_, AppState>) -> CmdResult<Vec<SavedSkin>> {
    Ok(state.skins.list().await)
}

/// Add a PNG picked by the user to the library.
#[tauri::command]
#[specta::specta]
pub async fn add_skin(
    state: State<'_, AppState>,
    path: String,
    name: String,
    variant: SkinVariant,
) -> CmdResult<SavedSkin> {
    let meta = tokio::fs::metadata(&path)
        .await
        .map_err(|e| launcher_core::Error::io(&path, e))?;
    if meta.len() > MAX_READ {
        return Err(launcher_core::Error::FileTooLarge {
            max_kb: MAX_READ / 1024,
        }
        .into());
    }
    let png = tokio::fs::read(&path)
        .await
        .map_err(|e| launcher_core::Error::io(&path, e))?;
    Ok(state.skins.add(&name, variant, &png).await?)
}

/// Copy an account's current skin into the library.
#[tauri::command]
#[specta::specta]
pub async fn save_account_skin(
    state: State<'_, AppState>,
    account_id: String,
    name: String,
) -> CmdResult<SavedSkin> {
    let acc = account(&state, &account_id).await?;
    let url = acc
        .skin_url
        .as_deref()
        .and_then(skins::mojang_texture_url)
        .ok_or_else(|| launcher_core::Error::Invalid("this account has no custom skin".into()))?;
    let variant = match acc.skin_variant.as_deref() {
        Some("slim") => SkinVariant::Slim,
        _ => SkinVariant::Classic,
    };
    let png = {
        let engine = state.engine.read().await;
        let resp = check(engine.http.send(|c| c.get(&url)).await?).await?;
        resp.bytes().await.map_err(launcher_core::Error::from)?
    };
    Ok(state.skins.add(&name, variant, &png).await?)
}

#[tauri::command]
#[specta::specta]
pub async fn update_skin(
    state: State<'_, AppState>,
    id: String,
    name: String,
    variant: SkinVariant,
) -> CmdResult<SavedSkin> {
    Ok(state.skins.update(&id, &name, variant).await?)
}

#[tauri::command]
#[specta::specta]
pub async fn delete_skin(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    Ok(state.skins.delete(&id).await?)
}

/// Upload a library skin to the account (Microsoft accounts only).
#[tauri::command]
#[specta::specta]
pub async fn apply_skin(
    state: State<'_, AppState>,
    account_id: String,
    skin_id: String,
) -> CmdResult<Account> {
    let saved = state
        .skins
        .list()
        .await
        .into_iter()
        .find(|s| s.id == skin_id)
        .ok_or_else(|| launcher_core::Error::Invalid(format!("unknown skin {skin_id}")))?;
    let png = state.skins.read(&skin_id).await?;
    let token = token(&state, &account_id).await?;
    let profile = {
        let engine = state.engine.read().await;
        skins::upload_skin(&engine.http, &state.endpoints, &token, saved.variant, &png).await?
    };
    state.accounts.apply_profile(&account_id, &profile).await?;
    Ok(account(&state, &account_id).await?)
}

/// Back to the default skin.
#[tauri::command]
#[specta::specta]
pub async fn reset_skin(state: State<'_, AppState>, account_id: String) -> CmdResult<Account> {
    let token = token(&state, &account_id).await?;
    let profile = {
        let engine = state.engine.read().await;
        skins::reset_skin(&engine.http, &state.endpoints, &token).await?
    };
    state.accounts.apply_profile(&account_id, &profile).await?;
    Ok(account(&state, &account_id).await?)
}

/// Capes the account owns (live from Minecraft services).
#[tauri::command]
#[specta::specta]
pub async fn account_capes(state: State<'_, AppState>, account_id: String) -> CmdResult<Vec<Skin>> {
    let token = token(&state, &account_id).await?;
    let profile = {
        let engine = state.engine.read().await;
        skins::fetch_profile(&engine.http, &state.endpoints, &token).await?
    };
    state.accounts.apply_profile(&account_id, &profile).await?;
    Ok(profile.capes)
}

/// Show a cape (`None` hides it).
#[tauri::command]
#[specta::specta]
pub async fn set_cape(
    state: State<'_, AppState>,
    account_id: String,
    cape_id: Option<String>,
) -> CmdResult<Account> {
    let token = token(&state, &account_id).await?;
    let profile = {
        let engine = state.engine.read().await;
        skins::set_cape(&engine.http, &state.endpoints, &token, cape_id.as_deref()).await?
    };
    state.accounts.apply_profile(&account_id, &profile).await?;
    Ok(account(&state, &account_id).await?)
}
