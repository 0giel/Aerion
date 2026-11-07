//! In-game settings (controls, video, sound) shared between instances: copy them from one
//! instance to others, or keep a template that new instances start with.

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::{Error, IoContext, Result};

/// Vanilla options, OptiFine's and the shader settings.
const FILES: [&str; 3] = ["options.txt", "optionsof.txt", "optionsshaders.txt"];
const META: &str = "template.json";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct OptionsTemplate {
    /// Name of the instance it was saved from.
    pub source: String,
    /// Unix seconds.
    pub saved_at: i64,
}

/// Copy whichever option files exist. Returns how many were copied.
pub fn copy(from_game: &Path, to_game: &Path) -> Result<usize> {
    if from_game == to_game {
        return Ok(0);
    }
    std::fs::create_dir_all(to_game).at(to_game)?;
    let mut n = 0;
    for f in FILES {
        let src = from_game.join(f);
        if src.is_file() {
            let dst = to_game.join(f);
            std::fs::copy(&src, &dst).at(&dst)?;
            n += 1;
        }
    }
    Ok(n)
}

pub fn save_template(
    from_game: &Path,
    template_dir: &Path,
    source: &str,
) -> Result<OptionsTemplate> {
    if !from_game.join("options.txt").is_file() {
        return Err(Error::Invalid(
            "this instance has no game settings yet — start it once first".into(),
        ));
    }
    if template_dir.exists() {
        std::fs::remove_dir_all(template_dir).at(template_dir)?;
    }
    copy(from_game, template_dir)?;
    let meta = OptionsTemplate {
        source: source.to_owned(),
        saved_at: crate::util::now_secs(),
    };
    let path = template_dir.join(META);
    std::fs::write(&path, serde_json::to_vec(&meta).unwrap_or_default()).at(&path)?;
    Ok(meta)
}

pub fn template(template_dir: &Path) -> Option<OptionsTemplate> {
    let bytes = std::fs::read(template_dir.join(META)).ok()?;
    serde_json::from_slice(&bytes).ok()
}

pub fn clear_template(template_dir: &Path) -> Result<()> {
    match std::fs::remove_dir_all(template_dir) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(Error::io(template_dir, e)),
        _ => Ok(()),
    }
}

/// Give a new instance the template's settings (never overwrites existing ones).
pub fn apply_template(template_dir: &Path, to_game: &Path) -> Result<bool> {
    if template(template_dir).is_none() || to_game.join("options.txt").exists() {
        return Ok(false);
    }
    Ok(copy(template_dir, to_game)? > 0)
}

// --- shared settings ---------------------------------------------------------------------------

/// Keys that stay with each instance: its own packs, servers, onboarding and the file's version.
const LOCAL: &[&str] = &[
    "version",
    "resourcePacks",
    "incompatibleResourcePacks",
    "lastServer",
    "tutorialStep",
    "joinedFirstServer",
    "onboardAccessibility",
    "skipMultiplayerWarning",
    "skipRealms32bitWarning",
    "hideBundleTutorial",
];

/// From this data version (1.13) key bindings are names (`key.keyboard.w`); older games store
/// numbers, so bindings are only shared between files at or above it.
const NAMED_KEYS: i64 = 1519;

fn lines(text: &str) -> Vec<(String, String)> {
    text.lines()
        .filter_map(|l| l.split_once(':'))
        .map(|(k, v)| (k.to_owned(), v.to_owned()))
        .collect()
}

fn data_version(entries: &[(String, String)]) -> Option<i64> {
    entries
        .iter()
        .find(|(k, _)| k == "version")
        .and_then(|(_, v)| v.trim().parse().ok())
}

/// `into` with the values of `from`: shared keys only, key bindings only when `bindings` is true,
/// and the file's `version` when `version` is true (the shared file keeps its writer's).
/// Keys `into` lacks are added at the end; its own order and local keys are kept.
fn merge(from: &str, into: &str, bindings: bool, version: bool) -> String {
    let from = lines(from);
    let mut out = lines(into);
    let shared = |k: &str| {
        (!LOCAL.contains(&k) || (version && k == "version")) && (bindings || !k.starts_with("key_"))
    };
    for (k, v) in from.into_iter().filter(|(k, _)| shared(k)) {
        match out.iter_mut().find(|(ok, _)| *ok == k) {
            Some(e) => e.1 = v,
            None => out.push((k, v)),
        }
    }
    let mut text: String = out.iter().map(|(k, v)| format!("{k}:{v}\n")).collect();
    if text.is_empty() {
        text.push('\n');
    }
    text
}

fn read(path: &Path) -> String {
    std::fs::read_to_string(path).unwrap_or_default()
}

/// Whether a game version reads key bindings by name (1.13 and newer, and the year-numbered
/// releases); older ones store numbers and must never get named keys.
fn names_keys(game_version: &str) -> bool {
    let v = game_version.trim();
    if let Some(rest) = v.strip_prefix("1.") {
        return rest
            .split(|c: char| !c.is_ascii_digit())
            .next()
            .and_then(|m| m.parse::<u32>().ok())
            .is_some_and(|m| m >= 13);
    }
    // Snapshots "18w01a": from 2018 on; anything else (26.3, 26.4-snapshot-1…) is newer.
    if let Some((year, _)) = v.split_once('w')
        && year.len() == 2
        && let Ok(y) = year.parse::<u32>()
    {
        return y >= 18;
    }
    v.split('.')
        .next()
        .and_then(|m| m.parse::<u32>().ok())
        .is_some_and(|m| m >= 2)
}

/// What [`pull_shared`] did, which decides whether the game's settings go back afterwards.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Pulled {
    /// Nothing shared yet: this game's settings will start the shared ones.
    NoShared,
    /// The shared settings were merged into the instance's own.
    Merged,
    /// A new instance started from the shared settings.
    Started,
    /// The instance has no settings yet and the shared ones can't start it safely (saved before
    /// the shared file carried its version): its defaults must not replace the shared settings.
    KeepOut,
}

impl Pulled {
    /// Give this game's settings back to the shared ones when it exits.
    pub fn shares_back(self) -> bool {
        self != Pulled::KeepOut
    }
}

/// Before a start: the instance takes the shared settings (key bindings only where both sides
/// name keys). A new instance starts from them, with the shared file's `version` so the game
/// reads them in the format they were written in, never as ancient settings to convert.
pub fn pull_shared(shared_dir: &Path, game_dir: &Path, game_version: &str) -> Result<Pulled> {
    let shared = read(&shared_dir.join("options.txt"));
    if shared.trim().is_empty() {
        return Ok(Pulled::NoShared);
    }
    let path = game_dir.join("options.txt");
    let own = read(&path);
    let shared_version = data_version(&lines(&shared));
    let (text, pulled) = match data_version(&lines(&own)) {
        Some(v) => {
            let bindings = v >= NAMED_KEYS && shared_version.is_none_or(|s| s >= NAMED_KEYS);
            (merge(&shared, &own, bindings, false), Pulled::Merged)
        }
        None => {
            let Some(sv) = shared_version else {
                return Ok(Pulled::KeepOut);
            };
            let bindings = sv >= NAMED_KEYS && names_keys(game_version);
            (
                merge(&shared, &format!("version:{sv}\n"), bindings, false),
                Pulled::Started,
            )
        }
    };
    std::fs::create_dir_all(game_dir).at(game_dir)?;
    crate::util::fs::atomic_write_sync(&path, text.as_bytes())?;
    Ok(pulled)
}

/// After the game: what was changed in the instance becomes the shared settings, with the
/// file's version, so a new instance can later start from them.
pub fn push_shared(game_dir: &Path, shared_dir: &Path) -> Result<bool> {
    let own = read(&game_dir.join("options.txt"));
    let Some(v) = data_version(&lines(&own)) else {
        return Ok(false);
    };
    let bindings = v >= NAMED_KEYS;
    let path = shared_dir.join("options.txt");
    std::fs::create_dir_all(shared_dir).at(shared_dir)?;
    let merged = merge(&own, &read(&path), bindings, true);
    crate::util::fs::atomic_write_sync(&path, merged.as_bytes())?;
    Ok(true)
}

// --- key bindings ------------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct KeyBinding {
    /// The action, e.g. `key.jump` or `key.aerion.zoom`.
    pub action: String,
    /// The key, e.g. `key.keyboard.space`, `key.mouse.left`, `key.keyboard.unknown` for none.
    pub key: String,
}

/// The bindings in a settings folder's `options.txt` (empty when it has none, or an old format).
pub fn read_bindings(game_dir: &Path) -> Vec<KeyBinding> {
    let entries = lines(&read(&game_dir.join("options.txt")));
    if data_version(&entries).is_some_and(|v| v < NAMED_KEYS) {
        return Vec::new();
    }
    entries
        .into_iter()
        .filter_map(|(k, v)| {
            k.strip_prefix("key_").map(|a| KeyBinding {
                action: a.to_owned(),
                key: v,
            })
        })
        .collect()
}

/// A key name as `options.txt` stores it: `key.keyboard.*` or `key.mouse.*`, nothing else.
fn valid_key(k: &str) -> bool {
    (k.starts_with("key.keyboard.") || k.starts_with("key.mouse."))
        && k.len() <= 64
        && k.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_')
}

/// Change bindings in a settings folder; actions it doesn't list yet are added.
pub fn write_bindings(game_dir: &Path, changes: &[KeyBinding]) -> Result<()> {
    for c in changes {
        let action_ok = !c.action.is_empty()
            && c.action.len() <= 128
            && c.action.bytes().all(|b| b.is_ascii_graphic() && b != b':');
        if !action_ok || !valid_key(&c.key) {
            return Err(Error::Invalid(format!("invalid key binding {}", c.action)));
        }
    }
    let path = game_dir.join("options.txt");
    let own = read(&path);
    if data_version(&lines(&own)).is_some_and(|v| v < NAMED_KEYS) {
        return Err(Error::Invalid(
            "this game version stores keys differently".into(),
        ));
    }
    let patch: String = changes
        .iter()
        .map(|c| format!("key_{}:{}\n", c.action, c.key))
        .collect();
    std::fs::create_dir_all(game_dir).at(game_dir)?;
    crate::util::fs::atomic_write_sync(&path, merge(&patch, &own, true, false).as_bytes())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn copy_template_apply() {
        let dir = tempfile::tempdir().unwrap();
        let (a, b, tpl) = (
            dir.path().join("a"),
            dir.path().join("b"),
            dir.path().join("tpl"),
        );
        std::fs::create_dir_all(&a).unwrap();
        assert!(save_template(&a, &tpl, "A").is_err(), "nothing to save yet");
        std::fs::write(
            a.join("options.txt"),
            "fov:0.5\nkey_key.jump:key.keyboard.space\n",
        )
        .unwrap();
        std::fs::write(a.join("optionsof.txt"), "ofFastRender:true\n").unwrap();
        assert_eq!(copy(&a, &b).unwrap(), 2);
        assert_eq!(
            std::fs::read_to_string(b.join("options.txt"))
                .unwrap()
                .lines()
                .next(),
            Some("fov:0.5")
        );

        let meta = save_template(&a, &tpl, "A").unwrap();
        assert_eq!(template(&tpl).unwrap().source, meta.source);
        let c = dir.path().join("c");
        assert!(apply_template(&tpl, &c).unwrap());
        assert!(c.join("optionsof.txt").is_file());
        assert!(
            !apply_template(&tpl, &c).unwrap(),
            "existing settings are kept"
        );
        clear_template(&tpl).unwrap();
        assert!(template(&tpl).is_none());
        clear_template(&tpl).unwrap();
    }

    #[test]
    fn a_new_instance_starts_from_the_shared_settings_and_never_resets_them() {
        let dir = tempfile::tempdir().unwrap();
        let (shared, a, b) = (
            dir.path().join("s"),
            dir.path().join("a"),
            dir.path().join("b"),
        );
        std::fs::create_dir_all(&a).unwrap();
        // A was changed by the player (fov 1.0) and gives it to the shared settings.
        std::fs::write(
            a.join("options.txt"),
            "version:5023\nfov:1.0\nresourcePacks:[\"file/A.zip\"]\nkey_key.jump:key.keyboard.j\n",
        )
        .unwrap();
        assert!(push_shared(&a, &shared).unwrap());
        // B is new: it starts from them, in the shared file's format, without A's packs.
        assert_eq!(pull_shared(&shared, &b, "26.3").unwrap(), Pulled::Started);
        let got = std::fs::read_to_string(b.join("options.txt")).unwrap();
        assert!(got.starts_with("version:5023\n"));
        assert!(got.contains("fov:1.0"));
        assert!(got.contains("key_key.jump:key.keyboard.j"));
        assert!(!got.contains("resourcePacks"));
        // B's game keeps those values; giving them back changes nothing for A's choice.
        assert!(push_shared(&b, &shared).unwrap());
        assert!(
            std::fs::read_to_string(shared.join("options.txt"))
                .unwrap()
                .contains("fov:1.0")
        );
        // With its own file, B keeps its packs and takes what changed elsewhere.
        std::fs::write(
            b.join("options.txt"),
            "version:5023\nresourcePacks:[\"file/B.zip\"]\nfov:0.0\n",
        )
        .unwrap();
        assert_eq!(pull_shared(&shared, &b, "26.3").unwrap(), Pulled::Merged);
        let got = std::fs::read_to_string(b.join("options.txt")).unwrap();
        assert!(got.contains("fov:1.0"));
        assert!(got.contains("file/B.zip"));
    }

    #[test]
    fn unsafe_starts_keep_the_shared_settings() {
        let dir = tempfile::tempdir().unwrap();
        let (shared, b) = (dir.path().join("s"), dir.path().join("b"));
        std::fs::create_dir_all(&shared).unwrap();
        // Saved before the shared file carried its version: B can't start from it, and its
        // defaults must not go back.
        std::fs::write(shared.join("options.txt"), "fov:1.0\n").unwrap();
        let pulled = pull_shared(&shared, &b, "26.3").unwrap();
        assert_eq!(pulled, Pulled::KeepOut);
        assert!(!pulled.shares_back());
        assert!(!b.join("options.txt").exists());
        // Nothing shared yet: the first game's settings start them.
        assert_eq!(
            pull_shared(&dir.path().join("none"), &b, "26.3").unwrap(),
            Pulled::NoShared
        );
    }

    #[test]
    fn old_games_never_get_named_keys() {
        let dir = tempfile::tempdir().unwrap();
        let (shared, old, fresh_old) = (
            dir.path().join("s"),
            dir.path().join("old"),
            dir.path().join("o2"),
        );
        std::fs::create_dir_all(&shared).unwrap();
        std::fs::create_dir_all(&old).unwrap();
        std::fs::write(
            shared.join("options.txt"),
            "version:5023\nfov:0.5\nkey_key.jump:key.keyboard.j\n",
        )
        .unwrap();
        std::fs::write(old.join("options.txt"), "version:1343\nkey_key.jump:57\n").unwrap();
        pull_shared(&shared, &old, "1.12.2").unwrap();
        let got = std::fs::read_to_string(old.join("options.txt")).unwrap();
        assert!(got.contains("key_key.jump:57"));
        assert!(got.contains("fov:0.5"));
        assert!(read_bindings(&old).is_empty());
        assert!(
            write_bindings(
                &old,
                &[KeyBinding {
                    action: "key.jump".into(),
                    key: "key.keyboard.j".into()
                }]
            )
            .is_err()
        );
        // A new 1.8.9 instance gets the settings but no named keys.
        pull_shared(&shared, &fresh_old, "1.8.9").unwrap();
        assert!(
            !std::fs::read_to_string(fresh_old.join("options.txt"))
                .unwrap()
                .contains("key_")
        );
    }

    #[test]
    fn knows_which_versions_name_keys() {
        for v in ["1.13", "1.21.11", "26.3", "26.4-snapshot-6", "24w14a"] {
            assert!(names_keys(v), "{v}");
        }
        for v in ["1.12.2", "1.8.9", "17w31a", "b1.7.3"] {
            assert!(!names_keys(v), "{v}");
        }
    }

    #[test]
    fn edits_bindings_safely() {
        let dir = tempfile::tempdir().unwrap();
        let g = dir.path().join("g");
        std::fs::create_dir_all(&g).unwrap();
        std::fs::write(
            g.join("options.txt"),
            "version:4671\nkey_key.jump:key.keyboard.space\nfov:0.5\n",
        )
        .unwrap();
        write_bindings(
            &g,
            &[
                KeyBinding {
                    action: "key.jump".into(),
                    key: "key.keyboard.j".into(),
                },
                KeyBinding {
                    action: "key.aerion.zoom".into(),
                    key: "key.mouse.4".into(),
                },
            ],
        )
        .unwrap();
        let b = read_bindings(&g);
        assert_eq!(
            b[0],
            KeyBinding {
                action: "key.jump".into(),
                key: "key.keyboard.j".into()
            }
        );
        assert!(
            b.iter()
                .any(|k| k.action == "key.aerion.zoom" && k.key == "key.mouse.4")
        );
        assert!(
            std::fs::read_to_string(g.join("options.txt"))
                .unwrap()
                .contains("fov:0.5")
        );
        for bad in ["key.keyboard.j\nfov:1", "rm -rf", "key.keyboard.a:b"] {
            assert!(
                write_bindings(
                    &g,
                    &[KeyBinding {
                        action: "key.jump".into(),
                        key: bad.into()
                    }]
                )
                .is_err()
            );
        }
        assert!(
            write_bindings(
                &g,
                &[KeyBinding {
                    action: "a\nfov".into(),
                    key: "key.keyboard.j".into()
                }]
            )
            .is_err()
        );
    }
}
