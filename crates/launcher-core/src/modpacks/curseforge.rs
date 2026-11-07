//! CurseForge modpack zips (`manifest.json` + `overrides/`).

use std::collections::HashMap;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::Engine;
use crate::error::{Error, Result};
use crate::instances::{InstanceManager, ModpackInfo};
use crate::loaders::LoaderKind;
use crate::modpacks::{
    ExportOptions, ImportResult, ManualDownload, exported_loader_version, finish_platform_pack,
    import_into_new, pack_instance, write_pack,
};
use crate::mods::curseforge::CurseForge;
use crate::mods::model::{ModProvider, Provider};
use crate::mods::modrinth::Modrinth;
use crate::mods::resolve::{InstallPlan, PlannedFile};
use crate::progress::TaskCtx;
use crate::util::archive::{extract_zip_prefix, read_zip_entry};
use crate::util::fs::safe_file_name;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub minecraft: Minecraft,
    pub manifest_type: String,
    pub manifest_version: u32,
    pub name: String,
    #[serde(default)]
    pub version: Option<String>,
    #[serde(default)]
    pub author: Option<String>,
    pub files: Vec<FileRef>,
    #[serde(default)]
    pub overrides: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Minecraft {
    pub version: String,
    #[serde(default)]
    pub mod_loaders: Vec<ModLoader>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModLoader {
    pub id: String,
    #[serde(default)]
    pub primary: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileRef {
    #[serde(rename = "projectID")]
    pub project_id: u64,
    #[serde(rename = "fileID")]
    pub file_id: u64,
    #[serde(default = "yes")]
    pub required: bool,
}

fn yes() -> bool {
    true
}

/// `forge-47.2.0`, `fabric-0.15.3` → (kind, version).
fn loader_from_id(id: &str) -> Option<(LoaderKind, String)> {
    let (name, version) = id.split_once('-')?;
    let kind = LoaderKind::parse(name)?;
    Some((kind, version.to_owned()))
}

pub fn read_manifest(archive: &Path) -> Result<Manifest> {
    let bytes = read_zip_entry(archive, "manifest.json")?
        .ok_or_else(|| Error::Invalid("missing manifest.json".into()))?;
    serde_json::from_slice(&bytes).map_err(|e| Error::parse("manifest.json", e))
}

pub async fn import(
    engine: &Engine,
    instances: &InstanceManager,
    cf: &CurseForge,
    modrinth: &Modrinth,
    archive: &Path,
    origin: Option<ModpackInfo>,
    ctx: &TaskCtx,
) -> Result<ImportResult> {
    if !cf.has_key() {
        return Err(Error::CurseForgeKeyMissing);
    }
    let a = archive.to_path_buf();
    let manifest = tokio::task::spawn_blocking(move || read_manifest(&a))
        .await
        .map_err(|e| Error::Invalid(e.to_string()))??;
    let primary = manifest
        .minecraft
        .mod_loaders
        .iter()
        .find(|l| l.primary)
        .or(manifest.minecraft.mod_loaders.first());
    let (loader, loader_version) = primary
        .and_then(|l| loader_from_id(&l.id))
        .map_or((LoaderKind::Vanilla, None), |(k, v)| (k, Some(v)));
    ctx.stage("stage.modpack", Some(manifest.name.clone()));
    let spec = pack_instance(
        manifest.name.clone(),
        manifest.minecraft.version.clone(),
        loader,
        loader_version,
    );
    import_into_new(instances, spec, |inst| async move {
        let id = inst.id;
        let game_dir = instances.game_dir(&id)?;
        let file_ids: Vec<u64> = manifest
            .files
            .iter()
            .filter(|f| f.required)
            .map(|f| f.file_id)
            .collect();
        let versions = cf.files_by_id(&file_ids).await?;
        let project_ids: Vec<String> = versions.iter().map(|v| v.project_id.clone()).collect();
        let projects: HashMap<String, _> = cf
            .projects(&project_ids)
            .await?
            .into_iter()
            .map(|p| (p.id.clone(), p))
            .collect();

        let content = instances.content(&id)?;
        let mut manual = Vec::new();
        let mut by_kind: HashMap<_, Vec<PlannedFile>> = HashMap::new();
        for v in versions {
            let project = projects.get(&v.project_id);
            let kind = project
                .map(|p| p.kind)
                .unwrap_or(crate::mods::model::ContentKind::Mod);
            let Some(file) = v.primary_file().cloned() else {
                continue;
            };
            safe_file_name(&file.filename)?;
            if file.url.is_none() {
                manual.push(ManualDownload {
                    name: project
                        .map(|p| p.title.clone())
                        .unwrap_or_else(|| v.project_id.clone()),
                    file_name: file.filename.clone(),
                    url: v.page_url.clone().unwrap_or_default(),
                    folder: game_dir.join(kind.folder()).display().to_string(),
                });
                continue;
            }
            by_kind.entry(kind).or_default().push(PlannedFile {
                project_id: v.project_id.clone(),
                title: project.map(|p| p.title.clone()).unwrap_or_default(),
                icon_url: project.and_then(|p| p.icon_url.clone()),
                page_url: project.map(|p| p.page_url.clone()),
                version: v,
                required_by: None,
            });
        }
        ctx.stage("stage.modpackFiles", Some(manifest.name.clone()));
        for (kind, install) in by_kind {
            let plan = InstallPlan {
                provider: Some(Provider::Curseforge),
                install,
                ..Default::default()
            };
            content
                .install(&engine.downloader, &plan, kind, ctx)
                .await?;
        }
        let prefix = format!(
            "{}/",
            manifest
                .overrides
                .clone()
                .unwrap_or_else(|| "overrides".into())
                .trim_end_matches('/')
        );
        let (a, g) = (archive.to_path_buf(), game_dir.clone());
        tokio::task::spawn_blocking(move || extract_zip_prefix(&a, &prefix, &g))
            .await
            .map_err(|e| Error::Invalid(e.to_string()))??;
        let info = origin.unwrap_or(ModpackInfo {
            provider: "local".into(),
            project_id: None,
            version_id: None,
            name: manifest.name.clone(),
            version_name: manifest.version.clone(),
        });
        let instance =
            finish_platform_pack(engine, instances, &id, info, modrinth, Some(cf), ctx).await?;
        Ok(ImportResult {
            instance,
            manual_downloads: manual,
        })
    })
    .await
}

/// Export as a CurseForge pack: CurseForge-tracked files by id, the rest in `overrides/`.
pub async fn export(
    instances: &InstanceManager,
    id: &str,
    dest: &Path,
    opts: &ExportOptions,
) -> Result<()> {
    let inst = instances.get(id).await?;
    let game_dir = instances.game_dir(id)?;
    let entries = instances.content(id)?.scan().await?;
    let mut files = Vec::new();
    let mut overrides = Vec::new();
    for e in entries.iter().filter(|e| e.enabled) {
        match (
            e.provider,
            e.project_id.as_deref().and_then(|p| p.parse().ok()),
            e.version_id.as_deref().and_then(|v| v.parse().ok()),
        ) {
            (Some(Provider::Curseforge), Some(project_id), Some(file_id)) => files.push(FileRef {
                project_id,
                file_id,
                required: true,
            }),
            _ => overrides.push((
                format!("overrides/{}/{}", e.kind.folder(), e.file_name),
                e.path(&game_dir),
            )),
        }
    }
    let mod_loaders = exported_loader_version(&inst)?
        .map(|v| ModLoader {
            id: format!("{}-{v}", inst.loader.as_str()),
            primary: true,
        })
        .into_iter()
        .collect();
    let manifest = Manifest {
        minecraft: Minecraft {
            version: inst.game_version.clone(),
            mod_loaders,
        },
        manifest_type: "minecraftModpack".into(),
        manifest_version: 1,
        name: opts.name.clone(),
        version: Some(opts.version.clone()),
        author: None,
        files,
        overrides: Some("overrides".into()),
    };
    let json = serde_json::to_vec_pretty(&manifest).map_err(|e| Error::parse("manifest", e))?;
    write_pack(
        dest,
        ("manifest.json", json),
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
    fn parses_manifest_and_loader_ids() {
        let m: Manifest = serde_json::from_str(
            r#"{"minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.2.0","primary":true}]},"manifestType":"minecraftModpack","manifestVersion":1,"name":"Pack","version":"1.0","author":"me","files":[{"projectID":238222,"fileID":5000,"required":true}],"overrides":"overrides"}"#,
        )
        .unwrap();
        assert_eq!(m.files[0].project_id, 238222);
        assert_eq!(
            loader_from_id(&m.minecraft.mod_loaders[0].id),
            Some((LoaderKind::Forge, "47.2.0".into()))
        );
        assert_eq!(
            loader_from_id("neoforge-21.1.77"),
            Some((LoaderKind::Neoforge, "21.1.77".into()))
        );
        assert_eq!(
            loader_from_id("fabric-0.15.3"),
            Some((LoaderKind::Fabric, "0.15.3".into()))
        );
    }
}
