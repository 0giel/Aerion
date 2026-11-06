//! One file with the launcher's settings, themes, skins, in-game HUD and shared game settings,
//! e.g. to move to a new computer. Accounts and tokens never go in it (they live in the keyring).

use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::aerion_mod::hud::HudConfig;
use crate::error::{Error, Result};
use crate::paths::Paths;
use crate::settings::Settings;
use crate::util::fs::{atomic_write_sync, safe_join};

const MANIFEST: &str = "aerion-settings.json";
const SETTINGS: &str = "settings.json";
const HUD: &str = "aerion-mod/hud.json";
const FORMAT: u32 = 1;
/// A single file in the archive (a theme background GIF is the biggest thing expected).
const MAX_ENTRY: u64 = 64 * 1024 * 1024;
const MAX_TOTAL: u64 = 512 * 1024 * 1024;

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Manifest {
    format: u32,
    app_version: String,
    created: i64,
}

/// What an export or import moved.
#[derive(Debug, Clone, Default, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct TransferSummary {
    pub files: u32,
    pub themes: u32,
    pub skins: u32,
    pub hud: bool,
    pub game_options: bool,
}

/// Folders copied whole, as `(prefix in the archive, folder on disk)`.
fn folders(paths: &Paths) -> [(&'static str, PathBuf); 4] {
    [
        ("themes/", paths.themes_dir()),
        ("skins/", paths.skins_dir()),
        ("shared/game-options/", paths.shared_options_dir()),
        ("templates/game-options/", paths.options_template_dir()),
    ]
}

/// Settings that belong to this computer stay out of the file (and are kept on import).
fn portable(mut s: Settings) -> Settings {
    let d = Settings::default();
    s.data_dir = None;
    s.java_overrides = d.java_overrides;
    s.client_id = String::new();
    s.selected_account = None;
    s.last_instance = None;
    s.last_seen_version = None;
    s
}

fn keep_local(mut imported: Settings, current: &Settings) -> Settings {
    imported.data_dir = current.data_dir.clone();
    imported.java_overrides = current.java_overrides.clone();
    imported.client_id = current.client_id.clone();
    imported.selected_account = current.selected_account.clone();
    imported.last_instance = current.last_instance.clone();
    imported.last_seen_version = current.last_seen_version.clone();
    imported.onboarding_done = true;
    imported
}

fn files_in(dir: &Path, out: &mut Vec<(String, PathBuf)>, prefix: &str) {
    let Ok(rd) = std::fs::read_dir(dir) else {
        return;
    };
    for e in rd.flatten() {
        let Some(name) = e.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let Ok(ft) = e.file_type() else { continue };
        if ft.is_dir() {
            files_in(&e.path(), out, &format!("{prefix}{name}/"));
        } else if ft.is_file() && !name.ends_with(".tmp") {
            out.push((format!("{prefix}{name}"), e.path()));
        }
    }
}

fn inv(e: impl std::fmt::Display) -> Error {
    Error::Invalid(e.to_string())
}

/// Write everything to `dest` (a zip file).
pub fn export(paths: &Paths, settings: &Settings, dest: &Path) -> Result<TransferSummary> {
    let mut entries = Vec::new();
    for (prefix, dir) in folders(paths) {
        files_in(&dir, &mut entries, prefix);
    }
    let hud = crate::aerion_mod::hud::file(paths);
    if hud.is_file() {
        entries.push((HUD.to_owned(), hud));
    }

    let mut buf = std::io::Cursor::new(Vec::new());
    let mut zip = zip::ZipWriter::new(&mut buf);
    let opts = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    let manifest = Manifest {
        format: FORMAT,
        app_version: env!("CARGO_PKG_VERSION").to_owned(),
        created: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0),
    };
    zip.start_file(MANIFEST, opts)?;
    zip.write_all(&serde_json::to_vec_pretty(&manifest).map_err(inv)?)
        .map_err(inv)?;
    zip.start_file(SETTINGS, opts)?;
    zip.write_all(&serde_json::to_vec_pretty(&portable(settings.clone())).map_err(inv)?)
        .map_err(inv)?;

    let mut summary = TransferSummary::default();
    for (name, path) in entries {
        let Ok(bytes) = std::fs::read(&path) else {
            continue;
        };
        zip.start_file(name.as_str(), opts)?;
        zip.write_all(&bytes).map_err(inv)?;
        count(&mut summary, &name);
    }
    zip.finish()?;
    atomic_write_sync(dest, buf.get_ref())?;
    Ok(summary)
}

fn count(summary: &mut TransferSummary, name: &str) {
    summary.files += 1;
    if name.starts_with("themes/") && !name.contains("/assets/") && name.ends_with(".json") {
        if !name.ends_with("theme.schema.json") {
            summary.themes += 1;
        }
    } else if name.starts_with("skins/") && name.ends_with(".png") {
        summary.skins += 1;
    } else if name == HUD {
        summary.hud = true;
    } else if name.starts_with("shared/game-options/") {
        summary.game_options = true;
    }
}

/// Read a file made by [`export`]: files are added or replaced (nothing is deleted) and the
/// returned settings are the file's, with this computer's own values kept. The caller saves them.
pub fn import(
    paths: &Paths,
    src: &Path,
    current: &Settings,
) -> Result<(Settings, TransferSummary)> {
    let bad = |why: &str| Error::Invalid(format!("{}: {why}", src.display()));
    let mut zip = zip::ZipArchive::new(std::fs::File::open(src).map_err(|e| bad(&e.to_string()))?)
        .map_err(|_| bad("not an Aerion settings file"))?;

    let read = |zip: &mut zip::ZipArchive<std::fs::File>, i: usize| -> Result<(String, Vec<u8>)> {
        let f = zip.by_index(i)?;
        let name = f.name()?.into_owned();
        if f.size() > MAX_ENTRY {
            return Err(bad("a file in it is too big"));
        }
        let mut bytes = Vec::with_capacity(f.size() as usize);
        f.take(MAX_ENTRY + 1).read_to_end(&mut bytes).map_err(inv)?;
        if bytes.len() as u64 > MAX_ENTRY {
            return Err(bad("a file in it is too big"));
        }
        Ok((name, bytes))
    };

    // Everything is read and checked before anything on disk changes.
    let mut manifest = None;
    let mut settings = None;
    let mut files: Vec<(PathBuf, String, Vec<u8>)> = Vec::new();
    let mut total = 0u64;
    let folders = folders(paths);
    for i in 0..zip.len() {
        let (name, bytes) = read(&mut zip, i)?;
        total += bytes.len() as u64;
        if total > MAX_TOTAL {
            return Err(bad("too big"));
        }
        if name.ends_with('/') {
            continue;
        }
        match name.as_str() {
            MANIFEST => {
                manifest = Some(
                    serde_json::from_slice::<Manifest>(&bytes)
                        .map_err(|_| bad("not an Aerion settings file"))?,
                )
            }
            SETTINGS => {
                settings = Some(
                    serde_json::from_slice::<Settings>(&bytes)
                        .map_err(|e| bad(&format!("settings: {e}")))?,
                )
            }
            HUD => {
                // Only a HUD the game can read.
                let ok = serde_json::from_slice::<HudConfig>(&bytes)
                    .ok()
                    .is_some_and(|h| h.validate().is_ok());
                if ok {
                    files.push((crate::aerion_mod::hud::file(paths), name, bytes));
                }
            }
            _ => {
                let Some((prefix, dir)) = folders.iter().find(|(p, _)| name.starts_with(p)) else {
                    continue;
                };
                let path = safe_join(dir, &name[prefix.len()..])?;
                files.push((path, name, bytes));
            }
        }
    }
    let manifest = manifest.ok_or_else(|| bad("not an Aerion settings file"))?;
    if manifest.format > FORMAT {
        return Err(bad("made by a newer Aerion, update the launcher first"));
    }
    let settings = keep_local(settings.ok_or_else(|| bad("no settings in it"))?, current);
    settings.validate()?;

    let mut summary = TransferSummary::default();
    for (path, name, bytes) in files {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| Error::Invalid(format!("{}: {e}", parent.display())))?;
        }
        atomic_write_sync(&path, &bytes)?;
        count(&mut summary, &name);
    }
    Ok((settings, summary))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn paths(dir: &Path) -> Paths {
        Paths::at(dir.to_path_buf(), false)
    }

    #[test]
    fn moves_settings_and_files_but_not_this_computers_values() {
        let a = tempfile::tempdir().unwrap();
        let pa = paths(a.path());
        std::fs::create_dir_all(pa.themes_dir().join("assets")).unwrap();
        std::fs::write(pa.themes_dir().join("mine.json"), b"{}").unwrap();
        std::fs::write(pa.themes_dir().join("assets/bg.gif"), b"GIF89a").unwrap();
        std::fs::create_dir_all(pa.shared_options_dir()).unwrap();
        std::fs::write(pa.shared_options_dir().join("options.txt"), b"fov:1.0\n").unwrap();
        let mut s = Settings {
            theme: "mine".into(),
            client_id: "secret-ish".into(),
            data_dir: Some("/old/disk".into()),
            ..Settings::default()
        };
        s.java_overrides.insert("21".into(), "/old/java".into());
        let file = a.path().join("out.zip");
        let sum = export(&pa, &s, &file).unwrap();
        assert_eq!((sum.themes, sum.game_options), (1, true));

        let b = tempfile::tempdir().unwrap();
        let pb = paths(b.path());
        let here = Settings {
            client_id: "new-pc".into(),
            ..Settings::default()
        };
        let (got, sum) = import(&pb, &file, &here).unwrap();
        assert_eq!(got.theme, "mine");
        assert_eq!(got.client_id, "new-pc");
        assert_eq!(got.data_dir, None);
        assert!(got.java_overrides.is_empty());
        assert!(got.onboarding_done);
        assert_eq!(sum.files, 3);
        assert_eq!(
            std::fs::read(pb.themes_dir().join("assets/bg.gif")).unwrap(),
            b"GIF89a"
        );
        assert_eq!(
            std::fs::read(pb.shared_options_dir().join("options.txt")).unwrap(),
            b"fov:1.0\n"
        );
    }

    #[test]
    fn refuses_other_zips_and_paths_outside() {
        let d = tempfile::tempdir().unwrap();
        let p = paths(d.path());
        let write = |name: &str, entries: &[(&str, &[u8])]| {
            let path = d.path().join(name);
            let mut z = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
            for (n, b) in entries {
                z.start_file(*n, zip::write::SimpleFileOptions::default())
                    .unwrap();
                z.write_all(b).unwrap();
            }
            z.finish().unwrap();
            path
        };
        let plain = write("a.zip", &[("readme.txt", b"hi")]);
        assert!(import(&p, &plain, &Settings::default()).is_err());

        let manifest = br#"{"format":1,"appVersion":"1.0.0","created":0}"#;
        let evil = write(
            "b.zip",
            &[
                (MANIFEST, manifest),
                (SETTINGS, b"{}"),
                ("themes/../../evil.txt", b"x"),
            ],
        );
        assert!(import(&p, &evil, &Settings::default()).is_err());
        assert!(!d.path().join("evil.txt").exists());

        let newer = write(
            "c.zip",
            &[
                (MANIFEST, br#"{"format":99,"appVersion":"9","created":0}"#),
                (SETTINGS, b"{}"),
            ],
        );
        assert!(import(&p, &newer, &Settings::default()).is_err());
    }
}
