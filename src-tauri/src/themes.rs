//! User themes on disk (`<root>/themes`): `<id>.json`, optional `<id>.css`, `assets/`.
//! A watcher emits `ThemesChangedEvent` so edits made in any editor apply live.

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use notify::{RecursiveMode, Watcher};
use serde::Serialize;
use specta::Type;
use tauri::AppHandle;
use tauri_specta::Event;

#[derive(Debug, Clone, Serialize, Type, Event)]
pub struct ThemesChangedEvent;

pub fn valid_id(id: &str) -> bool {
    let mut chars = id.chars();
    matches!(chars.next(), Some(c) if c.is_ascii_lowercase() || c.is_ascii_digit())
        && id.len() <= 48
        && id.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-' || c == '_')
}

pub fn valid_asset(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 128
        && !name.starts_with('.')
        && name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.'))
}

pub fn assets_dir(themes: &Path) -> PathBuf {
    themes.join("assets")
}

pub fn content_type(name: &str) -> &'static str {
    let ext = name.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
    match ext.as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "gif" => "image/gif",
        "avif" => "image/avif",
        "svg" => "image/svg+xml",
        "mp4" | "m4v" => "video/mp4",
        "webm" => "video/webm",
        "ogv" => "video/ogg",
        "woff2" => "font/woff2",
        "ttf" => "font/ttf",
        _ => "application/octet-stream",
    }
}

/// Watch the themes folder and notify the UI (debounced).
pub fn watch(app: AppHandle, dir: PathBuf) {
    std::thread::Builder::new()
        .name("theme-watcher".into())
        .spawn(move || {
            let (tx, rx) = std::sync::mpsc::channel();
            let mut watcher = match notify::recommended_watcher(tx) {
                Ok(w) => w,
                Err(e) => return tracing::warn!(error = %e, "theme watcher unavailable"),
            };
            if let Err(e) = watcher.watch(&dir, RecursiveMode::Recursive) {
                return tracing::warn!(error = %e, "cannot watch themes folder");
            }
            while rx.recv().is_ok() {
                let until = Instant::now() + Duration::from_millis(200);
                while let Some(left) = until.checked_duration_since(Instant::now()) {
                    if rx.recv_timeout(left).is_err() {
                        break;
                    }
                }
                let _ = ThemesChangedEvent.emit(&app);
            }
        })
        .ok();
}
