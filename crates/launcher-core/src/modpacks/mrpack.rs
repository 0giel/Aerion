//! Modrinth modpack format (`.mrpack`, https://support.modrinth.com/en/articles/8802351).

use std::collections::BTreeMap;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::Engine;
use crate::error::{Error, Result};
use crate::instances::{Instance, InstanceManager, ModpackInfo};
use crate::loaders::LoaderKind;
use crate::modpacks::{
    ExportOptions, exported_loader_version, finish_platform_pack, import_into_new, pack_instance,
    write_pack,
};
use crate::mods::model::{ModProvider, Provider};
use crate::mods::modrinth::Modrinth;
use crate::net::download::{DownloadItem, VerifyMode};
use crate::progress::TaskCtx;
use crate::util::archive::{extract_zip_prefix, read_zip_entry};
use crate::util::fs::safe_join;
use crate::util::hash::{Hash, sha512_file};

/// Hosts the specification allows downloads from.
const ALLOWED_HOSTS: &[&str] = &[
    "cdn.modrinth.com",
    "github.com",
    "raw.githubusercontent.com",
    "gitlab.com",
];

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Index {
    pub format_version: u32,
    pub game: String,
    pub version_id: String,
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub summary: Option<String>,
    pub files: Vec<IndexFile>,
    pub dependencies: BTreeMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IndexFile {
    pub path: String,
    pub hashes: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub env: Option<Env>,
    pub downloads: Vec<String>,
    pub file_size: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Env {
    pub client: String,
    pub server: String,
}

fn allowed(url: &str) -> bool {
    reqwest::Url::parse(url)
        .ok()
        .filter(|u| u.scheme() == "https")
        .and_then(|u| u.host_str().map(str::to_owned))
        .is_some_and(|h| ALLOWED_HOSTS.contains(&h.as_str()))
}

/// The `dependencies` key of each loader in `modrinth.index.json`.
const LOADER_KEYS: [(&str, LoaderKind); 4] = [
    ("fabric-loader", LoaderKind::Fabric),
    ("quilt-loader", LoaderKind::Quilt),
    ("neoforge", LoaderKind::Neoforge),
    ("forge", LoaderKind::Forge),
];

fn loader_of(deps: &BTreeMap<String, String>) -> (LoaderKind, Option<String>) {
    LOADER_KEYS
        .iter()
        .find_map(|(key, kind)| Some((*kind, Some(deps.get(*key)?.clone()))))
        .unwrap_or((LoaderKind::Vanilla, None))
}

fn read_index(archive: &Path) -> Result<Index> {
    let bytes = read_zip_entry(archive, "modrinth.index.json")?
        .ok_or_else(|| Error::Invalid("missing modrinth.index.json".into()))?;
    let index: Index =
        serde_json::from_slice(&bytes).map_err(|e| Error::parse("modrinth.index.json", e))?;
    if index.format_version != 1 || index.game != "minecraft" {
        return Err(Error::Invalid(format!(
            "unsupported mrpack format {} for {}",
            index.format_version, index.game
        )));
    }
    Ok(index)
}

pub async fn import(
    engine: &Engine,
    instances: &InstanceManager,
    modrinth: &Modrinth,
    archive: &Path,
    origin: Option<ModpackInfo>,
    ctx: &TaskCtx,
) -> Result<Instance> {
    let a = archive.to_path_buf();
    let index = tokio::task::spawn_blocking(move || read_index(&a))
        .await
        .map_err(|e| Error::Invalid(e.to_string()))??;
    let game_version = index
        .dependencies
        .get("minecraft")
        .cloned()
        .ok_or_else(|| Error::Invalid("pack has no minecraft version".into()))?;
    let (loader, loader_version) = loader_of(&index.dependencies);
    ctx.stage("stage.modpack", Some(index.name.clone()));
    let spec = pack_instance(index.name.clone(), game_version, loader, loader_version);
    import_into_new(instances, spec, |inst| async move {
        install_files(engine, instances, archive, &index, &inst, ctx).await?;
        let info = origin.unwrap_or(ModpackInfo {
            provider: "local".into(),
            project_id: None,
            version_id: None,
            name: index.name.clone(),
            version_name: Some(index.version_id.clone()),
        });
        finish_platform_pack(engine, instances, &inst.id, info, modrinth, None, ctx).await
    })
    .await
}

async fn install_files(
    engine: &Engine,
    instances: &InstanceManager,
    archive: &Path,
    index: &Index,
    inst: &Instance,
    ctx: &TaskCtx,
) -> Result<()> {
    let game_dir = instances.game_dir(&inst.id)?;
    let mut items = Vec::new();
    for f in &index.files {
        if f.env.as_ref().is_some_and(|e| e.client == "unsupported") {
            continue;
        }
        let dest = safe_join(&game_dir, &f.path)?;
        let url = f.downloads.iter().find(|u| allowed(u)).ok_or_else(|| {
            Error::UnsafePath(format!("download host not allowed for {}", f.path))
        })?;
        let hash = f
            .hashes
            .get("sha512")
            .cloned()
            .map(Hash::Sha512)
            .or_else(|| f.hashes.get("sha1").cloned().map(Hash::Sha1));
        items.push(
            DownloadItem::new(url, dest)
                .hash(hash)
                .size(Some(f.file_size)),
        );
    }
    ctx.stage("stage.modpackFiles", Some(index.name.clone()));
    engine.downloader.run(items, VerifyMode::Fast, ctx).await?;

    let (a, g) = (archive.to_path_buf(), game_dir.clone());
    tokio::task::spawn_blocking(move || -> Result<()> {
        extract_zip_prefix(&a, "overrides/", &g)?;
        extract_zip_prefix(&a, "client-overrides/", &g)?;
        Ok(())
    })
    .await
    .map_err(|e| Error::Invalid(e.to_string()))?
}

/// Export an instance as `.mrpack`. Files known on Modrinth are referenced by URL; everything
/// else (and the chosen config folders) goes into `overrides/`.
pub async fn export(
    instances: &InstanceManager,
    modrinth: &Modrinth,
    id: &str,
    dest: &Path,
    opts: &ExportOptions,
) -> Result<()> {
    let inst = instances.get(id).await?;
    let game_dir = instances.game_dir(id)?;
    let content = instances.content(id)?;
    let entries = content.scan().await?;
    let mut deps = BTreeMap::new();
    deps.insert("minecraft".to_owned(), inst.game_version.clone());
    if let Some(v) = exported_loader_version(&inst)? {
        let key = LOADER_KEYS
            .iter()
            .find(|(_, kind)| *kind == inst.loader)
            .map(|(key, _)| *key)
            .ok_or_else(|| Error::Invalid(format!("{:?} has no pack loader key", inst.loader)))?;
        deps.insert(key.to_owned(), v);
    }

    let version_ids: Vec<String> = entries
        .iter()
        .filter(|e| e.enabled && e.provider == Some(Provider::Modrinth))
        .filter_map(|e| e.version_id.clone())
        .collect();
    let versions = modrinth
        .versions_by_id(&version_ids)
        .await
        .unwrap_or_default();
    let mut files = Vec::new();
    let mut overrides: Vec<(String, std::path::PathBuf)> = Vec::new();
    for e in entries.iter().filter(|e| e.enabled) {
        let rel = format!("{}/{}", e.kind.folder(), e.file_name);
        let remote = versions
            .iter()
            .filter(|v| Some(&v.id) == e.version_id.as_ref())
            .flat_map(|v| v.files.iter())
            .find(|f| {
                f.sha1.as_deref() == Some(e.sha1.as_str()) && f.url.as_deref().is_some_and(allowed)
            });
        match remote {
            Some(f) => {
                let sha512 = match &f.sha512 {
                    Some(h) => h.clone(),
                    None => sha512_file(&e.path(&game_dir)).await?,
                };
                let mut hashes = BTreeMap::new();
                hashes.insert("sha1".to_owned(), e.sha1.clone());
                hashes.insert("sha512".to_owned(), sha512);
                files.push(IndexFile {
                    path: rel,
                    hashes,
                    env: Some(Env {
                        client: "required".into(),
                        server: "required".into(),
                    }),
                    downloads: vec![f.url.clone().unwrap_or_default()],
                    file_size: e.size,
                });
            }
            None => overrides.push((format!("overrides/{rel}"), e.path(&game_dir))),
        }
    }
    let index = Index {
        format_version: 1,
        game: "minecraft".into(),
        version_id: opts.version.clone(),
        name: opts.name.clone(),
        summary: opts.summary.clone(),
        files,
        dependencies: deps,
    };
    let json = serde_json::to_vec_pretty(&index).map_err(|e| Error::parse("index", e))?;
    write_pack(
        dest,
        ("modrinth.index.json", json),
        overrides,
        game_dir,
        opts.include.clone(),
    )
    .await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_allowlist() {
        assert!(allowed(
            "https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar"
        ));
        assert!(allowed("https://github.com/x/y/releases/download/1/a.jar"));
        assert!(!allowed("http://cdn.modrinth.com/data/x.jar"), "https only");
        assert!(!allowed("https://evil.example.com/cdn.modrinth.com/x.jar"));
    }

    #[test]
    fn loader_detection() {
        let mut d = BTreeMap::new();
        d.insert("minecraft".to_owned(), "1.20.1".to_owned());
        d.insert("fabric-loader".to_owned(), "0.15.3".to_owned());
        assert_eq!(loader_of(&d), (LoaderKind::Fabric, Some("0.15.3".into())));
        d.clear();
        assert_eq!(loader_of(&d).0, LoaderKind::Vanilla);
    }

    #[test]
    fn rejects_traversal_in_index() {
        let dir = tempfile::tempdir().unwrap();
        assert!(safe_join(dir.path(), "../../.bashrc").is_err());
        assert!(safe_join(dir.path(), "mods/ok.jar").is_ok());
    }
}
