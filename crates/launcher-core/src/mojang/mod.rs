//! Mojang metadata: manifest, version JSONs (with inheritance), libraries, assets, arguments.

pub mod args;
pub mod assets;
pub mod library;
pub mod manifest;
pub mod rules;
pub mod version;

use crate::error::{Error, Result};
use crate::net::cache::{HttpCache, MINUTE};
use crate::net::download::{DownloadItem, VerifyMode};
use crate::paths::Paths;
use crate::progress::TaskCtx;
use crate::util::fs::read_json;
use crate::util::hash::verify_file;

use manifest::{MANIFEST_URL, VersionManifest};
use version::VersionJson;

/// Access to Mojang metadata with on-disk caching.
#[derive(Clone)]
pub struct Meta {
    pub paths: Paths,
    pub cache: HttpCache,
    pub downloader: crate::net::download::Downloader,
}

impl Meta {
    pub async fn manifest(&self) -> Result<VersionManifest> {
        self.cache.get_json(MANIFEST_URL, 10 * MINUTE).await
    }

    /// Load a version JSON: local copy first (loader profiles live only there), otherwise
    /// download it from the manifest with SHA-1 verification.
    pub async fn version_json(&self, id: &str, ctx: &TaskCtx) -> Result<VersionJson> {
        let path = self.paths.version_json(id);
        if let Ok(Some(v)) = read_json::<VersionJson>(&path).await {
            return Ok(v);
        }
        let manifest = self.manifest().await?;
        let entry = manifest
            .get(id)
            .ok_or_else(|| Error::VersionNotFound(id.to_owned()))?;
        let item = DownloadItem::new(&entry.url, &path).sha1(entry.sha1.as_deref());
        self.downloader.fetch(&item, ctx).await?;
        read_json::<VersionJson>(&path)
            .await?
            .ok_or_else(|| Error::VersionNotFound(id.to_owned()))
    }

    /// Re-download a vanilla version JSON if it fails its checksum (used by "Verify files").
    pub async fn verify_version_json(&self, id: &str, ctx: &TaskCtx) -> Result<()> {
        let manifest = self.manifest().await?;
        if let Some(entry) = manifest.get(id) {
            let path = self.paths.version_json(id);
            let item = DownloadItem::new(&entry.url, &path).sha1(entry.sha1.as_deref());
            if let Some(hash) = &item.hash
                && !verify_file(&path, hash).await.unwrap_or(false)
            {
                self.downloader
                    .run(vec![item], VerifyMode::Full, ctx)
                    .await?;
            }
        }
        Ok(())
    }

    /// Fully resolved version (all `inheritsFrom` parents merged) plus the id of the
    /// root vanilla version that provides the client jar.
    pub async fn resolve(&self, id: &str, ctx: &TaskCtx) -> Result<(VersionJson, String)> {
        let mut chain = vec![self.version_json(id, ctx).await?];
        while let Some(parent) = chain.last().and_then(|v| v.inherits_from.clone()) {
            if chain.len() > 8 || chain.iter().any(|v| v.id == parent) {
                return Err(Error::Invalid(format!("inheritsFrom loop at {parent}")));
            }
            chain.push(self.version_json(&parent, ctx).await?);
        }
        let root_id = chain.last().map(|v| v.id.clone()).unwrap_or_default();
        let mut merged = chain
            .pop()
            .ok_or_else(|| Error::VersionNotFound(id.to_owned()))?;
        while let Some(child) = chain.pop() {
            merged = version::merge(child, merged);
        }
        Ok((merged, root_id))
    }
}
