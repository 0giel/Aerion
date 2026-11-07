//! Modpacks: Modrinth `.mrpack`, CurseForge zip (`manifest.json`) and Aerion's own instance archive.

pub mod curseforge;
pub mod mrpack;
pub mod native;

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};
use std::path::PathBuf;

use crate::Engine;
use crate::instances::launch::ensure_version;
use crate::instances::{Instance, InstanceManager, ModpackInfo, NewInstance};
use crate::loaders::LoaderKind;
use crate::mods::curseforge::CurseForge;
use crate::mods::model::ContentKind;
use crate::mods::modrinth::Modrinth;
use crate::progress::TaskCtx;
use crate::util::archive::{ZipBuilder, read_zip_entry};
use crate::util::fs::safe_join;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub enum PackFormat {
    Mrpack,
    Curseforge,
    Aerion,
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ManualDownload {
    pub name: String,
    pub file_name: String,
    pub url: String,
    pub folder: String,
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ImportResult {
    pub instance: Instance,
    /// Files whose authors disabled automatic downloads (CurseForge); the user must fetch them.
    pub manual_downloads: Vec<ManualDownload>,
}

#[derive(Debug, Clone, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ExportOptions {
    pub format: PackFormat,
    pub name: String,
    pub version: String,
    pub summary: Option<String>,
    /// Paths relative to the game directory to ship as overrides (`config`, `options.txt`, …).
    pub include: Vec<String>,
}

/// The new instance a pack is imported into; nothing about it comes from this computer.
fn pack_instance(
    name: String,
    game_version: String,
    loader: LoaderKind,
    loader_version: Option<String>,
) -> NewInstance {
    NewInstance {
        name,
        game_version,
        loader,
        loader_version,
        icon: None,
        group: None,
        game_dir: None,
        link_existing: false,
        aerion_mod: false,
        shared_options: false,
    }
}

/// The last steps of a platform pack: recognise its files (for updates), record where it came
/// from and install the mod loader.
async fn finish_platform_pack(
    engine: &Engine,
    instances: &InstanceManager,
    id: &str,
    info: ModpackInfo,
    modrinth: &Modrinth,
    curseforge: Option<&CurseForge>,
    ctx: &TaskCtx,
) -> Result<Instance> {
    let content = instances.content(id)?;
    content.identify(modrinth, curseforge).await?;
    let inst = instances.modify(id, |i| i.modpack = Some(info)).await?;
    ensure_version(engine, instances, &inst, ctx).await?;
    instances.get(id).await
}

/// The loader version a platform pack names: `None` for vanilla. Horizon has no place in these
/// formats, and the version is only known once the loader was installed.
fn exported_loader_version(inst: &Instance) -> Result<Option<String>> {
    match inst.loader {
        LoaderKind::Vanilla => Ok(None),
        LoaderKind::Horizon => Err(Error::Invalid(
            "Horizon instances can't be shared as this pack format (it has no Horizon)".into(),
        )),
        _ => inst.loader_version.clone().map(Some).ok_or_else(|| {
            Error::Invalid("start the instance once so the loader version is known".into())
        }),
    }
}

/// Write a platform pack: its index, the content files it can't link to, and the folders the
/// player chose to include, all under `overrides/`.
async fn write_pack(
    dest: &Path,
    index: (&'static str, Vec<u8>),
    files: Vec<(String, PathBuf)>,
    game_dir: PathBuf,
    include: Vec<String>,
) -> Result<()> {
    let dest = dest.to_path_buf();
    crate::util::blocking(move || -> Result<()> {
        let mut zip = ZipBuilder::create(&dest)?;
        zip.add_bytes(index.0, &index.1)?;
        for (name, path) in files {
            zip.add_file(&name, &path)?;
        }
        for rel in include {
            let content_folder = ContentKind::INSTALLABLE.iter().any(|k| k.folder() == rel);
            if excluded_from_export(&rel) || content_folder {
                continue;
            }
            let p = safe_join(&game_dir, &rel)?;
            if p.is_dir() {
                zip.add_dir(&format!("overrides/{rel}"), &p, &|x| {
                    x.file_name()
                        .is_some_and(|n| n.to_string_lossy().ends_with(".log"))
                })?;
            } else if p.is_file() {
                zip.add_file(&format!("overrides/{rel}"), &p)?;
            }
        }
        zip.finish()
    })
    .await
}

pub fn detect_format(path: &Path) -> Result<PackFormat> {
    if read_zip_entry(path, "modrinth.index.json")?.is_some() {
        return Ok(PackFormat::Mrpack);
    }
    if native::read_marker(path)?.is_some() {
        return Ok(PackFormat::Aerion);
    }
    if let Some(bytes) = read_zip_entry(path, "manifest.json")?
        && serde_json::from_slice::<serde_json::Value>(&bytes)
            .ok()
            .and_then(|v| {
                v.get("manifestType")
                    .and_then(|t| t.as_str())
                    .map(|t| t == "minecraftModpack")
            })
            .unwrap_or(false)
    {
        return Ok(PackFormat::Curseforge);
    }
    Err(Error::Invalid(
        "not a supported modpack (.mrpack, CurseForge zip or Aerion archive)".into(),
    ))
}

/// Entries in the game directory that are never exported.
pub fn excluded_from_export(rel: &str) -> bool {
    let first = rel.split('/').next().unwrap_or(rel);
    crate::instances::regenerated_by_game(first)
        || matches!(first, "screenshots" | "debug" | "quickPlay")
        || rel.ends_with(".log")
}
