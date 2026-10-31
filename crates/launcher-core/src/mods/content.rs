//! Installed content of an instance (`content.json`): provenance, enable/disable,
//! identification of manually added files, updates with backups.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::error::{Error, IoContext, Result};
use crate::instances::LoaderKind;
use crate::mods::curseforge::CurseForge;
use crate::mods::model::*;
use crate::mods::modrinth::Modrinth;
use crate::mods::resolve::InstallPlan;
use crate::mods::select::{best_version, loaders_for};
use crate::net::download::{DownloadItem, Downloader, VerifyMode};
use crate::progress::TaskCtx;
use crate::util::fs::{read_json, write_json};
use crate::util::hash::{Hash, curseforge_fingerprint_file, sha1_file};
use crate::util::now_secs;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ContentEntry {
    /// File name without the `.disabled` suffix.
    pub file_name: String,
    pub kind: ContentKind,
    pub enabled: bool,
    pub provider: Option<Provider>,
    pub project_id: Option<String>,
    pub version_id: Option<String>,
    pub title: String,
    pub version_number: Option<String>,
    pub icon_url: Option<String>,
    pub page_url: Option<String>,
    pub sha1: String,
    pub size: u64,
    pub modified: i64,
    pub installed_at: i64,
    /// Installed automatically as a dependency.
    pub dependency: bool,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct Manifest {
    #[serde(default)]
    entries: Vec<ContentEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    pub file_name: String,
    pub kind: ContentKind,
    pub title: String,
    pub current: Option<String>,
    pub version: ProjectVersion,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct BackupInfo {
    pub id: String,
    pub created: i64,
    pub files: Vec<String>,
}

const DISABLED: &str = ".disabled";

fn accepts(kind: ContentKind, name: &str) -> bool {
    let n = name.trim_end_matches(DISABLED).to_ascii_lowercase();
    match kind {
        ContentKind::Mod | ContentKind::Modpack => n.ends_with(".jar"),
        _ => n.ends_with(".zip"),
    }
}

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

pub struct ContentManager {
    instance_dir: PathBuf,
    game_dir: PathBuf,
}

impl ContentManager {
    pub fn new(instance_dir: PathBuf) -> Self {
        let game_dir = instance_dir.join("minecraft");
        Self {
            instance_dir,
            game_dir,
        }
    }

    fn manifest_path(&self) -> PathBuf {
        self.instance_dir.join("content.json")
    }

    async fn load(&self) -> Manifest {
        read_json(self.manifest_path())
            .await
            .ok()
            .flatten()
            .unwrap_or_default()
    }

    async fn save(&self, m: &Manifest) -> Result<()> {
        write_json(self.manifest_path(), m).await
    }

    pub fn folder(&self, kind: ContentKind) -> PathBuf {
        self.game_dir.join(kind.folder())
    }

    fn file_path(&self, e: &ContentEntry) -> PathBuf {
        let name = if e.enabled {
            e.file_name.clone()
        } else {
            format!("{}{DISABLED}", e.file_name)
        };
        self.folder(e.kind).join(name)
    }

    /// Reconcile `content.json` with what is actually on disk.
    pub async fn scan(&self) -> Result<Vec<ContentEntry>> {
        let mut manifest = self.load().await;
        let mut found: Vec<ContentEntry> = Vec::new();
        for kind in ContentKind::INSTALLABLE {
            let dir = self.folder(kind);
            let Ok(mut rd) = tokio::fs::read_dir(&dir).await else {
                continue;
            };
            while let Ok(Some(e)) = rd.next_entry().await {
                let name = e.file_name().to_string_lossy().into_owned();
                if !accepts(kind, &name)
                    || !e.file_type().await.map(|t| t.is_file()).unwrap_or(false)
                {
                    continue;
                }
                let enabled = !name.ends_with(DISABLED);
                let base = name.trim_end_matches(DISABLED).to_owned();
                let meta = e.metadata().await.at(e.path())?;
                let modified = meta
                    .modified()
                    .ok()
                    .and_then(|m| m.duration_since(std::time::UNIX_EPOCH).ok())
                    .map_or(0, |d| d.as_secs() as i64);
                let known = manifest
                    .entries
                    .iter()
                    .find(|x| x.kind == kind && x.file_name == base);
                let entry = match known {
                    Some(k) if k.size == meta.len() && k.modified == modified => ContentEntry {
                        enabled,
                        ..k.clone()
                    },
                    _ => {
                        let sha1 = sha1_file(&e.path()).await?;
                        match manifest.entries.iter().find(|x| x.sha1 == sha1) {
                            Some(k) => ContentEntry {
                                file_name: base.clone(),
                                enabled,
                                modified,
                                size: meta.len(),
                                kind,
                                ..k.clone()
                            },
                            None => ContentEntry {
                                title: base
                                    .trim_end_matches(".jar")
                                    .trim_end_matches(".zip")
                                    .to_owned(),
                                file_name: base,
                                kind,
                                enabled,
                                provider: None,
                                project_id: None,
                                version_id: None,
                                version_number: None,
                                icon_url: None,
                                page_url: None,
                                sha1,
                                size: meta.len(),
                                modified,
                                installed_at: now_secs(),
                                dependency: false,
                            },
                        }
                    }
                };
                found.push(entry);
            }
        }
        found.sort_by_key(|e| e.title.to_lowercase());
        if found != manifest.entries {
            manifest.entries = found.clone();
            self.save(&manifest).await?;
        }
        Ok(found)
    }

    pub async fn installed_projects(&self) -> HashSet<(Provider, String)> {
        self.load()
            .await
            .entries
            .into_iter()
            .filter_map(|e| Some((e.provider?, e.project_id?)))
            .collect()
    }

    pub async fn set_enabled(
        &self,
        kind: ContentKind,
        file_name: &str,
        enabled: bool,
    ) -> Result<()> {
        safe_file_name(file_name)?;
        let dir = self.folder(kind);
        let (from, to) = if enabled {
            (
                dir.join(format!("{file_name}{DISABLED}")),
                dir.join(file_name),
            )
        } else {
            (
                dir.join(file_name),
                dir.join(format!("{file_name}{DISABLED}")),
            )
        };
        if from.is_file() {
            tokio::fs::rename(&from, &to).await.at(&to)?;
        }
        let mut m = self.load().await;
        for e in m
            .entries
            .iter_mut()
            .filter(|e| e.kind == kind && e.file_name == file_name)
        {
            e.enabled = enabled;
        }
        self.save(&m).await
    }

    pub async fn remove(&self, kind: ContentKind, file_name: &str) -> Result<()> {
        safe_file_name(file_name)?;
        let dir = self.folder(kind);
        for p in [
            dir.join(file_name),
            dir.join(format!("{file_name}{DISABLED}")),
        ] {
            if p.is_file() {
                tokio::fs::remove_file(&p).await.at(&p)?;
            }
        }
        let mut m = self.load().await;
        m.entries
            .retain(|e| !(e.kind == kind && e.file_name == file_name));
        self.save(&m).await
    }

    /// Download the files of a plan into the right folder and record their origin.
    /// An older file of the same project is replaced.
    pub async fn install(
        &self,
        downloader: &Downloader,
        plan: &InstallPlan,
        kind: ContentKind,
        ctx: &TaskCtx,
    ) -> Result<Vec<ContentEntry>> {
        let provider = plan.provider;
        let mut items = Vec::new();
        let mut new_entries = Vec::new();
        for planned in &plan.install {
            let Some(file) = planned.version.primary_file() else {
                continue;
            };
            let Some(url) = &file.url else { continue };
            safe_file_name(&file.filename)?;
            let k = kind;
            let dest = self.folder(k).join(&file.filename);
            let hash = file
                .sha512
                .clone()
                .map(Hash::Sha512)
                .or_else(|| file.sha1.clone().map(Hash::Sha1));
            items.push(
                DownloadItem::new(url, dest)
                    .hash(hash)
                    .size(Some(file.size)),
            );
            new_entries.push(ContentEntry {
                file_name: file.filename.clone(),
                kind: k,
                enabled: true,
                provider,
                project_id: Some(planned.project_id.clone()),
                version_id: Some(planned.version.id.clone()),
                title: planned.title.clone(),
                version_number: Some(planned.version.version_number.clone()),
                icon_url: planned.icon_url.clone(),
                page_url: planned.page_url.clone(),
                sha1: file.sha1.clone().unwrap_or_default(),
                size: file.size,
                modified: 0,
                installed_at: now_secs(),
                dependency: planned.required_by.is_some(),
            });
        }
        ctx.stage("stage.installContent", None);
        downloader.run(items, VerifyMode::Full, ctx).await?;

        let mut m = self.load().await;
        for e in &new_entries {
            // Replace older files of the same project.
            let old: Vec<ContentEntry> = m
                .entries
                .iter()
                .filter(|x| {
                    x.provider == e.provider
                        && x.project_id == e.project_id
                        && x.file_name != e.file_name
                })
                .cloned()
                .collect();
            for o in old {
                let _ = tokio::fs::remove_file(self.file_path(&o)).await;
                m.entries
                    .retain(|x| x.file_name != o.file_name || x.kind != o.kind);
            }
            let mut e = e.clone();
            if e.sha1.is_empty() {
                e.sha1 = sha1_file(&self.folder(e.kind).join(&e.file_name))
                    .await
                    .unwrap_or_default();
            }
            if let Ok(meta) = tokio::fs::metadata(self.folder(e.kind).join(&e.file_name)).await {
                e.modified = meta
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map_or(0, |d| d.as_secs() as i64);
                e.size = meta.len();
            }
            m.entries
                .retain(|x| !(x.kind == e.kind && x.file_name == e.file_name));
            m.entries.push(e);
        }
        self.save(&m).await?;
        Ok(new_entries)
    }

    /// Fill in provenance of files added by hand: Modrinth by SHA-1, CurseForge by fingerprint.
    pub async fn identify(
        &self,
        modrinth: &Modrinth,
        curseforge: Option<&CurseForge>,
    ) -> Result<usize> {
        let mut m = self.load().await;
        let unknown: Vec<&ContentEntry> =
            m.entries.iter().filter(|e| e.provider.is_none()).collect();
        if unknown.is_empty() {
            return Ok(0);
        }
        let mut files = Vec::new();
        for e in &unknown {
            let fingerprint = match curseforge.filter(|c| c.has_key()) {
                Some(_) => curseforge_fingerprint_file(&self.file_path(e))
                    .await
                    .unwrap_or(0),
                None => 0,
            };
            files.push(LocalFile {
                key: format!("{}/{}", e.kind.folder(), e.file_name),
                sha1: e.sha1.clone(),
                fingerprint,
            });
        }
        let mut found: HashMap<String, Identified> =
            modrinth.identify(&files).await.unwrap_or_default();
        if let Some(cf) = curseforge.filter(|c| c.has_key()) {
            let rest: Vec<LocalFile> = files
                .iter()
                .filter(|f| !found.contains_key(&f.key))
                .cloned()
                .collect();
            if !rest.is_empty() {
                found.extend(cf.identify(&rest).await.unwrap_or_default());
            }
        }
        let mut n = 0;
        for e in m.entries.iter_mut().filter(|e| e.provider.is_none()) {
            if let Some(id) = found.get(&format!("{}/{}", e.kind.folder(), e.file_name)) {
                e.provider = Some(id.project.provider);
                e.project_id = Some(id.project.id.clone());
                e.version_id = Some(id.version.id.clone());
                e.version_number = Some(id.version.version_number.clone());
                e.title = id.project.title.clone();
                e.icon_url = id.project.icon_url.clone();
                e.page_url = Some(id.project.page_url.clone());
                n += 1;
            }
        }
        self.save(&m).await?;
        Ok(n)
    }

    pub async fn check_updates(
        &self,
        modrinth: &Modrinth,
        curseforge: Option<&CurseForge>,
        game_version: &str,
        loader: LoaderKind,
    ) -> Result<Vec<UpdateInfo>> {
        let entries = self.load().await.entries;
        let mut out = Vec::new();
        for kind in ContentKind::INSTALLABLE {
            let loaders = loaders_for(kind, loader, game_version);
            let mr: Vec<&ContentEntry> = entries
                .iter()
                .filter(|e| {
                    e.kind == kind && e.provider == Some(Provider::Modrinth) && !e.sha1.is_empty()
                })
                .collect();
            if !mr.is_empty() {
                let hashes: Vec<String> = mr.iter().map(|e| e.sha1.clone()).collect();
                let latest = modrinth
                    .latest_for_hashes(&hashes, &loaders, game_version)
                    .await?;
                for e in mr {
                    if let Some(v) = latest.get(&e.sha1)
                        && Some(&v.id) != e.version_id.as_ref()
                        && v.primary_file()
                            .is_some_and(|f| f.sha1.as_deref() != Some(e.sha1.as_str()))
                    {
                        out.push(UpdateInfo {
                            file_name: e.file_name.clone(),
                            kind,
                            title: e.title.clone(),
                            current: e.version_number.clone(),
                            version: v.clone(),
                        });
                    }
                }
            }
            if let Some(cf) = curseforge.filter(|c| c.has_key()) {
                for e in entries
                    .iter()
                    .filter(|e| e.kind == kind && e.provider == Some(Provider::Curseforge))
                {
                    let Some(pid) = &e.project_id else { continue };
                    let versions = cf.versions(pid, Some(game_version), &loaders).await?;
                    let current: u64 = e
                        .version_id
                        .as_deref()
                        .and_then(|v| v.parse().ok())
                        .unwrap_or(0);
                    if let Some(best) = best_version(&versions, game_version, &loaders)
                        && best.id.parse::<u64>().unwrap_or(0) > current
                    {
                        out.push(UpdateInfo {
                            file_name: e.file_name.clone(),
                            kind,
                            title: e.title.clone(),
                            current: e.version_number.clone(),
                            version: best.clone(),
                        });
                    }
                }
            }
        }
        Ok(out)
    }

    fn backups_dir(&self) -> PathBuf {
        self.instance_dir.join(".backups")
    }

    /// Apply updates; the replaced files and `content.json` are kept in `.backups/<id>`
    /// so [`ContentManager::undo_last_update`] can restore them.
    pub async fn apply_updates(
        &self,
        downloader: &Downloader,
        updates: &[UpdateInfo],
        ctx: &TaskCtx,
    ) -> Result<BackupInfo> {
        let id = chrono::Utc::now().format("%Y%m%d-%H%M%S").to_string();
        let dir = self.backups_dir().join(&id);
        tokio::fs::create_dir_all(&dir).await.at(&dir)?;
        let m = self.load().await;
        write_json(dir.join("content.json"), &m).await?;
        let mut items = Vec::new();
        let mut moved = Vec::new();
        for u in updates {
            let Some(old) = m
                .entries
                .iter()
                .find(|e| e.kind == u.kind && e.file_name == u.file_name)
            else {
                continue;
            };
            let Some(file) = u.version.primary_file() else {
                continue;
            };
            let Some(url) = &file.url else { continue };
            safe_file_name(&file.filename)?;
            let hash = file
                .sha512
                .clone()
                .map(Hash::Sha512)
                .or_else(|| file.sha1.clone().map(Hash::Sha1));
            items.push(
                DownloadItem::new(url, self.folder(u.kind).join(&file.filename))
                    .hash(hash)
                    .size(Some(file.size)),
            );
            moved.push((old.clone(), u.clone()));
        }
        ctx.stage("stage.updateContent", None);
        downloader.run(items, VerifyMode::Full, ctx).await?;

        let mut m = m;
        let mut files = Vec::new();
        for (old, u) in moved {
            let Some(file) = u.version.primary_file() else {
                continue;
            };
            let src = self.file_path(&old);
            let backup = dir
                .join(old.kind.folder())
                .join(src.file_name().unwrap_or_default());
            if let Some(p) = backup.parent() {
                tokio::fs::create_dir_all(p).await.at(p)?;
            }
            if file.filename != old.file_name && src.is_file() {
                tokio::fs::rename(&src, &backup).await.at(&backup)?;
            } else if src.is_file() {
                tokio::fs::copy(&src, &backup).await.at(&backup)?;
            }
            if !old.enabled {
                let new_path = self.folder(u.kind).join(&file.filename);
                let _ = tokio::fs::rename(
                    &new_path,
                    new_path.with_file_name(format!("{}{DISABLED}", file.filename)),
                )
                .await;
            }
            files.push(format!("{}/{}", old.kind.folder(), old.file_name));
            if let Some(e) = m
                .entries
                .iter_mut()
                .find(|e| e.kind == old.kind && e.file_name == old.file_name)
            {
                e.file_name = file.filename.clone();
                e.version_id = Some(u.version.id.clone());
                e.version_number = Some(u.version.version_number.clone());
                e.sha1 = file.sha1.clone().unwrap_or_default();
                e.size = file.size;
                e.modified = 0;
            }
        }
        self.save(&m).await?;
        let info = BackupInfo {
            id,
            created: now_secs(),
            files,
        };
        write_json(dir.join("backup.json"), &info).await?;
        Ok(info)
    }

    pub async fn backups(&self) -> Vec<BackupInfo> {
        let mut out = Vec::new();
        let Ok(mut rd) = tokio::fs::read_dir(self.backups_dir()).await else {
            return out;
        };
        while let Ok(Some(e)) = rd.next_entry().await {
            if let Ok(Some(b)) = read_json::<BackupInfo>(e.path().join("backup.json")).await {
                out.push(b);
            }
        }
        out.sort_by_key(|b| std::cmp::Reverse(b.created));
        out
    }

    /// Restore the files and manifest from the most recent update backup.
    pub async fn undo_last_update(&self) -> Result<bool> {
        let Some(latest) = self.backups().await.into_iter().next() else {
            return Ok(false);
        };
        let dir = self.backups_dir().join(&latest.id);
        let old: Manifest = read_json(dir.join("content.json"))
            .await?
            .unwrap_or_default();
        let current = self.load().await;
        // Remove files that the update introduced.
        for e in &current.entries {
            if !old
                .entries
                .iter()
                .any(|o| o.kind == e.kind && o.file_name == e.file_name)
            {
                let _ = tokio::fs::remove_file(self.file_path(e)).await;
            }
        }
        for kind in ContentKind::INSTALLABLE {
            let src_dir = dir.join(kind.folder());
            let Ok(mut rd) = tokio::fs::read_dir(&src_dir).await else {
                continue;
            };
            while let Ok(Some(e)) = rd.next_entry().await {
                let to = self.folder(kind).join(e.file_name());
                tokio::fs::rename(e.path(), &to).await.at(&to)?;
            }
        }
        self.save(&old).await?;
        tokio::fs::remove_dir_all(&dir).await.at(&dir)?;
        Ok(true)
    }
}

/// Paths of every content file, for exports.
pub fn entry_path(game_dir: &Path, e: &ContentEntry) -> PathBuf {
    let name = if e.enabled {
        e.file_name.clone()
    } else {
        format!("{}{DISABLED}", e.file_name)
    };
    game_dir.join(e.kind.folder()).join(name)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn scan_toggle_remove() {
        let dir = tempfile::tempdir().unwrap();
        let inst = dir.path().join("Inst ł");
        let mods = inst.join("minecraft/mods");
        std::fs::create_dir_all(&mods).unwrap();
        std::fs::write(mods.join("sodium.jar"), b"sodium").unwrap();
        std::fs::write(mods.join("old.jar.disabled"), b"old").unwrap();
        std::fs::write(mods.join("notes.txt"), b"x").unwrap();
        std::fs::create_dir_all(inst.join("minecraft/resourcepacks")).unwrap();
        std::fs::write(inst.join("minecraft/resourcepacks/Faithful.zip"), b"pk").unwrap();
        let m = ContentManager::new(inst.clone());
        let entries = m.scan().await.unwrap();
        assert_eq!(entries.len(), 3);
        let old = entries.iter().find(|e| e.file_name == "old.jar").unwrap();
        assert!(!old.enabled);
        assert_eq!(
            entries
                .iter()
                .find(|e| e.file_name == "sodium.jar")
                .unwrap()
                .sha1,
            crate::util::hash::sha1_hex(b"sodium")
        );

        m.set_enabled(ContentKind::Mod, "old.jar", true)
            .await
            .unwrap();
        assert!(mods.join("old.jar").is_file());
        m.set_enabled(ContentKind::Mod, "sodium.jar", false)
            .await
            .unwrap();
        assert!(mods.join("sodium.jar.disabled").is_file());
        let entries = m.scan().await.unwrap();
        assert!(
            !entries
                .iter()
                .find(|e| e.file_name == "sodium.jar")
                .unwrap()
                .enabled
        );

        m.remove(ContentKind::Mod, "sodium.jar").await.unwrap();
        assert!(!mods.join("sodium.jar.disabled").exists());
        assert_eq!(m.scan().await.unwrap().len(), 2);
        assert!(m.remove(ContentKind::Mod, "../x").await.is_err());
    }
}
