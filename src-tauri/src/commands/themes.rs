use std::path::PathBuf;

use base64::Engine as _;
use launcher_core::ErrorInfo;
use serde::{Deserialize, Serialize};
use specta::Type;
use tauri::State;

use super::CmdResult;
use crate::state::AppState;
use crate::themes::{assets_dir, content_type, valid_asset, valid_id};

#[derive(Debug, Clone, Serialize, Deserialize, Type)]
#[serde(rename_all = "camelCase")]
pub struct ThemeFile {
    pub id: String,
    /// Raw theme JSON (validated by the UI against the token schema).
    pub json: String,
    pub css: Option<String>,
}

fn bad(msg: &str) -> ErrorInfo {
    ErrorInfo::msg("theme.invalid", msg)
}

fn io(path: &std::path::Path, e: std::io::Error) -> ErrorInfo {
    launcher_core::Error::io(path, e).into()
}

#[tauri::command]
#[specta::specta]
pub async fn list_user_themes(state: State<'_, AppState>) -> CmdResult<Vec<ThemeFile>> {
    let dir = state.paths.themes_dir();
    let mut out = Vec::new();
    let Ok(mut rd) = tokio::fs::read_dir(&dir).await else {
        return Ok(out);
    };
    while let Ok(Some(e)) = rd.next_entry().await {
        let p = e.path();
        let Some(id) = p.file_stem().and_then(|s| s.to_str()).map(str::to_owned) else {
            continue;
        };
        if p.extension().is_none_or(|x| x != "json") || !valid_id(&id) {
            continue;
        }
        let Ok(json) = tokio::fs::read_to_string(&p).await else {
            continue;
        };
        let css = tokio::fs::read_to_string(dir.join(format!("{id}.css")))
            .await
            .ok();
        out.push(ThemeFile { id, json, css });
    }
    out.sort_by(|a, b| a.id.cmp(&b.id));
    Ok(out)
}

#[tauri::command]
#[specta::specta]
pub async fn save_user_theme(state: State<'_, AppState>, theme: ThemeFile) -> CmdResult<()> {
    if !valid_id(&theme.id) {
        return Err(bad("theme id may contain a-z, 0-9, - and _"));
    }
    if theme.json.len() > 512 * 1024 || theme.css.as_ref().is_some_and(|c| c.len() > 512 * 1024) {
        return Err(bad("theme is too large"));
    }
    serde_json::from_str::<serde_json::Map<String, serde_json::Value>>(&theme.json)
        .map_err(|e| bad(&e.to_string()))?;
    let dir = state.paths.themes_dir();
    launcher_core::util::fs::atomic_write(
        dir.join(format!("{}.json", theme.id)),
        theme.json.as_bytes(),
    )
    .await?;
    let css_path = dir.join(format!("{}.css", theme.id));
    match theme.css.filter(|c| !c.trim().is_empty()) {
        Some(css) => launcher_core::util::fs::atomic_write(&css_path, css.as_bytes()).await?,
        None => {
            let _ = tokio::fs::remove_file(&css_path).await;
        }
    }
    Ok(())
}

#[tauri::command]
#[specta::specta]
pub async fn delete_user_theme(state: State<'_, AppState>, id: String) -> CmdResult<()> {
    if !valid_id(&id) {
        return Err(bad("invalid id"));
    }
    let dir = state.paths.themes_dir();
    for ext in ["json", "css"] {
        let _ = tokio::fs::remove_file(dir.join(format!("{id}.{ext}"))).await;
    }
    Ok(())
}

/// Copy an image/video into the themes asset folder; returns the asset name.
#[tauri::command]
#[specta::specta]
pub async fn add_theme_asset(state: State<'_, AppState>, path: String) -> CmdResult<String> {
    let src = PathBuf::from(&path);
    let meta = tokio::fs::metadata(&src).await.map_err(|e| io(&src, e))?;
    if meta.len() > 200 * 1024 * 1024 {
        return Err(bad("file is larger than 200 MB"));
    }
    let ext = src
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    if content_type(&format!("x.{ext}")) == "application/octet-stream" {
        return Err(bad("unsupported file type"));
    }
    let name = format!(
        "bg-{}.{ext}",
        &uuid::Uuid::new_v4().simple().to_string()[..12]
    );
    let dir = assets_dir(&state.paths.themes_dir());
    tokio::fs::create_dir_all(&dir)
        .await
        .map_err(|e| io(&dir, e))?;
    tokio::fs::copy(&src, dir.join(&name))
        .await
        .map_err(|e| io(&src, e))?;
    Ok(name)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Bundle {
    #[serde(flatten)]
    theme: serde_json::Map<String, serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    custom_css: Option<String>,
    /// `asset name → data URI`
    #[serde(default, skip_serializing_if = "std::collections::BTreeMap::is_empty")]
    embedded_assets: std::collections::BTreeMap<String, String>,
}

/// Export one self-contained file: theme JSON + custom CSS + background asset (data URI).
#[tauri::command]
#[specta::specta]
pub async fn export_theme(
    state: State<'_, AppState>,
    theme: ThemeFile,
    path: String,
) -> CmdResult<()> {
    let map: serde_json::Map<String, serde_json::Value> =
        serde_json::from_str(&theme.json).map_err(|e| bad(&e.to_string()))?;
    let mut embedded = std::collections::BTreeMap::new();
    if let Some(asset) = map
        .get("background")
        .and_then(|b| b.get("asset"))
        .and_then(|a| a.as_str())
        && valid_asset(asset)
    {
        let p = assets_dir(&state.paths.themes_dir()).join(asset);
        if let Ok(bytes) = tokio::fs::read(&p).await {
            let b64 = base64::engine::general_purpose::STANDARD.encode(bytes);
            embedded.insert(
                asset.to_owned(),
                format!("data:{};base64,{b64}", content_type(asset)),
            );
        }
    }
    let bundle = Bundle {
        theme: map,
        custom_css: theme.css.filter(|c| !c.trim().is_empty()),
        embedded_assets: embedded,
    };
    let bytes = serde_json::to_vec_pretty(&bundle).map_err(|e| bad(&e.to_string()))?;
    let dest = PathBuf::from(&path);
    tokio::fs::write(&dest, bytes)
        .await
        .map_err(|e| io(&dest, e))
}

/// Import a theme file (plain theme JSON or an exported bundle). Returns the stored theme.
#[tauri::command]
#[specta::specta]
pub async fn import_theme(state: State<'_, AppState>, path: String) -> CmdResult<ThemeFile> {
    let src = PathBuf::from(&path);
    let meta = tokio::fs::metadata(&src).await.map_err(|e| io(&src, e))?;
    if meta.len() > 300 * 1024 * 1024 {
        return Err(bad("file too large"));
    }
    let bytes = tokio::fs::read(&src).await.map_err(|e| io(&src, e))?;
    let mut bundle: Bundle = serde_json::from_slice(&bytes).map_err(|e| bad(&e.to_string()))?;
    let themes = state.paths.themes_dir();
    let assets = assets_dir(&themes);
    for (name, uri) in &bundle.embedded_assets {
        if !valid_asset(name) {
            continue;
        }
        let Some((_, data)) = uri.split_once(";base64,") else {
            continue;
        };
        let Ok(decoded) = base64::engine::general_purpose::STANDARD.decode(data) else {
            continue;
        };
        tokio::fs::create_dir_all(&assets)
            .await
            .map_err(|e| io(&assets, e))?;
        tokio::fs::write(assets.join(name), decoded)
            .await
            .map_err(|e| io(&assets, e))?;
    }
    let base: String = bundle
        .theme
        .get("name")
        .and_then(|n| n.as_str())
        .unwrap_or("theme")
        .to_lowercase()
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect::<String>()
        .trim_matches('-')
        .chars()
        .take(40)
        .collect();
    let base = if base.is_empty() || !valid_id(&base) {
        "theme".to_owned()
    } else {
        base
    };
    let mut id = base.clone();
    let mut n = 2;
    while themes.join(format!("{id}.json")).exists() {
        id = format!("{base}-{n}");
        n += 1;
    }
    bundle
        .theme
        .insert("id".into(), serde_json::Value::String(id.clone()));
    let json = serde_json::to_string_pretty(&bundle.theme).map_err(|e| bad(&e.to_string()))?;
    let file = ThemeFile {
        id,
        json,
        css: bundle.custom_css.take(),
    };
    save_user_theme(state, file.clone()).await?;
    Ok(file)
}
