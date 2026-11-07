//! Small filesystem helpers: atomic writes, JSON files, safe path joins.

use std::path::{Component, Path, PathBuf};

use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::error::{Error, IoContext, Result};

async fn ensure_dir(path: impl AsRef<Path>) -> Result<()> {
    let path = path.as_ref();
    tokio::fs::create_dir_all(path).await.at(path)
}

/// Write via a temporary sibling file and rename, so readers never see half a file.
pub async fn atomic_write(path: impl AsRef<Path>, bytes: impl AsRef<[u8]>) -> Result<()> {
    let path = path.as_ref();
    if let Some(parent) = path.parent() {
        ensure_dir(parent).await?;
    }
    let tmp = tmp_sibling(path);
    tokio::fs::write(&tmp, bytes).await.at(&tmp)?;
    if let Err(e) = tokio::fs::rename(&tmp, path).await {
        let _ = tokio::fs::remove_file(&tmp).await;
        return Err(Error::io(path, e));
    }
    Ok(())
}

pub fn atomic_write_sync(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).at(parent)?;
    }
    let tmp = tmp_sibling(path);
    std::fs::write(&tmp, bytes).at(&tmp)?;
    std::fs::rename(&tmp, path).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        Error::io(path, e)
    })
}

fn tmp_sibling(path: &Path) -> PathBuf {
    let mut name = path
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_default();
    name.push(format!(".tmp-{}", uuid::Uuid::new_v4().simple()));
    path.with_file_name(name)
}

/// Read a JSON file; `Ok(None)` when it does not exist.
pub async fn read_json<T: DeserializeOwned>(path: impl AsRef<Path>) -> Result<Option<T>> {
    let path = path.as_ref();
    match tokio::fs::read(path).await {
        Ok(bytes) => serde_json::from_slice(&bytes)
            .map(Some)
            .map_err(|e| Error::parse(path.display().to_string(), e)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(Error::io(path, e)),
    }
}

pub async fn write_json<T: Serialize>(path: impl AsRef<Path>, value: &T) -> Result<()> {
    let bytes = serde_json::to_vec_pretty(value).map_err(|e| Error::parse("serialize", e))?;
    atomic_write(path, bytes).await
}

/// Join an untrusted relative path (from an archive, a modpack index, …) onto `base`.
///
/// Rejects absolute paths, drive prefixes, `..`, and empty paths, which is the zip-slip guard
/// used everywhere archives or remote file lists are written to disk.
/// A file name from an API or the UI: one plain segment, never hidden or a parent reference.
pub fn safe_file_name(name: &str) -> Result<()> {
    if name.is_empty()
        || name.contains(['/', '\\', '\0'])
        || name.starts_with('.')
        || name.contains("..")
    {
        return Err(Error::UnsafePath(name.to_owned()));
    }
    Ok(())
}

pub fn safe_join(base: &Path, relative: &str) -> Result<PathBuf> {
    let normalized = relative.replace('\\', "/");
    if normalized.is_empty() || normalized.starts_with('/') || normalized.contains('\0') {
        return Err(Error::UnsafePath(relative.to_owned()));
    }
    let mut out = base.to_path_buf();
    let mut depth = 0usize;
    for comp in Path::new(&normalized).components() {
        match comp {
            Component::Normal(part) => {
                let s = part.to_string_lossy();
                // `C:` style prefixes and alternate data streams on Windows.
                if s.contains(':') {
                    return Err(Error::UnsafePath(relative.to_owned()));
                }
                out.push(part);
                depth += 1;
            }
            Component::CurDir => {}
            _ => return Err(Error::UnsafePath(relative.to_owned())),
        }
    }
    if depth == 0 {
        return Err(Error::UnsafePath(relative.to_owned()));
    }
    Ok(out)
}

/// Hard-link `src` to `dst`, falling back to a copy (different filesystems, FAT, …).
pub async fn link_or_copy(src: &Path, dst: &Path) -> Result<()> {
    if let Some(parent) = dst.parent() {
        ensure_dir(parent).await?;
    }
    let _ = tokio::fs::remove_file(dst).await;
    if tokio::fs::hard_link(src, dst).await.is_ok() {
        return Ok(());
    }
    tokio::fs::copy(src, dst).await.at(dst)?;
    Ok(())
}

/// Recursively copy a directory (used by instance cloning and overrides).
pub fn copy_dir_sync(src: &Path, dst: &Path, skip: &dyn Fn(&Path) -> bool) -> Result<()> {
    std::fs::create_dir_all(dst).at(dst)?;
    for entry in std::fs::read_dir(src).at(src)? {
        let entry = entry.at(src)?;
        let from = entry.path();
        if skip(&from) {
            continue;
        }
        let to = dst.join(entry.file_name());
        let ft = entry.file_type().at(&from)?;
        if ft.is_dir() {
            copy_dir_sync(&from, &to, skip)?;
        } else if ft.is_file() {
            std::fs::copy(&from, &to).at(&to)?;
        }
    }
    Ok(())
}

/// Entries of `dir` that pass `f` (0 when it can't be read).
pub fn count_entries(dir: &Path, f: impl Fn(&std::fs::DirEntry) -> bool) -> u32 {
    std::fs::read_dir(dir)
        .map(|rd| rd.flatten().filter(|e| f(e)).count() as u32)
        .unwrap_or(0)
}

pub fn dir_size_sync(path: &Path) -> u64 {
    let Ok(rd) = std::fs::read_dir(path) else {
        return 0;
    };
    rd.flatten()
        .map(|e| match e.file_type() {
            Ok(ft) if ft.is_dir() => dir_size_sync(&e.path()),
            Ok(_) => e.metadata().map(|m| m.len()).unwrap_or(0),
            Err(_) => 0,
        })
        .sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safe_join_accepts_normal_paths() {
        let base = Path::new("/base");
        assert_eq!(
            safe_join(base, "mods/a.jar").unwrap(),
            Path::new("/base/mods/a.jar")
        );
        assert_eq!(
            safe_join(base, "./config/x.toml").unwrap(),
            Path::new("/base/config/x.toml")
        );
        assert_eq!(
            safe_join(base, "dir\\file.txt").unwrap(),
            Path::new("/base/dir/file.txt")
        );
        assert_eq!(
            safe_join(base, "saves/Świat Łukasza/level.dat").unwrap(),
            Path::new("/base/saves/Świat Łukasza/level.dat")
        );
    }

    #[test]
    fn safe_join_rejects_escapes() {
        let base = Path::new("/base");
        for bad in [
            "../evil",
            "mods/../../evil",
            "/etc/passwd",
            "\\windows\\x",
            "C:/x",
            "a/C:b",
            "",
            ".",
            "a\0b",
        ] {
            assert!(safe_join(base, bad).is_err(), "{bad:?} must be rejected");
        }
    }

    #[tokio::test]
    async fn atomic_write_and_read_json() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("nested").join("ząb.json");
        write_json(&p, &serde_json::json!({"a": 1})).await.unwrap();
        let v: Option<serde_json::Value> = read_json(&p).await.unwrap();
        assert_eq!(v.unwrap()["a"], 1);
        let missing: Option<serde_json::Value> =
            read_json(dir.path().join("nope.json")).await.unwrap();
        assert!(missing.is_none());
    }
}
