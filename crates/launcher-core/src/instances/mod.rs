//! Instances: isolated game directories with a human-editable `instance.json`.
//!
//! ```text
//! instances/<id>/instance.json   settings (this module)
//! instances/<id>/content.json    installed mods/packs with their origin (mods module)
//! instances/<id>/.backups/       snapshots made before "update all"
//! instances/<id>/minecraft/      the game directory (mods, config, saves, ...)
//! ```

pub mod launch;
pub mod shortcut;

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::error::{Error, IoContext, Result};
use crate::java::flags::{JvmPreset, validate_syntax};
use crate::util::fs::{copy_dir_sync, read_json, write_json};
use crate::util::image::ImageKind;
use crate::util::names::{folder_name, validate_display_name};
use crate::util::now_secs;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "lowercase")]
pub enum LoaderKind {
    Vanilla,
    Fabric,
    Quilt,
    Forge,
    Neoforge,
}

impl LoaderKind {
    pub fn as_str(self) -> &'static str {
        match self {
            LoaderKind::Vanilla => "vanilla",
            LoaderKind::Fabric => "fabric",
            LoaderKind::Quilt => "quilt",
            LoaderKind::Forge => "forge",
            LoaderKind::Neoforge => "neoforge",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s.to_ascii_lowercase().as_str() {
            "vanilla" | "minecraft" => Some(Self::Vanilla),
            "fabric" | "fabric-loader" => Some(Self::Fabric),
            "quilt" | "quilt-loader" => Some(Self::Quilt),
            "forge" => Some(Self::Forge),
            "neoforge" | "neo-forge" => Some(Self::Neoforge),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct Resolution {
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ModpackInfo {
    /// `modrinth`, `curseforge` or `local`.
    pub provider: String,
    pub project_id: Option<String>,
    pub version_id: Option<String>,
    pub name: String,
    pub version_name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase", default)]
pub struct Instance {
    /// Folder name; always taken from the directory, never trusted from the file.
    pub id: String,
    pub name: String,
    pub game_version: String,
    pub loader: LoaderKind,
    pub loader_version: Option<String>,
    /// Launchable version id once the loader is installed (e.g. `fabric-loader-0.20.1-26.3`).
    pub version_id: Option<String>,
    pub java_path: Option<PathBuf>,
    pub max_memory_mb: Option<u32>,
    pub min_memory_mb: Option<u32>,
    pub jvm_preset: Option<JvmPreset>,
    pub jvm_args: Vec<String>,
    pub game_args: Vec<String>,
    pub env_vars: BTreeMap<String, String>,
    pub wrapper: Option<String>,
    pub resolution: Option<Resolution>,
    pub join_server: Option<String>,
    /// Built-in icon key (`grass`, `diamond`, …) or `file:<name>` inside the instance folder.
    pub icon: Option<String>,
    /// Pinned by the user: listed first and shown under "Favourites".
    pub favorite: bool,
    pub group: Option<String>,
    pub tags: Vec<String>,
    pub notes: Option<String>,
    pub created_at: i64,
    pub last_played: Option<i64>,
    pub play_time_secs: u64,
    pub modpack: Option<ModpackInfo>,
}

impl Default for Instance {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            game_version: String::new(),
            loader: LoaderKind::Vanilla,
            loader_version: None,
            version_id: None,
            java_path: None,
            max_memory_mb: None,
            min_memory_mb: None,
            jvm_preset: None,
            jvm_args: vec![],
            game_args: vec![],
            env_vars: BTreeMap::new(),
            wrapper: None,
            resolution: None,
            join_server: None,
            icon: None,
            favorite: false,
            group: None,
            tags: vec![],
            notes: None,
            created_at: 0,
            last_played: None,
            play_time_secs: 0,
            modpack: None,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct NewInstance {
    pub name: String,
    pub game_version: String,
    pub loader: LoaderKind,
    pub loader_version: Option<String>,
    pub icon: Option<String>,
    pub group: Option<String>,
}

pub struct InstanceManager {
    root: PathBuf,
    lock: Mutex<()>,
}

const FILE: &str = "instance.json";
/// Custom icons are files named `icon-<millis>.<ext>` in the instance folder; a new name per
/// change keeps cached images from going stale.
const ICON_PREFIX: &str = "icon-";
const MAX_ICON_BYTES: u64 = 2 * 1024 * 1024;

/// `mc:<texture>` (a Minecraft item/block texture, e.g. `mc:item/diamond`),
/// `file:icon-<millis>.<ext>`, or an older built-in key (`grass`, `diamond`, …).
pub fn validate_icon(icon: &str) -> Result<()> {
    let ok = match icon.strip_prefix("file:") {
        Some(file) => is_icon_file(file),
        None if icon.starts_with("mc:") => crate::game::textures::valid_texture(&icon[3..]),
        None => {
            !icon.is_empty()
                && icon.len() <= 32
                && icon
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        }
    };
    if ok {
        Ok(())
    } else {
        Err(Error::Invalid(format!("invalid icon {icon:?}")))
    }
}

/// `icon-<digits>.<png|jpg|webp|gif>`.
pub fn is_icon_file(name: &str) -> bool {
    name.strip_prefix(ICON_PREFIX)
        .and_then(|rest| rest.split_once('.'))
        .is_some_and(|(stamp, ext)| {
            !stamp.is_empty()
                && stamp.bytes().all(|b| b.is_ascii_digit())
                && ImageKind::from_ext(ext).is_some_and(|k| k.ext() == ext)
        })
}

impl InstanceManager {
    pub fn new(root: PathBuf) -> Self {
        Self {
            root,
            lock: Mutex::new(()),
        }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn dir(&self, id: &str) -> Result<PathBuf> {
        if id.is_empty() || id.contains(['/', '\\']) || id == "." || id == ".." {
            return Err(Error::InstanceNotFound(id.to_owned()));
        }
        Ok(self.root.join(id))
    }

    pub fn game_dir(&self, id: &str) -> Result<PathBuf> {
        Ok(self.dir(id)?.join("minecraft"))
    }

    pub async fn list(&self) -> Vec<Instance> {
        let mut out = Vec::new();
        let Ok(mut rd) = tokio::fs::read_dir(&self.root).await else {
            return out;
        };
        while let Ok(Some(e)) = rd.next_entry().await {
            let id = e.file_name().to_string_lossy().into_owned();
            match self.get(&id).await {
                Ok(i) => out.push(i),
                Err(Error::InstanceNotFound(_)) => {}
                Err(err) => tracing::warn!(%id, error = %err, "skipping unreadable instance"),
            }
        }
        out.sort_by(|a, b| {
            b.last_played
                .cmp(&a.last_played)
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
        });
        out
    }

    pub async fn get(&self, id: &str) -> Result<Instance> {
        let dir = self.dir(id)?;
        let mut inst: Instance = read_json(dir.join(FILE))
            .await?
            .ok_or_else(|| Error::InstanceNotFound(id.to_owned()))?;
        inst.id = id.to_owned();
        Ok(inst)
    }

    async fn write(&self, inst: &Instance) -> Result<()> {
        write_json(self.dir(&inst.id)?.join(FILE), inst).await
    }

    fn unique_id(&self, name: &str) -> String {
        let base = folder_name(name);
        let mut id = base.clone();
        let mut n = 2;
        while self.root.join(&id).exists() {
            id = format!("{base} ({n})");
            n += 1;
        }
        id
    }

    pub async fn create(&self, spec: NewInstance) -> Result<Instance> {
        let name = validate_display_name(&spec.name)?;
        if let Some(icon) = &spec.icon {
            validate_icon(icon)?;
        }
        if spec.game_version.trim().is_empty() {
            return Err(Error::Invalid("missing game version".into()));
        }
        let _g = self.lock.lock().await;
        let id = self.unique_id(&name);
        let dir = self.root.join(&id);
        tokio::fs::create_dir_all(dir.join("minecraft"))
            .await
            .at(&dir)?;
        let inst = Instance {
            id,
            name,
            game_version: spec.game_version.trim().to_owned(),
            loader: spec.loader,
            loader_version: spec.loader_version.filter(|v| !v.is_empty()),
            icon: spec.icon.filter(|i| !i.starts_with("file:")),
            group: spec.group.filter(|g| !g.trim().is_empty()),
            created_at: now_secs(),
            ..Instance::default()
        };
        self.write(&inst).await?;
        Ok(inst)
    }

    /// Save user edits. Identity and statistics are kept from disk.
    pub async fn update(&self, mut edited: Instance) -> Result<Instance> {
        let _g = self.lock.lock().await;
        let current = self.get(&edited.id).await?;
        edited.name = validate_display_name(&edited.name)?;
        validate_syntax(&edited.jvm_args)?;
        if let Some(icon) = &edited.icon {
            validate_icon(icon)?;
        }
        if edited
            .game_args
            .iter()
            .any(|a| a.contains(['\0', '\n', '\r']))
        {
            return Err(Error::Invalid(
                "game arguments may not contain line breaks".into(),
            ));
        }
        if let Some(m) = edited.max_memory_mb
            && !(512..=262_144).contains(&m)
        {
            return Err(Error::Invalid(
                "memory must be between 512 MB and 256 GB".into(),
            ));
        }
        // Changing version or loader invalidates the installed profile.
        if edited.game_version != current.game_version
            || edited.loader != current.loader
            || edited.loader_version != current.loader_version
        {
            edited.version_id = None;
        }
        edited.created_at = current.created_at;
        edited.play_time_secs = current.play_time_secs;
        edited.last_played = current.last_played;
        self.write(&edited).await?;
        Ok(edited)
    }

    /// Internal updates (installed version id, statistics) without validation.
    pub async fn modify(&self, id: &str, f: impl FnOnce(&mut Instance)) -> Result<Instance> {
        let _g = self.lock.lock().await;
        let mut inst = self.get(id).await?;
        f(&mut inst);
        self.write(&inst).await?;
        Ok(inst)
    }

    pub async fn set_favorite(&self, id: &str, favorite: bool) -> Result<Instance> {
        self.modify(id, |i| i.favorite = favorite).await
    }

    /// Built-in icon key, or `None` for the default icon. Custom image files are removed.
    pub async fn set_icon(&self, id: &str, icon: Option<String>) -> Result<Instance> {
        if let Some(i) = &icon {
            if i.starts_with("file:") {
                return Err(Error::Invalid("use import_icon for image files".into()));
            }
            validate_icon(i)?;
        }
        let inst = self.modify(id, |i| i.icon = icon).await?;
        self.remove_icon_files(id, None).await;
        Ok(inst)
    }

    /// Copy a user-picked image (PNG, JPEG, WebP or GIF, at most 2 MB) into the instance
    /// folder and use it as the icon.
    pub async fn import_icon(&self, id: &str, source: &Path) -> Result<Instance> {
        let dir = self.dir(id)?;
        self.get(id).await?;
        let meta = tokio::fs::metadata(source).await.at(source)?;
        if meta.len() > MAX_ICON_BYTES {
            return Err(Error::FileTooLarge {
                max_kb: MAX_ICON_BYTES / 1024,
            });
        }
        let bytes = tokio::fs::read(source).await.at(source)?;
        let kind = ImageKind::sniff(&bytes).ok_or(Error::UnsupportedImage)?;
        let millis = chrono::Utc::now().timestamp_millis();
        let name = format!("{ICON_PREFIX}{millis}.{}", kind.ext());
        let path = dir.join(&name);
        tokio::fs::write(&path, &bytes).await.at(&path)?;
        let inst = self
            .modify(id, |i| i.icon = Some(format!("file:{name}")))
            .await?;
        self.remove_icon_files(id, Some(&name)).await;
        Ok(inst)
    }

    /// Path of a custom icon file (validated name) for serving it to the UI.
    pub fn icon_file(&self, id: &str, name: &str) -> Result<PathBuf> {
        if !is_icon_file(name) {
            return Err(Error::Invalid(format!("invalid icon file {name:?}")));
        }
        Ok(self.dir(id)?.join(name))
    }

    async fn remove_icon_files(&self, id: &str, keep: Option<&str>) {
        let Ok(dir) = self.dir(id) else { return };
        let Ok(mut rd) = tokio::fs::read_dir(&dir).await else {
            return;
        };
        while let Ok(Some(e)) = rd.next_entry().await {
            let name = e.file_name().to_string_lossy().into_owned();
            if is_icon_file(&name) && Some(name.as_str()) != keep {
                let _ = tokio::fs::remove_file(e.path()).await;
            }
        }
    }

    pub async fn record_play(&self, id: &str, started: i64, secs: u64) -> Result<()> {
        self.modify(id, |i| {
            i.last_played = Some(started);
            i.play_time_secs += secs;
        })
        .await
        .map(|_| ())
    }

    pub async fn delete(&self, id: &str) -> Result<()> {
        let _g = self.lock.lock().await;
        let dir = self.dir(id)?;
        if !dir.join(FILE).is_file() {
            return Err(Error::InstanceNotFound(id.to_owned()));
        }
        tokio::fs::remove_dir_all(&dir).await.at(&dir)
    }

    /// Copy an instance (worlds, mods, configs) under a new name.
    pub async fn clone_instance(&self, id: &str, new_name: &str) -> Result<Instance> {
        let name = validate_display_name(new_name)?;
        let src = self.get(id).await?;
        let _g = self.lock.lock().await;
        let new_id = self.unique_id(&name);
        let from = self.dir(id)?;
        let to = self.root.join(&new_id);
        tokio::task::spawn_blocking(move || {
            copy_dir_sync(&from, &to, &|p: &Path| {
                let n = p
                    .file_name()
                    .map(|n| n.to_string_lossy().into_owned())
                    .unwrap_or_default();
                matches!(n.as_str(), "logs" | "crash-reports" | ".backups")
                    || n.starts_with("hs_err_pid")
            })
        })
        .await
        .map_err(|e| Error::Invalid(e.to_string()))??;
        let inst = Instance {
            id: new_id,
            name,
            created_at: now_secs(),
            last_played: None,
            play_time_secs: 0,
            ..src
        };
        self.write(&inst).await?;
        Ok(inst)
    }

    /// All group names in use, for the UI's group picker.
    pub async fn groups(&self) -> Vec<String> {
        let mut g: Vec<String> = self
            .list()
            .await
            .into_iter()
            .filter_map(|i| i.group)
            .collect();
        g.sort_by_key(|s| s.to_lowercase());
        g.dedup();
        g
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spec(name: &str) -> NewInstance {
        NewInstance {
            name: name.into(),
            game_version: "26.3".into(),
            loader: LoaderKind::Fabric,
            loader_version: None,
            icon: None,
            group: Some("Survival".into()),
        }
    }

    #[tokio::test]
    async fn crud_clone_and_unique_ids() {
        let dir = tempfile::tempdir().unwrap();
        let m = InstanceManager::new(dir.path().join("instances"));
        let a = m.create(spec("Mój świat")).await.unwrap();
        assert_eq!(a.id, "Mój świat");
        let b = m.create(spec("Mój świat")).await.unwrap();
        assert_eq!(b.id, "Mój świat (2)");
        assert!(m.game_dir(&a.id).unwrap().is_dir());

        std::fs::create_dir_all(m.game_dir(&a.id).unwrap().join("saves/World 1")).unwrap();
        std::fs::write(
            m.game_dir(&a.id).unwrap().join("saves/World 1/level.dat"),
            b"x",
        )
        .unwrap();
        std::fs::create_dir_all(m.game_dir(&a.id).unwrap().join("logs")).unwrap();
        let c = m.clone_instance(&a.id, "Kopia").await.unwrap();
        assert!(
            m.game_dir(&c.id)
                .unwrap()
                .join("saves/World 1/level.dat")
                .is_file()
        );
        assert!(!m.game_dir(&c.id).unwrap().join("logs").exists());

        let mut edited = m.get(&a.id).await.unwrap();
        edited.version_id = Some("fabric-loader-x".into());
        let edited = m.update(edited).await.unwrap();
        let mut changed = edited.clone();
        changed.game_version = "1.21.11".into();
        assert!(
            m.update(changed).await.unwrap().version_id.is_none(),
            "version change resets profile"
        );

        let mut bad = m.get(&a.id).await.unwrap();
        bad.jvm_args = vec!["-cp".into()];
        assert!(m.update(bad).await.is_err());

        m.record_play(&a.id, 100, 30).await.unwrap();
        assert_eq!(m.get(&a.id).await.unwrap().play_time_secs, 30);
        assert_eq!(m.list().await.len(), 3);
        assert_eq!(m.groups().await, ["Survival"]);
        m.delete(&b.id).await.unwrap();
        assert_eq!(m.list().await.len(), 2);
        assert!(m.dir("../escape").unwrap_err().info().code == "instance.notFound");
    }

    #[tokio::test]
    async fn icons_and_favourites() {
        let dir = tempfile::tempdir().unwrap();
        let m = InstanceManager::new(dir.path().join("instances"));
        let mut s = spec("Ikony");
        s.icon = Some("diamond".into());
        let a = m.create(s).await.unwrap();
        assert_eq!(a.icon.as_deref(), Some("diamond"));
        let mut bad = spec("Zła");
        bad.icon = Some("../x".into());
        assert!(m.create(bad).await.is_err());

        let png = dir.path().join("pic.png");
        std::fs::write(&png, crate::util::image::test_png(32, 32)).unwrap();
        let a = m.import_icon(&a.id, &png).await.unwrap();
        let file = a
            .icon
            .as_deref()
            .unwrap()
            .strip_prefix("file:")
            .unwrap()
            .to_owned();
        assert!(m.icon_file(&a.id, &file).unwrap().is_file());
        assert!(m.icon_file(&a.id, "../instance.json").is_err());
        let txt = dir.path().join("note.txt");
        std::fs::write(&txt, b"hello").unwrap();
        assert_eq!(
            m.import_icon(&a.id, &txt).await.unwrap_err().info().code,
            "image.unsupported"
        );

        let a = m
            .set_icon(&a.id, Some("mc:item/diamond_sword".into()))
            .await
            .unwrap();
        assert_eq!(a.icon.as_deref(), Some("mc:item/diamond_sword"));
        assert!(
            m.set_icon(&a.id, Some("mc:entity/creeper".into()))
                .await
                .is_err()
        );
        assert!(
            !m.icon_file(&a.id, &file).unwrap().exists(),
            "old file removed"
        );
        assert!(
            m.set_icon(&a.id, Some("file:icon-1.png".into()))
                .await
                .is_err()
        );

        assert!(m.set_favorite(&a.id, true).await.unwrap().favorite);
        assert!(m.get(&a.id).await.unwrap().favorite);
    }
}
