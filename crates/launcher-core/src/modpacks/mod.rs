//! Modpacks: Modrinth `.mrpack`, CurseForge zip (`manifest.json`) and Aerion's own instance archive.

pub mod curseforge;
pub mod mrpack;
pub mod native;

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};
use crate::instances::{Instance, LoaderKind};
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

/// `fabric-loader` → Fabric, `forge-47.2.0` style ids → (kind, version).
pub fn loader_from_id(id: &str) -> Option<(LoaderKind, String)> {
    let (name, version) = id.split_once('-')?;
    let kind = LoaderKind::parse(name)?;
    Some((kind, version.to_owned()))
}

/// Entries in the game directory that are never exported.
pub fn excluded_from_export(rel: &str) -> bool {
    let first = rel.split('/').next().unwrap_or(rel);
    matches!(
        first,
        "logs"
            | "crash-reports"
            | "screenshots"
            | ".cache"
            | "natives"
            | ".fabric"
            | ".quilt"
            | "debug"
            | "quickPlay"
    ) || first.starts_with("hs_err_pid")
        || rel.ends_with(".log")
}
