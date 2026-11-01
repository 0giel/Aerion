//! The Aerion mod (`mod/` in the repository): a small Fabric mod that draws the game's menus in
//! the launcher's theme. The launcher keeps the jar and the current theme in `<root>/aerion-mod`
//! and loads the jar with `-Dfabric.addMods`, so it never appears in the player's mods folder.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::error::{IoContext, Result};
use crate::instances::{Instance, LoaderKind};
use crate::paths::Paths;

/// Game versions the bundled jar is built for.
pub const GAME_VERSIONS: &[&str] = &["26.3"];
pub const JAR_NAME: &str = "aerion-ui.jar";

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
}

pub fn dir(paths: &Paths) -> PathBuf {
    paths.root.join("aerion-mod")
}

pub fn supports(inst: &Instance) -> bool {
    inst.loader == LoaderKind::Fabric && GAME_VERSIONS.contains(&inst.game_version.as_str())
}

/// Write the bundled jar unless the same bytes are already there.
pub fn install_jar(paths: &Paths, bytes: &[u8]) -> Result<PathBuf> {
    let dir = dir(paths);
    std::fs::create_dir_all(&dir).at(&dir)?;
    let jar = dir.join(JAR_NAME);
    if std::fs::read(&jar).ok().as_deref() != Some(bytes) {
        let tmp = dir.join(format!("{JAR_NAME}.tmp"));
        std::fs::write(&tmp, bytes).at(&tmp)?;
        std::fs::rename(&tmp, &jar).at(&jar)?;
    }
    Ok(jar)
}

/// Save the theme where running games pick it up (they poll the file once a second).
pub async fn write_theme(paths: &Paths, theme: &GameTheme) -> Result<()> {
    crate::util::fs::write_json(dir(paths).join("theme.json"), theme).await
}

/// JVM arguments that load the mod, or nothing when the instance doesn't use it.
pub fn jvm_args(paths: &Paths, inst: &Instance) -> Vec<String> {
    let dir = dir(paths);
    let jar = dir.join(JAR_NAME);
    if !inst.aerion_mod || !supports(inst) || !jar.is_file() {
        return Vec::new();
    }
    vec![
        format!("-Dfabric.addMods={}", display(&jar)),
        format!("-Daerion.theme={}", display(&dir.join("theme.json"))),
    ]
}

fn display(p: &Path) -> String {
    p.to_string_lossy().into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loads_only_where_supported() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::at(dir.path().to_path_buf(), false);
        install_jar(&paths, b"jar").unwrap();
        let mut inst = Instance {
            loader: LoaderKind::Fabric,
            game_version: "26.3".into(),
            aerion_mod: true,
            ..Default::default()
        };
        let args = jvm_args(&paths, &inst);
        assert_eq!(args.len(), 2);
        assert!(args[0].starts_with("-Dfabric.addMods=") && args[0].ends_with(JAR_NAME));
        inst.game_version = "1.21.11".into();
        assert!(jvm_args(&paths, &inst).is_empty());
        inst.game_version = "26.3".into();
        inst.loader = LoaderKind::Neoforge;
        assert!(jvm_args(&paths, &inst).is_empty());
    }
}
