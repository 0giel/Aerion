//! On-disk layout.
//!
//! `root` holds the small, personal files (settings, accounts, themes, logs).
//! `data` holds the big shared caches and instances; it defaults to `root` and
//! can be moved in settings. A `portable` file next to the executable switches
//! both to `<exe dir>/data`.

use std::path::{Path, PathBuf};

use crate::error::{Error, Result};

pub const PORTABLE_MARKER: &str = "portable";

#[derive(Debug, Clone)]
pub struct Paths {
    pub root: PathBuf,
    pub data: PathBuf,
    pub portable: bool,
}

/// The launcher used to be called "Luncher": move its data folder to the new name once.
/// Nothing happens when the new folder already exists or the move fails (e.g. another drive).
fn migrate_old_root(old: &Path, new: &Path) {
    if old.is_dir() && !new.exists() {
        let _ = std::fs::rename(old, new);
    }
}

impl Paths {
    /// Resolve the root directory: `AERION_HOME` (or the old `LUNCHER_HOME`) env var, portable
    /// mode, or the OS default.
    pub fn detect() -> Result<Self> {
        if let Some(home) = ["AERION_HOME", "LUNCHER_HOME"]
            .into_iter()
            .find_map(|v| std::env::var_os(v).filter(|v| !v.is_empty()))
        {
            return Ok(Self::at(PathBuf::from(home), false));
        }
        if let Some(exe_dir) = std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(Path::to_path_buf))
            && exe_dir.join(PORTABLE_MARKER).is_file()
        {
            return Ok(Self::at(exe_dir.join("data"), true));
        }
        let base = directories::BaseDirs::new()
            .ok_or_else(|| Error::Invalid("cannot determine the user's home directory".into()))?;
        let (dir, name, old) = if cfg!(windows) {
            (base.data_local_dir(), "Aerion", "Luncher")
        } else if cfg!(target_os = "macos") {
            (base.data_dir(), "Aerion", "Luncher")
        } else {
            (base.data_dir(), "aerion", "luncher")
        };
        let root = dir.join(name);
        migrate_old_root(&dir.join(old), &root);
        Ok(Self::at(root, false))
    }

    pub fn at(root: PathBuf, portable: bool) -> Self {
        Self {
            data: root.clone(),
            root,
            portable,
        }
    }

    /// Point the large data directory somewhere else (settings → data directory).
    pub fn with_data_dir(mut self, data: Option<PathBuf>) -> Self {
        if let Some(d) = data.filter(|d| d.is_absolute()) {
            self.data = d;
        }
        self
    }

    pub fn settings_file(&self) -> PathBuf {
        self.root.join("settings.json")
    }
    pub fn accounts_file(&self) -> PathBuf {
        self.root.join("accounts.json")
    }
    pub fn skins_dir(&self) -> PathBuf {
        self.root.join("skins")
    }
    pub fn themes_dir(&self) -> PathBuf {
        self.root.join("themes")
    }
    pub fn logs_dir(&self) -> PathBuf {
        self.root.join("logs")
    }
    pub fn http_cache_dir(&self) -> PathBuf {
        self.data.join("cache").join("http")
    }
    pub fn image_cache_dir(&self) -> PathBuf {
        self.data.join("cache").join("images")
    }
    /// Private fontconfig cache, used only when the user's cache is unusable (Linux).
    pub fn fontconfig_cache_dir(&self) -> PathBuf {
        self.data.join("cache").join("fontconfig")
    }
    pub fn meta_dir(&self) -> PathBuf {
        self.data.join("meta")
    }
    pub fn version_json(&self, id: &str) -> PathBuf {
        self.meta_dir().join("versions").join(format!("{id}.json"))
    }
    pub fn versions_dir(&self) -> PathBuf {
        self.data.join("versions")
    }
    pub fn client_jar(&self, id: &str) -> PathBuf {
        self.versions_dir().join(id).join(format!("{id}.jar"))
    }
    pub fn libraries_dir(&self) -> PathBuf {
        self.data.join("libraries")
    }
    pub fn assets_dir(&self) -> PathBuf {
        self.data.join("assets")
    }
    pub fn runtimes_dir(&self) -> PathBuf {
        self.data.join("runtimes")
    }
    pub fn natives_dir(&self, version_id: &str) -> PathBuf {
        self.data.join("natives").join(version_id)
    }
    pub fn instances_dir(&self) -> PathBuf {
        self.data.join("instances")
    }
    pub fn installers_dir(&self) -> PathBuf {
        self.data.join("installers")
    }
    pub fn temp_dir(&self) -> PathBuf {
        self.data.join("temp")
    }
}

/// Make a path suitable for handing to Java / other processes (no `\\?\` prefixes on Windows).
pub fn display_path(p: &Path) -> String {
    #[cfg(windows)]
    {
        dunce::simplified(p).display().to_string()
    }
    #[cfg(not(windows))]
    {
        p.display().to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migrates_the_old_data_folder_once() {
        let dir = tempfile::tempdir().unwrap();
        let (old, new) = (dir.path().join("luncher"), dir.path().join("aerion"));
        std::fs::create_dir_all(old.join("instances/Mój świat")).unwrap();
        migrate_old_root(&old, &new);
        assert!(new.join("instances/Mój świat").is_dir());
        assert!(!old.exists());

        // An existing new folder is never overwritten.
        std::fs::create_dir_all(old.join("x")).unwrap();
        migrate_old_root(&old, &new);
        assert!(old.join("x").is_dir());
        assert!(!new.join("x").exists());
    }
}
