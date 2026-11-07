//! Minecraft textures used as instance icons. Nothing from the game ships with the launcher:
//! textures are read at run time from a client jar downloaded from Mojang (any installed version
//! with the 1.13+ texture layout, otherwise the latest release's jar is fetched) and cached.

use std::path::{Path, PathBuf};

use tokio::sync::Mutex;

use crate::engine::Engine;
use crate::error::{Error, IoContext, Result};
use crate::net::download::VerifyMode;
use crate::progress::TaskCtx;
use crate::util::archive::read_zip_entry;
use crate::util::image::png_size;

/// Present in every client jar with the modern (`item/`, `block/`) layout.
const PROBE: &str = "assets/minecraft/textures/item/diamond.png";

/// `item/diamond`, `block/oak_log`: only item and block textures, plain names.
pub fn valid_texture(name: &str) -> bool {
    name.split_once('/').is_some_and(|(kind, rest)| {
        matches!(kind, "item" | "block")
            && (1..=64).contains(&rest.len())
            && rest
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
    })
}

/// Newest client jar in `versions/` that has the modern texture layout.
fn find_jar(versions: &Path) -> Option<PathBuf> {
    let mut jars: Vec<(std::time::SystemTime, PathBuf)> = std::fs::read_dir(versions)
        .ok()?
        .flatten()
        .filter_map(|e| {
            let id = e.file_name().to_string_lossy().into_owned();
            let jar = e.path().join(format!("{id}.jar"));
            let modified = jar.metadata().ok()?.modified().ok()?;
            Some((modified, jar))
        })
        .collect();
    jars.sort_by_key(|j| std::cmp::Reverse(j.0));
    jars.into_iter()
        .map(|(_, jar)| jar)
        .find(|jar| matches!(read_zip_entry(jar, PROBE), Ok(Some(_))))
}

#[derive(Default)]
pub struct Textures {
    /// The jar textures are read from, once found or downloaded.
    jar: Mutex<Option<PathBuf>>,
}

impl Textures {
    /// PNG bytes of a texture (cached under `cache/textures`).
    pub async fn get(&self, engine: &Engine, name: &str) -> Result<Vec<u8>> {
        if !valid_texture(name) {
            return Err(Error::Invalid(format!("invalid texture {name:?}")));
        }
        let cached = engine.paths.texture_cache_dir().join(format!("{name}.png"));
        if let Ok(bytes) = tokio::fs::read(&cached).await {
            return Ok(bytes);
        }
        let jar = self.jar(engine).await?;
        let entry = format!("assets/minecraft/textures/{name}.png");
        let bytes = crate::util::blocking(move || read_zip_entry(&jar, &entry))
            .await?
            .filter(|b| png_size(b).is_some())
            .ok_or_else(|| Error::Invalid(format!("texture {name} not found")))?;
        if let Some(dir) = cached.parent() {
            tokio::fs::create_dir_all(dir).await.at(dir)?;
        }
        tokio::fs::write(&cached, &bytes).await.at(&cached)?;
        Ok(bytes)
    }

    /// An installed client jar, or the latest release's jar downloaded from Mojang. Concurrent
    /// callers wait for one search/download.
    async fn jar(&self, engine: &Engine) -> Result<PathBuf> {
        let mut jar = self.jar.lock().await;
        if let Some(j) = jar.as_ref().filter(|j| j.is_file()) {
            return Ok(j.clone());
        }
        let versions = engine.paths.versions_dir();
        let found = crate::util::blocking(move || Ok(find_jar(&versions))).await?;
        let path = match found {
            Some(p) => p,
            None => {
                let ctx = TaskCtx::new();
                let id = engine.meta.manifest().await?.latest.release;
                let version = engine.meta.version_json(&id, &ctx).await?;
                let item = crate::mojang::client_jar_item(&engine.paths, &version, &id)?;
                engine
                    .downloader
                    .run(vec![item], VerifyMode::Fast, &ctx)
                    .await?;
                engine.paths.client_jar(&id)
            }
        };
        *jar = Some(path.clone());
        Ok(path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn texture_names() {
        assert!(valid_texture("item/diamond_sword"));
        assert!(valid_texture("block/grass_block_side"));
        assert!(!valid_texture("entity/creeper"));
        assert!(!valid_texture("item/../../x"));
        assert!(!valid_texture("item/"));
        assert!(!valid_texture("item/Diamond"));
    }

    #[test]
    fn picks_the_newest_modern_jar() {
        let dir = tempfile::tempdir().unwrap();
        let jar = |id: &str, entry: &str| {
            let d = dir.path().join(id);
            std::fs::create_dir_all(&d).unwrap();
            let f = std::fs::File::create(d.join(format!("{id}.jar"))).unwrap();
            let mut z = zip::ZipWriter::new(f);
            z.start_file(entry, zip::write::SimpleFileOptions::default())
                .unwrap();
            std::io::Write::write_all(&mut z, b"png").unwrap();
            z.finish().unwrap();
        };
        assert_eq!(find_jar(dir.path()), None);
        jar("1.21.11", PROBE);
        jar("1.8.9", "assets/minecraft/textures/items/diamond.png");
        let newer = std::time::SystemTime::now() + std::time::Duration::from_secs(60);
        std::fs::File::options()
            .write(true)
            .open(dir.path().join("1.8.9/1.8.9.jar"))
            .unwrap()
            .set_modified(newer)
            .unwrap();
        assert_eq!(
            find_jar(dir.path()),
            Some(dir.path().join("1.21.11/1.21.11.jar")),
            "old layout skipped even when newer"
        );
    }
}
