//! The Aerion mod (`mod/` in the repository): a small Fabric mod that draws the game's menus in
//! the launcher's theme. The launcher keeps the jar and the current theme in `<root>/aerion-mod`
//! and loads the jar with `-Dfabric.addMods`, so it never appears in the player's mods folder.

pub mod hud;

use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::error::{IoContext, Result};
use crate::instances::Instance;
use crate::loaders::LoaderKind;
use crate::paths::{Paths, display_path};

/// Game versions the bundled jars are built for (one jar each).
pub const GAME_VERSIONS: &[&str] = &["26.3", "1.21.11"];

fn jar_name(game_version: &str) -> String {
    format!("aerion-ui-{game_version}.jar")
}

/// Colours the mod uses, resolved by the launcher from the active theme (the mod parses no CSS).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct GameTheme {
    pub name: String,
    /// `dark` or `light`.
    pub base: String,
    pub bg: String,
    pub surface: String,
    pub surface2: String,
    pub border: String,
    pub text: String,
    pub text_muted: String,
    pub accent: String,
    pub accent_text: String,
    /// Background gradient stops (`#rrggbb`), top to bottom; empty = `bg`.
    pub gradient: Vec<String>,
    /// Corner radius of medium controls in CSS pixels.
    pub radius: u32,
    /// The player's choices ([`crate::settings::GameUiPrefs`]); particles carry the theme's kind
    /// (`sparkles`, `dust`, `snow`) or `none`.
    pub colors: bool,
    pub particles: String,
    pub logo: bool,
    /// The theme's background picture for the menus: a theme asset's file name (PNG, JPEG or
    /// GIF, animated or not), or none for the gradient.
    #[serde(default)]
    pub background_asset: Option<String>,
    /// How much the picture is darkened behind the menus, 0–1.
    #[serde(default)]
    pub background_dim: f32,
    /// Absolute path of that picture for the mod; set by [`write_theme`], ignored from callers.
    #[serde(default)]
    pub background_file: Option<String>,
}

/// Picture formats the mod can read (Java's own decoders); videos and WebP keep the gradient.
const PICTURES: &[&str] = &["png", "jpg", "jpeg", "gif"];

/// The picture file for the mod: an existing theme asset of a supported format, nothing else.
fn background_file(paths: &Paths, asset: Option<&str>) -> Option<String> {
    let name = asset?;
    let ext = name.rsplit_once('.')?.1.to_ascii_lowercase();
    if !PICTURES.contains(&ext.as_str()) {
        return None;
    }
    let path = crate::util::fs::safe_join(&paths.theme_assets_dir(), name).ok()?;
    path.is_file().then(|| display_path(&path))
}

fn theme_file(paths: &Paths) -> PathBuf {
    paths.aerion_mod_dir().join("theme.json")
}

pub fn supports(inst: &Instance) -> bool {
    inst.loader == LoaderKind::Fabric && GAME_VERSIONS.contains(&inst.game_version.as_str())
}

/// Write a bundled jar unless the same bytes are already there.
pub fn install_jar(paths: &Paths, game_version: &str, bytes: &[u8]) -> Result<PathBuf> {
    let dir = paths.aerion_mod_dir();
    std::fs::create_dir_all(&dir).at(&dir)?;
    let name = jar_name(game_version);
    let jar = dir.join(&name);
    if std::fs::read(&jar).ok().as_deref() != Some(bytes) {
        let tmp = dir.join(format!("{name}.tmp"));
        std::fs::write(&tmp, bytes).at(&tmp)?;
        std::fs::rename(&tmp, &jar).at(&jar)?;
    }
    // The single jar of 0.1.x–0.2.x is no longer used.
    let _ = std::fs::remove_file(dir.join("aerion-ui.jar"));
    Ok(jar)
}

/// Save the theme where running games pick it up (they poll the file once a second).
pub async fn write_theme(paths: &Paths, theme: &GameTheme) -> Result<()> {
    let mut theme = theme.clone();
    theme.background_file = background_file(paths, theme.background_asset.as_deref());
    theme.background_dim = theme.background_dim.clamp(0.0, 1.0);
    crate::util::fs::write_json(theme_file(paths), &theme).await
}

/// JVM arguments that load the mod, or nothing when the instance doesn't use it.
pub fn jvm_args(paths: &Paths, inst: &Instance) -> Vec<String> {
    let jar = paths.aerion_mod_dir().join(jar_name(&inst.game_version));
    if !inst.aerion_mod || !supports(inst) || !jar.is_file() {
        return Vec::new();
    }
    vec![
        format!("-Dfabric.addMods={}", display_path(&jar)),
        format!("-Daerion.theme={}", display_path(&theme_file(paths))),
        format!("-Daerion.hud={}", display_path(&hud::file(paths))),
        format!(
            "-Daerion.status={}",
            display_path(&status_file(paths, &inst.id))
        ),
    ]
}

/// Where the mod reports the game's FPS for an instance (ids may hold any characters).
fn status_file(paths: &Paths, instance_id: &str) -> PathBuf {
    let safe: String = instance_id
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .take(40)
        .collect();
    // FNV-1a keeps ids that differ only in replaced characters apart.
    let hash = instance_id.bytes().fold(0x811c_9dc5_u32, |h, b| {
        (h ^ u32::from(b)).wrapping_mul(0x0100_0193)
    });
    paths
        .aerion_mod_dir()
        .join("status")
        .join(format!("{safe}-{hash:08x}.json"))
}

/// What the mod reports about the running game about once a second.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub fps: u32,
    /// Used Java heap (mods from 1.0.0 on).
    pub heap_mb: Option<u32>,
    pub heap_max_mb: Option<u32>,
    /// A world is open (not the menus).
    pub in_world: Option<bool>,
    /// Milliseconds since the epoch when the mod wrote the file.
    time: i64,
}

/// The mod's report from the last few seconds, if any.
pub fn read_status(paths: &Paths, instance_id: &str) -> Option<Status> {
    let s: Status =
        serde_json::from_slice(&std::fs::read(status_file(paths, instance_id)).ok()?).ok()?;
    let age = crate::util::now_millis().saturating_sub(s.time);
    (age < 3_000).then_some(s)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn background_only_for_existing_pictures() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::at(dir.path().to_path_buf(), false);
        let assets = paths.themes_dir().join("assets");
        std::fs::create_dir_all(&assets).unwrap();
        for f in ["sky.gif", "sky.PNG", "clip.mp4", "pic.webp"] {
            std::fs::write(assets.join(f), b"x").unwrap();
        }
        assert!(
            background_file(&paths, Some("sky.gif"))
                .unwrap()
                .ends_with("sky.gif")
        );
        assert!(background_file(&paths, Some("sky.PNG")).is_some());
        assert_eq!(background_file(&paths, Some("clip.mp4")), None);
        assert_eq!(background_file(&paths, Some("pic.webp")), None);
        assert_eq!(background_file(&paths, Some("missing.gif")), None);
        assert_eq!(background_file(&paths, Some("../../etc/x.gif")), None);
        assert_eq!(background_file(&paths, None), None);
    }

    #[test]
    fn loads_only_where_supported() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::at(dir.path().to_path_buf(), false);
        install_jar(&paths, "26.3", b"jar").unwrap();
        let mut inst = Instance {
            loader: LoaderKind::Fabric,
            game_version: "26.3".into(),
            aerion_mod: true,
            ..Default::default()
        };
        let args = jvm_args(&paths, &inst);
        assert_eq!(args.len(), 4);
        assert!(
            args[0].starts_with("-Dfabric.addMods=") && args[0].ends_with("aerion-ui-26.3.jar")
        );
        // Supported, but its jar isn't installed in this test.
        inst.game_version = "1.21.11".into();
        assert!(jvm_args(&paths, &inst).is_empty());
        inst.game_version = "1.20.1".into();
        assert!(jvm_args(&paths, &inst).is_empty());
        inst.game_version = "26.3".into();
        inst.loader = LoaderKind::Neoforge;
        assert!(jvm_args(&paths, &inst).is_empty());
    }

    #[test]
    fn reads_fresh_status_only() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::at(dir.path().to_path_buf(), false);
        let f = status_file(&paths, "Mój świat");
        assert!(
            f.file_name()
                .unwrap()
                .to_string_lossy()
                .starts_with("M_j__wiat-")
        );
        std::fs::create_dir_all(f.parent().unwrap()).unwrap();
        let now = crate::util::now_millis();
        std::fs::write(&f, format!(r#"{{"fps":144,"time":{now}}}"#)).unwrap();
        assert_eq!(read_status(&paths, "Mój świat").map(|s| s.fps), Some(144));
        std::fs::write(&f, format!(r#"{{"fps":144,"time":{}}}"#, now - 10_000)).unwrap();
        assert_eq!(read_status(&paths, "Mój świat").map(|s| s.fps), None);
    }
}
