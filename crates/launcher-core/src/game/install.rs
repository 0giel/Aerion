//! Making a version launchable: version JSON, client jar, libraries, natives, assets, log config.

use std::path::PathBuf;

use crate::error::{Error, IoContext, Result};
use crate::mojang::Meta;
use crate::mojang::assets::AssetIndex;
use crate::mojang::library::{LibKind, ResolvedLibrary, resolve_libraries};
use crate::mojang::rules::Environment;
use crate::mojang::version::{Argument, JavaVersionReq, VersionJson};
use crate::net::download::{DownloadItem, VerifyMode};
use crate::progress::TaskCtx;
use crate::util::archive::extract_zip;
use crate::util::fs::{atomic_write, link_or_copy};
use crate::util::hash::sha1_hex;

/// Everything needed to build the launch command of an installed version.
#[derive(Debug, Clone)]
pub struct Prepared {
    pub version: VersionJson,
    /// The vanilla version providing the client jar.
    pub root_id: String,
    pub classpath: Vec<PathBuf>,
    pub natives_dir: PathBuf,
    pub assets_root: PathBuf,
    /// `${game_assets}`: the virtual asset dir for very old versions, else the assets root.
    pub game_assets: PathBuf,
    pub asset_index_id: String,
    /// Pre-1.6 versions read assets from `<game dir>/resources`.
    pub resources_index: Option<AssetIndex>,
    pub logging_arg: Option<String>,
    pub java: JavaVersionReq,
}

pub async fn prepare(
    meta: &Meta,
    env: &Environment,
    version_id: &str,
    mode: VerifyMode,
    ctx: &TaskCtx,
) -> Result<Prepared> {
    let paths = &meta.paths;
    ctx.stage("stage.versionMeta", Some(version_id.to_owned()));
    if mode == VerifyMode::Full {
        meta.verify_version_json(version_id, ctx).await?;
    }
    let (version, root_id) = meta.resolve(version_id, ctx).await?;

    let mut items: Vec<DownloadItem> = Vec::new();

    // Client jar of the vanilla root version.
    let vanilla_jar = paths.client_jar(&root_id);
    items.push(crate::mojang::client_jar_item(paths, &version, &root_id)?);

    // Libraries.
    let libraries = resolve_libraries(&version.libraries, env);
    let libs_dir = paths.libraries_dir();
    let mut missing_local = Vec::new();
    for lib in &libraries {
        let dest = libs_dir.join(&lib.path);
        match &lib.url {
            Some(url) => items.push(
                DownloadItem::new(url, dest)
                    .hash(lib.hash.clone())
                    .size(lib.size),
            ),
            None if !dest.is_file() => missing_local.push(lib.name.clone()),
            None => {}
        }
    }
    if !missing_local.is_empty() {
        return Err(Error::LoaderInstall(format!(
            "missing loader libraries ({}); reinstall the loader",
            missing_local.join(", ")
        )));
    }

    // Assets.
    let assets_root = paths.assets_dir();
    let index_ref = version.asset_index.clone();
    let asset_index_id = version
        .asset_index_id()
        .unwrap_or_else(|| "legacy".to_owned());
    let mut index = None;
    if let Some(r) = &index_ref {
        let index_path = assets_root.join("indexes").join(format!("{}.json", r.id));
        let item = DownloadItem::new(&r.url, &index_path)
            .sha1(Some(&r.sha1))
            .size(Some(r.size));
        meta.downloader.run(vec![item], mode, ctx).await?;
        let bytes = tokio::fs::read(&index_path).await.at(&index_path)?;
        let parsed: AssetIndex = serde_json::from_slice(&bytes)
            .map_err(|e| Error::parse(format!("asset index {}", r.id), e))?;
        items.extend(parsed.download_items(&assets_root));
        index = Some(parsed);
    }

    // Logging configuration.
    let mut logging_arg = None;
    if let Some(cfg) = version.logging.as_ref().and_then(|l| l.client.as_ref())
        && !cfg.file.id.contains(['/', '\\'])
    {
        let path = assets_root.join("log_configs").join(&cfg.file.id);
        items.push(
            DownloadItem::new(&cfg.file.url, &path)
                .sha1(Some(&cfg.file.sha1))
                .size(Some(cfg.file.size)),
        );
        logging_arg = Some(
            cfg.argument
                .replace("${path}", &crate::paths::display_path(&path)),
        );
    }

    ctx.stage("stage.download", Some(version_id.to_owned()));
    meta.downloader.run(items, mode, ctx).await?;

    // Natives: legacy jars are unpacked; modern ones are only classpath jars, but the
    // directory (and 26.x sub-directories) must exist.
    let natives_dir = paths.natives_dir(version_id);
    tokio::fs::create_dir_all(&natives_dir)
        .await
        .at(&natives_dir)?;
    extract_natives(&libraries, &libs_dir, &natives_dir).await?;
    for sub in natives_subdirs(&version) {
        let d = natives_dir.join(sub);
        tokio::fs::create_dir_all(&d).await.at(&d)?;
    }

    // Old asset layouts.
    let mut game_assets = assets_root.clone();
    let mut resources_index = None;
    if let Some(idx) = index {
        if idx.is_virtual {
            ctx.stage("stage.assetsLegacy", None);
            game_assets = assets_root.join("virtual").join(&asset_index_id);
            idx.materialize(&assets_root, &game_assets).await?;
        }
        if idx.map_to_resources {
            resources_index = Some(idx);
        }
    }

    // Loader versions get their own copy of the client jar named after the version, as the
    // official launcher does; modern Forge's `-DignoreList=...,${version_name}.jar` relies on it.
    let jar = if version.id == root_id {
        vanilla_jar
    } else {
        let own = paths.client_jar(&version.id);
        let fresh = match (
            tokio::fs::metadata(&own).await,
            tokio::fs::metadata(&vanilla_jar).await,
        ) {
            (Ok(a), Ok(b)) => a.len() == b.len(),
            _ => false,
        };
        if !fresh {
            link_or_copy(&vanilla_jar, &own).await?;
        }
        own
    };

    let mut classpath: Vec<PathBuf> = Vec::new();
    for lib in libraries.iter().filter(|l| l.kind == LibKind::Classpath) {
        let p = libs_dir.join(&lib.path);
        if !classpath.contains(&p) {
            classpath.push(p);
        }
    }
    classpath.push(jar);

    Ok(Prepared {
        java: version.java_requirement(),
        version,
        root_id,
        classpath,
        natives_dir,
        assets_root,
        game_assets,
        asset_index_id,
        resources_index,
        logging_arg,
    })
}

/// Sub-directories referenced as `${natives_directory}/<name>` (26.x: java, jna, lwjgl, netty).
fn natives_subdirs(version: &VersionJson) -> Vec<String> {
    let mut out = Vec::new();
    let Some(args) = &version.arguments else {
        return out;
    };
    for arg in &args.jvm {
        let values: Vec<&String> = match arg {
            Argument::Plain(s) => vec![s],
            Argument::Conditional { value, .. } => value.values().iter().collect(),
        };
        for v in values {
            if let Some(rest) = v.split("${natives_directory}/").nth(1) {
                let name: String = rest
                    .chars()
                    .take_while(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
                    .collect();
                if !name.is_empty() && !out.contains(&name) {
                    out.push(name);
                }
            }
        }
    }
    out
}

async fn extract_natives(
    libraries: &[ResolvedLibrary],
    libs_dir: &std::path::Path,
    natives_dir: &std::path::Path,
) -> Result<()> {
    let natives: Vec<(PathBuf, Vec<String>)> = libraries
        .iter()
        .filter_map(|l| match &l.kind {
            LibKind::Native { exclude } => Some((libs_dir.join(&l.path), exclude.clone())),
            LibKind::Classpath => None,
        })
        .collect();
    if natives.is_empty() {
        return Ok(());
    }
    let signature = sha1_hex(format!("{natives:?}").as_bytes());
    let marker = natives_dir.join(".luncher-natives");
    if tokio::fs::read_to_string(&marker).await.ok().as_deref() == Some(signature.as_str()) {
        return Ok(());
    }
    let dest = natives_dir.to_path_buf();
    crate::util::blocking(move || -> Result<()> {
        for (jar, exclude) in natives {
            extract_zip(&jar, &dest, &|name: &str| {
                !name.ends_with('/') && !exclude.iter().any(|e| name.starts_with(e.as_str()))
            })?;
        }
        Ok(())
    })
    .await?;
    atomic_write(&marker, signature).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_26x_native_subdirs() {
        let path = format!(
            "{}/tests/fixtures/versions/26.3.json",
            env!("CARGO_MANIFEST_DIR")
        );
        let v: VersionJson = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        assert_eq!(natives_subdirs(&v), ["java", "jna", "lwjgl", "netty"]);
        let path = format!(
            "{}/tests/fixtures/versions/1.20.1.json",
            env!("CARGO_MANIFEST_DIR")
        );
        let v: VersionJson = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        assert!(natives_subdirs(&v).is_empty());
    }
}
