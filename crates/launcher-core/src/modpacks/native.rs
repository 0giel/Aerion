//! Aerion's own archive: the whole instance (settings, content manifest, game directory).

use std::path::Path;

use crate::error::{Error, Result};
use crate::instances::{Instance, InstanceManager, NewInstance};
use crate::modpacks::excluded_from_export;
use crate::util::archive::{ZipBuilder, extract_zip_prefix, read_zip_entry};
use crate::util::fs::write_json;

pub const MARKER: &str = "aerion-instance.json";
/// Archives exported before the launcher was renamed from "Luncher".
pub const LEGACY_MARKER: &str = "luncher-instance.json";

/// The instance settings stored in an archive, under the current or the legacy name.
pub fn read_marker(archive: &Path) -> Result<Option<Vec<u8>>> {
    match read_zip_entry(archive, MARKER)? {
        Some(b) => Ok(Some(b)),
        None => read_zip_entry(archive, LEGACY_MARKER),
    }
}

pub async fn export(instances: &InstanceManager, id: &str, dest: &Path) -> Result<()> {
    let inst = instances.get(id).await?;
    let dir = instances.dir(id)?;
    let game_dir = instances.game_dir(id)?;
    let json = serde_json::to_vec_pretty(&inst).map_err(|e| Error::parse("instance", e))?;
    let dest = dest.to_path_buf();
    tokio::task::spawn_blocking(move || -> Result<()> {
        let mut zip = ZipBuilder::create(&dest)?;
        zip.add_bytes(MARKER, &json)?;
        let content = dir.join("content.json");
        if content.is_file() {
            zip.add_file("content.json", &content)?;
        }
        if game_dir.is_dir() {
            let root = game_dir.clone();
            zip.add_dir("minecraft", &game_dir, &move |p: &Path| {
                p.strip_prefix(&root)
                    .map(|r| excluded_from_export(&r.to_string_lossy().replace('\\', "/")))
                    .unwrap_or(false)
            })?;
        }
        zip.finish()
    })
    .await
    .map_err(|e| Error::Invalid(e.to_string()))?
}

pub async fn import(instances: &InstanceManager, archive: &Path) -> Result<Instance> {
    let a = archive.to_path_buf();
    let src: Instance = tokio::task::spawn_blocking(move || -> Result<Instance> {
        let bytes =
            read_marker(&a)?.ok_or_else(|| Error::Invalid("not an Aerion archive".into()))?;
        serde_json::from_slice(&bytes).map_err(|e| Error::parse(MARKER, e))
    })
    .await
    .map_err(|e| Error::Invalid(e.to_string()))??;
    let created = instances
        .create(NewInstance {
            name: src.name.clone(),
            game_version: src.game_version.clone(),
            loader: src.loader,
            loader_version: src.loader_version.clone(),
            icon: src.icon.clone(),
            group: src.group.clone(),
            game_dir: None,
            link_existing: false,
        })
        .await?;
    let id = created.id.clone();
    let dir = instances.dir(&id)?;
    let (a, g, d) = (archive.to_path_buf(), instances.game_dir(&id)?, dir.clone());
    let res = tokio::task::spawn_blocking(move || -> Result<()> {
        extract_zip_prefix(&a, "minecraft/", &g)?;
        if let Some(c) = read_zip_entry(&a, "content.json")? {
            crate::util::fs::atomic_write_sync(&d.join("content.json"), &c)?;
        }
        Ok(())
    })
    .await
    .map_err(|e| Error::Invalid(e.to_string()))?;
    if let Err(e) = res {
        let _ = instances.delete(&id).await;
        return Err(e);
    }
    // Keep the user's settings but not identity/statistics from another machine.
    let restored = Instance {
        id: id.clone(),
        created_at: created.created_at,
        last_played: None,
        play_time_secs: 0,
        version_id: None,
        java_path: None,
        ..src
    };
    write_json(dir.join("instance.json"), &restored).await?;
    instances.get(&id).await
}
