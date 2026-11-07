//! Mod loaders: Fabric and Quilt (meta servers), Forge and NeoForge (installer jars).

pub mod fabric;
pub mod forge;

use futures_util::{StreamExt, stream};
use serde::{Deserialize, Serialize};

use crate::Engine;
use crate::error::{Error, Result};
use crate::mojang::library::{MavenCoord, maven_url};
use crate::mojang::version::Library;
use crate::net::client::Http;
use crate::progress::TaskCtx;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "lowercase")]
pub enum LoaderKind {
    Vanilla,
    Fabric,
    Quilt,
    Forge,
    Neoforge,
    /// Horizon: Minecraft 26.3 rewritten in Rust (see [`crate::horizon`]); no Java, no Java mods.
    Horizon,
}

impl LoaderKind {
    pub fn as_str(self) -> &'static str {
        match self {
            LoaderKind::Vanilla => "vanilla",
            LoaderKind::Fabric => "fabric",
            LoaderKind::Quilt => "quilt",
            LoaderKind::Forge => "forge",
            LoaderKind::Neoforge => "neoforge",
            LoaderKind::Horizon => "horizon",
        }
    }

    /// Anything but the plain game (which gets less memory by default).
    pub fn is_modded(self) -> bool {
        self != LoaderKind::Vanilla
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s.to_ascii_lowercase().as_str() {
            "vanilla" | "minecraft" => Some(Self::Vanilla),
            "fabric" | "fabric-loader" => Some(Self::Fabric),
            "quilt" | "quilt-loader" => Some(Self::Quilt),
            "forge" => Some(Self::Forge),
            "neoforge" | "neo-forge" => Some(Self::Neoforge),
            "horizon" => Some(Self::Horizon),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct LoaderVersion {
    pub version: String,
    pub stable: bool,
}

pub fn is_prerelease(v: &str) -> bool {
    let v = v.to_ascii_lowercase();
    ["beta", "alpha", "pre", "rc", "snapshot"]
        .iter()
        .any(|t| v.contains(t))
}

/// Loader versions for a game version, newest first.
pub async fn versions(
    engine: &Engine,
    kind: LoaderKind,
    game_version: &str,
) -> Result<Vec<LoaderVersion>> {
    match kind {
        // Horizon builds are not loader versions: see `crate::horizon`.
        LoaderKind::Vanilla | LoaderKind::Horizon => Ok(vec![]),
        LoaderKind::Fabric => fabric::versions(engine, &fabric::FABRIC, game_version).await,
        LoaderKind::Quilt => fabric::versions(engine, &fabric::QUILT, game_version).await,
        LoaderKind::Forge => forge::forge_versions(engine, game_version).await,
        LoaderKind::Neoforge => forge::neoforge_versions(engine, game_version).await,
    }
}

/// Install a loader profile; returns `(version id to launch, loader version)`.
pub async fn install(
    engine: &Engine,
    kind: LoaderKind,
    game_version: &str,
    loader_version: Option<&str>,
    ctx: &TaskCtx,
) -> Result<(String, String)> {
    let version = match loader_version.filter(|v| !v.is_empty()) {
        Some(v) => v.to_owned(),
        None => {
            let list = versions(engine, kind, game_version).await?;
            list.iter()
                .find(|v| v.stable)
                .or(list.first())
                .map(|v| v.version.clone())
                .ok_or_else(|| Error::LoaderUnavailable {
                    loader: kind.as_str().into(),
                    version: "latest".into(),
                    game_version: game_version.into(),
                })?
        }
    };
    ctx.stage("stage.loader", Some(format!("{} {version}", kind.as_str())));
    let id = match kind {
        LoaderKind::Vanilla => return Ok((game_version.to_owned(), String::new())),
        LoaderKind::Horizon => {
            return Err(Error::Horizon {
                kind: crate::error::HorizonFailure::NotInstalled,
                detail: "Horizon has no loader profile".into(),
            });
        }
        LoaderKind::Fabric => {
            fabric::install(engine, &fabric::FABRIC, game_version, &version, ctx).await?
        }
        LoaderKind::Quilt => {
            fabric::install(engine, &fabric::QUILT, game_version, &version, ctx).await?
        }
        LoaderKind::Forge | LoaderKind::Neoforge => {
            forge::install(engine, kind, game_version, &version, ctx).await?
        }
    };
    Ok((id, version))
}

/// Fill in SHA-1s that loader metadata leaves out, from the Maven `.sha1` sidecar files.
pub(crate) async fn fill_maven_hashes(http: &Http, libraries: &mut [Library]) {
    let jobs: Vec<(usize, String)> = libraries
        .iter()
        .enumerate()
        .filter(|(_, l)| l.sha1.is_none() && l.downloads.is_none())
        .filter_map(|(i, l)| {
            let base = l.url.as_deref().filter(|u| !u.is_empty())?;
            let path = MavenCoord::parse(&l.name)?.path();
            Some((i, format!("{}.sha1", maven_url(Some(base), &path))))
        })
        .collect();
    let results: Vec<(usize, Option<String>)> = stream::iter(jobs)
        .map(|(i, url)| async move {
            let sha = http.get_bytes(&url).await.ok().and_then(|b| {
                let text = String::from_utf8_lossy(&b).trim().to_owned();
                let h = text.split_whitespace().next()?.to_ascii_lowercase();
                (h.len() == 40 && h.bytes().all(|c| c.is_ascii_hexdigit())).then_some(h)
            });
            (i, sha)
        })
        .buffer_unordered(8)
        .collect()
        .await;
    for (i, sha) in results {
        libraries[i].sha1 = sha;
    }
}

pub(crate) fn safe_version_id(id: &str) -> Result<()> {
    if id.is_empty() || id.contains(['/', '\\', ':']) || id.contains("..") {
        return Err(Error::UnsafePath(id.to_owned()));
    }
    Ok(())
}
