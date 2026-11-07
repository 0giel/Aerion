//! Aerion's own archive: the whole instance (settings, content manifest, game directory).

use std::path::Path;

use crate::error::{Error, Result};
use crate::instances::{Instance, InstanceManager, NewInstance};
use crate::modpacks::{excluded_from_export, pack_instance};
use crate::util::archive::{ZipBuilder, extract_zip_prefix, read_zip_entry};
use crate::util::fs::write_json;

pub const MARKER: &str = "aerion-instance.json";
/// Archives exported before the launcher was renamed from "Luncher".
const LEGACY_MARKER: &str = "luncher-instance.json";

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
    crate::util::blocking(move || -> Result<()> {
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
}

pub async fn import(instances: &InstanceManager, archive: &Path) -> Result<Instance> {
    let a = archive.to_path_buf();
    let src: Instance = crate::util::blocking(move || -> Result<Instance> {
        let bytes =
            read_marker(&a)?.ok_or_else(|| Error::Invalid("not an Aerion archive".into()))?;
        serde_json::from_slice(&bytes).map_err(|e| Error::parse(MARKER, e))
    })
    .await?;
    let spec = NewInstance {
        icon: src.icon.clone(),
        group: src.group.clone(),
        ..pack_instance(
            src.name.clone(),
            src.game_version.clone(),
            src.loader,
            src.loader_version.clone(),
        )
    };
    instances
        .create_with(spec, |created| async move {
            let id = created.id.clone();
            let dir = instances.dir(&id)?;
            let (a, g, d) = (archive.to_path_buf(), instances.game_dir(&id)?, dir.clone());
            crate::util::blocking(move || -> Result<()> {
                extract_zip_prefix(&a, "minecraft/", &g)?;
                if let Some(c) = read_zip_entry(&a, "content.json")? {
                    crate::util::fs::atomic_write_sync(&d.join("content.json"), &c)?;
                }
                Ok(())
            })
            .await?;
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
        })
        .await
}
