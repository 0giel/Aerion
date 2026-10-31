//! Asset index handling.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::Deserialize;

use crate::error::{IoContext, Result};
use crate::net::download::DownloadItem;
use crate::util::fs::safe_join;

pub const RESOURCES_URL: &str = "https://resources.download.minecraft.net";

#[derive(Debug, Clone, Deserialize)]
pub struct AssetIndex {
    #[serde(default)]
    pub objects: BTreeMap<String, AssetObject>,
    /// Pre-1.7 `legacy` index: objects must be copied to `assets/virtual/<id>`.
    #[serde(default, rename = "virtual")]
    pub is_virtual: bool,
    /// Pre-1.6 `pre-1.6` index: objects go to `<game dir>/resources`.
    #[serde(default)]
    pub map_to_resources: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AssetObject {
    pub hash: String,
    pub size: u64,
}

pub fn object_path(assets_dir: &Path, hash: &str) -> PathBuf {
    assets_dir
        .join("objects")
        .join(&hash[..2.min(hash.len())])
        .join(hash)
}

pub fn object_url(hash: &str) -> String {
    format!("{RESOURCES_URL}/{}/{hash}", &hash[..2.min(hash.len())])
}

impl AssetIndex {
    pub fn download_items(&self, assets_dir: &Path) -> Vec<DownloadItem> {
        self.objects
            .values()
            .filter(|o| o.hash.len() == 40 && o.hash.bytes().all(|b| b.is_ascii_hexdigit()))
            .map(|o| {
                DownloadItem::new(object_url(&o.hash), object_path(assets_dir, &o.hash))
                    .sha1(Some(&o.hash))
                    .size(Some(o.size))
            })
            .collect()
    }

    pub fn total_size(&self) -> u64 {
        self.objects.values().map(|o| o.size).sum()
    }

    /// Copy objects to their legacy, path-based layout (virtual assets / resources dir).
    pub async fn materialize(&self, assets_dir: &Path, target: &Path) -> Result<()> {
        for (name, obj) in &self.objects {
            let dst = safe_join(target, name)?;
            if tokio::fs::metadata(&dst)
                .await
                .map(|m| m.len() == obj.size)
                .unwrap_or(false)
            {
                continue;
            }
            if let Some(parent) = dst.parent() {
                tokio::fs::create_dir_all(parent).await.at(parent)?;
            }
            let src = object_path(assets_dir, &obj.hash);
            tokio::fs::copy(&src, &dst).await.at(&dst)?;
        }
        Ok(())
    }
}
