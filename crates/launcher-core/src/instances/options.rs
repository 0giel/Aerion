//! In-game settings (controls, video, sound) shared between instances: copy them from one
//! instance to others, or keep a template that new instances start with.

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::{Error, IoContext, Result};

/// Vanilla options, OptiFine's and the shader settings.
pub const FILES: [&str; 3] = ["options.txt", "optionsof.txt", "optionsshaders.txt"];
const META: &str = "template.json";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct OptionsTemplate {
    /// Name of the instance it was saved from.
    pub source: String,
    /// Unix seconds.
    pub saved_at: i64,
}

/// Copy whichever option files exist. Returns how many were copied.
pub fn copy(from_game: &Path, to_game: &Path) -> Result<usize> {
    if from_game == to_game {
        return Ok(0);
    }
    std::fs::create_dir_all(to_game).at(to_game)?;
    let mut n = 0;
    for f in FILES {
        let src = from_game.join(f);
        if src.is_file() {
            let dst = to_game.join(f);
            std::fs::copy(&src, &dst).at(&dst)?;
            n += 1;
        }
    }
    Ok(n)
}

pub fn save_template(
    from_game: &Path,
    template_dir: &Path,
    source: &str,
) -> Result<OptionsTemplate> {
    if !from_game.join("options.txt").is_file() {
        return Err(Error::Invalid(
            "this instance has no game settings yet — start it once first".into(),
        ));
    }
    if template_dir.exists() {
        std::fs::remove_dir_all(template_dir).at(template_dir)?;
    }
    copy(from_game, template_dir)?;
    let meta = OptionsTemplate {
        source: source.to_owned(),
        saved_at: crate::util::now_secs(),
    };
    let path = template_dir.join(META);
    std::fs::write(&path, serde_json::to_vec(&meta).unwrap_or_default()).at(&path)?;
    Ok(meta)
}

pub fn template(template_dir: &Path) -> Option<OptionsTemplate> {
    let bytes = std::fs::read(template_dir.join(META)).ok()?;
    serde_json::from_slice(&bytes).ok()
}

pub fn clear_template(template_dir: &Path) -> Result<()> {
    match std::fs::remove_dir_all(template_dir) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(Error::io(template_dir, e)),
        _ => Ok(()),
    }
}

/// Give a new instance the template's settings (never overwrites existing ones).
pub fn apply_template(template_dir: &Path, to_game: &Path) -> Result<bool> {
    if template(template_dir).is_none() || to_game.join("options.txt").exists() {
        return Ok(false);
    }
    Ok(copy(template_dir, to_game)? > 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn copy_template_apply() {
        let dir = tempfile::tempdir().unwrap();
        let (a, b, tpl) = (
            dir.path().join("a"),
            dir.path().join("b"),
            dir.path().join("tpl"),
        );
        std::fs::create_dir_all(&a).unwrap();
        assert!(save_template(&a, &tpl, "A").is_err(), "nothing to save yet");
        std::fs::write(
            a.join("options.txt"),
            "fov:0.5\nkey_key.jump:key.keyboard.space\n",
        )
        .unwrap();
        std::fs::write(a.join("optionsof.txt"), "ofFastRender:true\n").unwrap();
        assert_eq!(copy(&a, &b).unwrap(), 2);
        assert_eq!(
            std::fs::read_to_string(b.join("options.txt"))
                .unwrap()
                .lines()
                .next(),
            Some("fov:0.5")
        );

        let meta = save_template(&a, &tpl, "A").unwrap();
        assert_eq!(template(&tpl).unwrap().source, meta.source);
        let c = dir.path().join("c");
        assert!(apply_template(&tpl, &c).unwrap());
        assert!(c.join("optionsof.txt").is_file());
        assert!(
            !apply_template(&tpl, &c).unwrap(),
            "existing settings are kept"
        );
        clear_template(&tpl).unwrap();
        assert!(template(&tpl).is_none());
        clear_template(&tpl).unwrap();
    }
}
