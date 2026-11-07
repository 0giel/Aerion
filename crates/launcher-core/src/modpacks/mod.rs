//! Modpacks: Modrinth `.mrpack`, CurseForge zip (`manifest.json`) and Aerion's own instance archive.

pub mod curseforge;
pub mod mrpack;
pub mod native;

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};
use std::future::Future;

use crate::Engine;
use crate::instances::launch::ensure_version;
use crate::instances::{Instance, InstanceManager, ModpackInfo, NewInstance};
use crate::loaders::LoaderKind;
use crate::mods::curseforge::CurseForge;
use crate::mods::modrinth::Modrinth;
use crate::progress::TaskCtx;
use crate::util::archive::read_zip_entry;

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

/// Create the instance and fill it in; a pack that fails half-way leaves no instance behind.
async fn import_into_new<T, Fut>(
    instances: &InstanceManager,
    spec: NewInstance,
    fill: impl FnOnce(Instance) -> Fut,
) -> Result<T>
where
    Fut: Future<Output = Result<T>>,
{
    let inst = instances.create(spec).await?;
    let id = inst.id.clone();
    let result = fill(inst).await;
    if result.is_err() {
        let _ = instances.delete(&id).await;
    }
    result
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
    content.scan().await?;
    let _ = content.identify(modrinth, curseforge).await;
    let inst = instances.modify(id, |i| i.modpack = Some(info)).await?;
    ensure_version(engine, instances, &inst, ctx).await?;
    instances.get(id).await
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
