//! Worlds of an instance (`<game>/saves/<folder>`) and their zip backups
//! (`instances/<id>/.backups/worlds/<folder>__<millis>[_auto].zip`).

use std::path::{Path, PathBuf};

use serde::Serialize;

use crate::error::{Error, IoContext, Result};
use crate::util::archive::{ZipBuilder, extract_zip_prefix};
use crate::util::nbt;

/// Automatic backups kept per world (manual ones are never pruned).
const KEEP_AUTO: usize = 3;

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct WorldInfo {
    /// Folder name inside `saves/` (the identity).
    pub folder: String,
    /// `LevelName` from `level.dat`, or the folder name.
    pub name: String,
    /// Unix seconds.
    pub last_played: i64,
    pub size_bytes: u64,
    pub has_icon: bool,
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct WorldBackup {
    pub file: String,
    pub world: String,
    /// Unix seconds.
    pub created: i64,
    pub size_bytes: u64,
    /// Made before mod updates or a version change.
    pub auto: bool,
}

/// A world folder name from the UI: one plain path segment.
fn check_folder(folder: &str) -> Result<()> {
    if folder.is_empty()
        || folder == "."
        || folder == ".."
        || folder.contains(['/', '\\', '\0'])
        || folder.contains(':')
    {
        return Err(Error::UnsafePath(folder.to_owned()));
    }
    Ok(())
}

fn backups_dir(instance_dir: &Path) -> PathBuf {
    instance_dir.join(".backups").join("worlds")
}

/// The world an instance's `join_world` setting points at, if it still exists.
pub fn resolve_join_world(game_dir: &Path, join_world: Option<&str>) -> Option<String> {
    let wanted = join_world?.trim();
    let worlds = list_worlds(game_dir);
    if wanted == "@last" {
        return worlds
            .into_iter()
            .max_by_key(|w| w.last_played)
            .map(|w| w.folder);
    }
    worlds
        .into_iter()
        .find(|w| w.folder == wanted)
        .map(|w| w.folder)
}

/// Worlds in `saves/`: folders with a `level.dat`.
pub fn count(game_dir: &Path) -> u32 {
    crate::util::fs::count_entries(&game_dir.join("saves"), |e| {
        e.path().join("level.dat").is_file()
    })
}

pub fn list_worlds(game_dir: &Path) -> Vec<WorldInfo> {
    let mut out: Vec<WorldInfo> = std::fs::read_dir(game_dir.join("saves"))
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| {
            let path = e.path();
            let level = path.join("level.dat");
            let meta = level.metadata().ok()?;
            let folder = e.file_name().to_string_lossy().into_owned();
            let data = std::fs::read(&level).ok();
            let tag = data.as_deref().and_then(|d| nbt::read_gzip(d).ok());
            let data_tag = tag.as_ref().and_then(|t| t.get("Data"));
            let name = data_tag
                .and_then(|d| d.get("LevelName"))
                .and_then(nbt::Tag::as_str)
                .filter(|s| !s.trim().is_empty())
                .map(str::to_owned)
                .unwrap_or_else(|| folder.clone());
            let last_played = data_tag
                .and_then(|d| d.get("LastPlayed"))
                .and_then(nbt::Tag::as_i64)
                .map(|ms| ms / 1000)
                .or_else(|| meta.modified().ok().map(crate::util::unix_secs))
                .unwrap_or(0);
            Some(WorldInfo {
                has_icon: path.join("icon.png").is_file(),
                size_bytes: crate::util::fs::dir_size_sync(&path),
                folder,
                name,
                last_played,
            })
        })
        .collect();
    out.sort_by_key(|w| std::cmp::Reverse(w.last_played));
    out
}

fn parse_backup(path: &Path) -> Option<WorldBackup> {
    let file = path.file_name()?.to_str()?.to_owned();
    let stem = file.strip_suffix(".zip")?;
    let (stem, auto) = match stem.strip_suffix("_auto") {
        Some(s) => (s, true),
        None => (stem, false),
    };
    let (world, millis) = stem.rsplit_once("__")?;
    let millis: i64 = millis.parse().ok()?;
    Some(WorldBackup {
        world: world.to_owned(),
        created: millis / 1000,
        size_bytes: path.metadata().ok()?.len(),
        auto,
        file,
    })
}

/// Newest first.
pub fn list_backups(instance_dir: &Path) -> Vec<WorldBackup> {
    let mut out: Vec<WorldBackup> = std::fs::read_dir(backups_dir(instance_dir))
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| parse_backup(&e.path()))
        .collect();
    out.sort_by(|a, b| b.created.cmp(&a.created).then(b.file.cmp(&a.file)));
    out
}

/// Zip one world. Blocking: call from `spawn_blocking`.
pub fn backup_world(
    instance_dir: &Path,
    game_dir: &Path,
    folder: &str,
    auto: bool,
) -> Result<WorldBackup> {
    check_folder(folder)?;
    let src = game_dir.join("saves").join(folder);
    if !src.join("level.dat").is_file() {
        return Err(Error::Invalid(format!("world {folder} not found")));
    }
    let dir = backups_dir(instance_dir);
    std::fs::create_dir_all(&dir).at(&dir)?;
    let millis = crate::util::now_millis();
    let name = format!("{folder}__{millis}{}.zip", if auto { "_auto" } else { "" });
    let path = dir.join(&name);
    let tmp = dir.join(format!("{name}.part"));
    let mut zip = ZipBuilder::create(&tmp)?;
    // `session.lock` is held open by a running game and isn't needed to restore.
    zip.add_dir(folder, &src, &|p: &Path| {
        p.file_name().is_some_and(|n| n == "session.lock")
    })?;
    zip.finish()?;
    std::fs::rename(&tmp, &path).at(&path)?;
    if auto {
        prune_auto(instance_dir, folder);
    }
    parse_backup(&path).ok_or_else(|| Error::Invalid("backup name".into()))
}

fn prune_auto(instance_dir: &Path, folder: &str) {
    let dir = backups_dir(instance_dir);
    for old in list_backups(instance_dir)
        .into_iter()
        .filter(|b| b.auto && b.world == folder)
        .skip(KEEP_AUTO)
    {
        let _ = std::fs::remove_file(dir.join(old.file));
    }
}

/// Automatic backup of every world (before mod updates or a version change).
pub fn backup_all(instance_dir: &Path, game_dir: &Path) -> Result<Vec<WorldBackup>> {
    list_worlds(game_dir)
        .iter()
        .map(|w| backup_world(instance_dir, game_dir, &w.folder, true))
        .collect()
}

/// Restore a backup into `saves/<world>`. The current state of that world is backed up first
/// (automatic backup), so a restore can itself be undone.
pub fn restore(instance_dir: &Path, game_dir: &Path, file: &str) -> Result<String> {
    let path = backups_dir(instance_dir).join(file);
    let backup = parse_backup(&path)
        .filter(|_| path.is_file() && !file.contains(['/', '\\']))
        .ok_or_else(|| Error::Invalid(format!("unknown backup {file}")))?;
    check_folder(&backup.world)?;
    let target = game_dir.join("saves").join(&backup.world);
    if target.join("level.dat").is_file() {
        backup_world(instance_dir, game_dir, &backup.world, true)?;
    }
    if target.exists() {
        std::fs::remove_dir_all(&target).at(&target)?;
    }
    std::fs::create_dir_all(&target).at(&target)?;
    extract_zip_prefix(&path, &format!("{}/", backup.world), &target)?;
    Ok(backup.world)
}

pub fn delete_backup(instance_dir: &Path, file: &str) -> Result<()> {
    if file.contains(['/', '\\']) || !file.ends_with(".zip") {
        return Err(Error::UnsafePath(file.to_owned()));
    }
    let path = backups_dir(instance_dir).join(file);
    std::fs::remove_file(&path).at(&path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn join_world_setting_finds_existing_worlds_only() {
        let dir = tempfile::tempdir().unwrap();
        for name in ["Old", "New"] {
            let world = dir.path().join("saves").join(name);
            std::fs::create_dir_all(&world).unwrap();
            std::fs::write(world.join("level.dat"), b"x").unwrap();
        }
        assert_eq!(resolve_join_world(dir.path(), None), None);
        assert_eq!(
            resolve_join_world(dir.path(), Some("Old")).as_deref(),
            Some("Old")
        );
        assert_eq!(resolve_join_world(dir.path(), Some("Gone")), None);
        assert!(resolve_join_world(dir.path(), Some("@last")).is_some());
    }
    use std::io::Write;

    fn level_dat(name: &str) -> Vec<u8> {
        let root = nbt::Tag::Compound(vec![(
            "Data".into(),
            nbt::Tag::Compound(vec![
                ("LevelName".into(), nbt::Tag::String(name.into())),
                ("LastPlayed".into(), nbt::Tag::Long(1_700_000_000_000)),
            ]),
        )]);
        let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        gz.write_all(&nbt::write(&root)).unwrap();
        gz.finish().unwrap()
    }

    #[test]
    fn list_backup_restore() {
        let dir = tempfile::tempdir().unwrap();
        let (inst, game) = (dir.path().join("inst"), dir.path().join("game"));
        let w = game.join("saves/Świat 1");
        std::fs::create_dir_all(w.join("region")).unwrap();
        std::fs::write(w.join("level.dat"), level_dat("Mój świat")).unwrap();
        std::fs::write(w.join("region/r.0.0.mca"), b"blocks").unwrap();
        std::fs::write(w.join("session.lock"), b"x").unwrap();

        let worlds = list_worlds(&game);
        assert_eq!(worlds.len(), 1);
        assert_eq!(
            (worlds[0].name.as_str(), worlds[0].last_played),
            ("Mój świat", 1_700_000_000)
        );

        let b = backup_world(&inst, &game, "Świat 1", false).unwrap();
        assert!(!b.auto);
        std::fs::write(w.join("region/r.0.0.mca"), b"griefed").unwrap();
        assert_eq!(restore(&inst, &game, &b.file).unwrap(), "Świat 1");
        assert_eq!(
            std::fs::read(w.join("region/r.0.0.mca")).unwrap(),
            b"blocks"
        );
        // The griefed state was saved automatically before restoring.
        assert!(list_backups(&inst).iter().any(|x| x.auto));

        for _ in 0..5 {
            std::thread::sleep(std::time::Duration::from_millis(3));
            backup_all(&inst, &game).unwrap();
        }
        let all = list_backups(&inst);
        assert_eq!(all.iter().filter(|x| x.auto).count(), KEEP_AUTO);
        assert_eq!(all.iter().filter(|x| !x.auto).count(), 1);

        assert!(backup_world(&inst, &game, "../x", false).is_err());
        assert!(restore(&inst, &game, "../../etc.zip").is_err());
        delete_backup(&inst, &b.file).unwrap();
    }
}
