//! Screenshots in `<game>/screenshots`.

use std::path::{Path, PathBuf};

use serde::Serialize;

use crate::error::{Error, IoContext, Result};

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct Screenshot {
    pub name: String,
    pub size_bytes: u64,
    /// Unix seconds.
    pub taken: i64,
}

/// A screenshot file name from the UI: a plain `.png` name in the folder.
pub fn path(game_dir: &Path, name: &str) -> Result<PathBuf> {
    if name.contains(['/', '\\', '\0', ':']) || name.starts_with('.') || !name.ends_with(".png") {
        return Err(Error::UnsafePath(name.to_owned()));
    }
    Ok(game_dir.join("screenshots").join(name))
}

/// Newest first.
pub fn list(game_dir: &Path) -> Vec<Screenshot> {
    let mut out: Vec<Screenshot> = std::fs::read_dir(game_dir.join("screenshots"))
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_str()?.to_owned();
            path(game_dir, &name).ok()?;
            let meta = e.metadata().ok().filter(|m| m.is_file())?;
            let taken = meta
                .modified()
                .ok()?
                .duration_since(std::time::UNIX_EPOCH)
                .ok()?
                .as_secs() as i64;
            Some(Screenshot {
                name,
                size_bytes: meta.len(),
                taken,
            })
        })
        .collect();
    out.sort_by(|a, b| b.taken.cmp(&a.taken).then(b.name.cmp(&a.name)));
    out
}

/// A screenshot in the gallery of every instance: which instance it belongs to.
#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct GalleryShot {
    pub instance_id: String,
    pub instance_name: String,
    #[serde(flatten)]
    pub shot: Screenshot,
}

/// Every instance's screenshots together, newest first.
pub fn gallery(instances: &[(String, String, std::path::PathBuf)]) -> Vec<GalleryShot> {
    let mut out: Vec<GalleryShot> = instances
        .iter()
        .flat_map(|(id, name, dir)| {
            list(dir).into_iter().map(|shot| GalleryShot {
                instance_id: id.clone(),
                instance_name: name.clone(),
                shot,
            })
        })
        .collect();
    out.sort_by_key(|a| std::cmp::Reverse(a.shot.taken));
    out
}

pub fn delete(game_dir: &Path, name: &str) -> Result<()> {
    let p = path(game_dir, name)?;
    std::fs::remove_file(&p).at(&p)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lists_only_pngs_and_guards_names() {
        let dir = tempfile::tempdir().unwrap();
        let s = dir.path().join("screenshots");
        std::fs::create_dir_all(&s).unwrap();
        std::fs::write(s.join("2026-10-31_12.00.00.png"), b"png").unwrap();
        std::fs::write(s.join("notes.txt"), b"x").unwrap();
        assert_eq!(list(dir.path()).len(), 1);
        assert!(path(dir.path(), "../options.txt").is_err());
        assert!(path(dir.path(), "../x.png").is_err());
        delete(dir.path(), "2026-10-31_12.00.00.png").unwrap();
        assert!(list(dir.path()).is_empty());
    }
}
