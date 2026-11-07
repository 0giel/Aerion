//! Fabric and Quilt: profiles come ready-made from their meta servers.

use serde::Deserialize;

use crate::Engine;
use crate::error::{Error, Result};
use crate::loaders::LoaderKind;
use crate::loaders::{LoaderVersion, fill_maven_hashes, is_prerelease, safe_version_id};
use crate::mojang::version::VersionJson;
use crate::net::cache::MINUTE;
use crate::progress::TaskCtx;
use crate::util::fs::write_json;

pub struct Flavor {
    pub kind: LoaderKind,
    pub meta: &'static str,
}

pub const FABRIC: Flavor = Flavor {
    kind: LoaderKind::Fabric,
    meta: "https://meta.fabricmc.net/v2",
};
pub const QUILT: Flavor = Flavor {
    kind: LoaderKind::Quilt,
    meta: "https://meta.quiltmc.org/v3",
};

#[derive(Deserialize)]
struct Entry {
    loader: Info,
}

#[derive(Deserialize)]
struct Info {
    version: String,
    #[serde(default)]
    stable: Option<bool>,
}

#[derive(Deserialize)]
struct GameEntry {
    version: String,
}

fn not_available(flavor: &Flavor, version: &str, game: &str) -> Error {
    Error::LoaderUnavailable {
        loader: flavor.kind.as_str().into(),
        version: version.into(),
        game_version: game.into(),
    }
}

pub async fn versions(engine: &Engine, flavor: &Flavor, game: &str) -> Result<Vec<LoaderVersion>> {
    let url = format!("{}/versions/loader/{game}", flavor.meta);
    let list: Vec<Entry> = match engine.cache.get_json(&url, 30 * MINUTE).await {
        Ok(l) => l,
        Err(Error::Http {
            status: 400 | 404, ..
        }) => vec![],
        Err(e) => return Err(e),
    };
    Ok(list
        .into_iter()
        .map(|e| LoaderVersion {
            stable: e
                .loader
                .stable
                .unwrap_or_else(|| !is_prerelease(&e.loader.version)),
            version: e.loader.version,
        })
        .collect())
}

/// Game versions the loader supports (for the instance wizard).
pub async fn game_versions(engine: &Engine, flavor: &Flavor) -> Result<Vec<String>> {
    let url = format!("{}/versions/game", flavor.meta);
    let list: Vec<GameEntry> = engine.cache.get_json(&url, 30 * MINUTE).await?;
    Ok(list.into_iter().map(|g| g.version).collect())
}

pub async fn install(
    engine: &Engine,
    flavor: &Flavor,
    game: &str,
    loader: &str,
    ctx: &TaskCtx,
) -> Result<String> {
    ctx.check()?;
    let url = format!(
        "{}/versions/loader/{game}/{loader}/profile/json",
        flavor.meta
    );
    let resp = engine.http.send(|c| c.get(&url)).await?;
    if matches!(resp.status().as_u16(), 400 | 404) {
        return Err(not_available(flavor, loader, game));
    }
    let mut profile: VersionJson = crate::net::client::json(resp).await?;
    safe_version_id(&profile.id)?;
    if profile.inherits_from.as_deref() != Some(game) {
        return Err(Error::Invalid(format!(
            "unexpected profile parent {:?}",
            profile.inherits_from
        )));
    }
    fill_maven_hashes(&engine.http, &mut profile.libraries).await;
    write_json(engine.paths.version_json(&profile.id), &profile).await?;
    Ok(profile.id)
}
