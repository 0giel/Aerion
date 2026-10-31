//! The in-game server list (`<game>/servers.dat`), edited without losing fields we don't know.

use std::path::Path;

use serde::Serialize;

use crate::error::{Error, IoContext, Result};
use crate::util::nbt::{self, Tag};

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct SavedServer {
    pub name: String,
    pub address: String,
    /// Last icon the game stored (base64 PNG), shown until a live status arrives.
    pub icon: Option<String>,
}

fn load(game_dir: &Path) -> Result<Tag> {
    let path = game_dir.join("servers.dat");
    match std::fs::read(&path) {
        Ok(data) => nbt::read(&data),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Tag::Compound(vec![])),
        Err(e) => Err(Error::io(&path, e)),
    }
}

fn entries(root: &Tag) -> Vec<Tag> {
    match root.get("servers") {
        Some(Tag::List(_, items)) => items.clone(),
        _ => vec![],
    }
}

fn save(game_dir: &Path, mut root: Tag, servers: Vec<Tag>) -> Result<()> {
    if let Tag::Compound(items) = &mut root {
        items.retain(|(k, _)| k != "servers");
        items.insert(0, ("servers".into(), Tag::List(10, servers)));
    }
    let path = game_dir.join("servers.dat");
    let tmp = game_dir.join("servers.dat.tmp");
    std::fs::create_dir_all(game_dir).at(game_dir)?;
    std::fs::write(&tmp, nbt::write(&root)).at(&tmp)?;
    std::fs::rename(&tmp, &path).at(&path)
}

pub fn list(game_dir: &Path) -> Result<Vec<SavedServer>> {
    Ok(entries(&load(game_dir)?)
        .iter()
        // Servers added by the game's "Direct connection" are hidden.
        .filter(|s| s.get("hidden").and_then(Tag::as_i64) != Some(1))
        .filter_map(|s| {
            Some(SavedServer {
                name: s.get("name").and_then(Tag::as_str).unwrap_or("").to_owned(),
                address: s.get("ip").and_then(Tag::as_str)?.to_owned(),
                icon: s.get("icon").and_then(Tag::as_str).map(str::to_owned),
            })
        })
        .collect())
}

fn check(name: &str, address: &str) -> Result<()> {
    if address.trim().is_empty()
        || address.len() > 255
        || name.len() > 128
        || address.contains(char::is_whitespace)
    {
        return Err(Error::Invalid("invalid server address".into()));
    }
    Ok(())
}

pub fn add(game_dir: &Path, name: &str, address: &str) -> Result<()> {
    check(name, address)?;
    let root = load(game_dir)?;
    let mut servers = entries(&root);
    servers.push(Tag::Compound(vec![
        ("name".into(), Tag::String(name.trim().to_owned())),
        ("ip".into(), Tag::String(address.trim().to_owned())),
    ]));
    save(game_dir, root, servers)
}

/// Remove the first server with this address.
pub fn remove(game_dir: &Path, address: &str) -> Result<()> {
    let root = load(game_dir)?;
    let mut servers = entries(&root);
    if let Some(i) = servers
        .iter()
        .position(|s| s.get("ip").and_then(Tag::as_str) == Some(address))
    {
        servers.remove(i);
    }
    save(game_dir, root, servers)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn add_list_remove_keeps_other_fields() {
        let dir = tempfile::tempdir().unwrap();
        let g = dir.path();
        assert!(list(g).unwrap().is_empty());
        // A list written by the game, with fields we don't model.
        let game_written = Tag::Compound(vec![(
            "servers".into(),
            Tag::List(
                10,
                vec![
                    Tag::Compound(vec![
                        ("name".into(), Tag::String("Hypixel".into())),
                        ("ip".into(), Tag::String("mc.hypixel.net".into())),
                        ("acceptTextures".into(), Tag::Byte(1)),
                    ]),
                    Tag::Compound(vec![
                        ("ip".into(), Tag::String("hidden.example".into())),
                        ("hidden".into(), Tag::Byte(1)),
                    ]),
                ],
            ),
        )]);
        std::fs::write(g.join("servers.dat"), nbt::write(&game_written)).unwrap();
        add(g, "Mój serwer", "play.example.pl:25566").unwrap();
        let l = list(g).unwrap();
        assert_eq!(l.len(), 2);
        assert_eq!(l[1].name, "Mój serwer");
        remove(g, "mc.hypixel.net").unwrap();
        let root = nbt::read(&std::fs::read(g.join("servers.dat")).unwrap()).unwrap();
        let all = entries(&root);
        assert_eq!(all.len(), 2, "hidden entry kept");
        assert!(add(g, "x", "bad address").is_err());
    }
}
