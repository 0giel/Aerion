//! Badges next to players' names in the game (bug hunters, testers…). The list is `data/badges.json`
//! in the Aerion repository, edited by hand (`scripts/badge.sh`); there is no server. The launcher
//! fetches it before a launch (cached for five minutes, so a new badge shows within minutes; the last copy is used offline) and writes the
//! players the mod should mark to `<root>/aerion-mod/badges.json`.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::time::Duration;

use serde::Deserialize;

use crate::engine::Engine;
use crate::net::cache::MINUTE;
use crate::paths::Paths;

const URL: &str = "https://raw.githubusercontent.com/0giel/Aerion/main/data/badges.json";
/// A launch never waits longer than this for the list.
const WAIT: Duration = Duration::from_secs(3);
const MAX_PLAYERS: usize = 10_000;

#[derive(Deserialize)]
struct List {
    players: Vec<Player>,
}

/// A player's badges: `badge` for one, `badges` for several (the first is the one others see).
#[derive(Deserialize)]
struct Player {
    uuid: String,
    #[serde(default)]
    badge: Option<String>,
    #[serde(default)]
    badges: Vec<String>,
}

pub fn file(paths: &Paths) -> PathBuf {
    paths.aerion_mod_dir().join("badges.json")
}

/// Every badge of every player, for mods that let a player pick which of their own to show.
/// `file` keeps only the first badge as a plain string, which older mods read.
pub fn file_all(paths: &Paths) -> PathBuf {
    paths.aerion_mod_dir().join("badges-all.json")
}

const MAX_BADGES: usize = 8;

/// Badges per player (UUID without dashes, lower case). Entries with a malformed UUID are
/// skipped, as are malformed badge ids; the mod ignores badge ids it doesn't know.
fn parse(bytes: &[u8]) -> Option<BTreeMap<String, Vec<String>>> {
    let list: List = serde_json::from_slice(bytes).ok()?;
    let map = list
        .players
        .into_iter()
        .filter_map(|p| {
            let uuid = uuid::Uuid::parse_str(p.uuid.trim())
                .ok()?
                .simple()
                .to_string();
            let mut badges: Vec<String> = Vec::new();
            for b in p.badge.into_iter().chain(p.badges) {
                let ok =
                    (1..=24).contains(&b.len()) && b.chars().all(|c| c.is_ascii_alphanumeric());
                if ok && !badges.contains(&b) && badges.len() < MAX_BADGES {
                    badges.push(b);
                }
            }
            (!badges.is_empty()).then_some((uuid, badges))
        })
        .take(MAX_PLAYERS)
        .collect();
    Some(map)
}

/// Bring the mod's copy up to date; on any failure the previous copy stays.
pub async fn refresh(engine: &Engine) {
    let fetched = tokio::time::timeout(WAIT, engine.cache.get(URL, 5 * MINUTE)).await;
    let Ok(Ok(bytes)) = fetched else {
        tracing::debug!("badges: list not available, keeping the last copy");
        return;
    };
    let Some(map) = parse(&bytes) else {
        tracing::warn!("badges: list is not valid JSON");
        return;
    };
    let first: BTreeMap<&String, &String> = map.iter().map(|(k, v)| (k, &v[0])).collect();
    if let Err(e) = crate::util::fs::write_json(file(&engine.paths), &first).await {
        tracing::warn!(error = %e, "badges: can't save the list");
    }
    if let Err(e) = crate::util::fs::write_json(file_all(&engine.paths), &map).await {
        tracing::warn!(error = %e, "badges: can't save the full list");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_valid_entries_only() {
        let map = parse(
            br#"{"players":[
                {"name":"0giel","uuid":"B2813A7B-BBA3-4B00-AE62-4A6BDAD17A91","badge":"creator"},
                {"uuid":"not-a-uuid","badge":"tester"},
                {"uuid":"069a79f444e94726a5befca90e38aaf5","badge":"../evil"}
            ]}"#,
        )
        .unwrap();
        assert_eq!(map.len(), 1);
        assert_eq!(map["b2813a7bbba34b00ae624a6bdad17a91"], ["creator"]);
        assert!(parse(b"<html>").is_none());
    }

    #[test]
    fn several_badges_keep_their_order() {
        let map = parse(
            br#"{"players":[
                {"uuid":"b2813a7b-bba3-4b00-ae62-4a6bdad17a91","badge":"creator","badges":["tester","creator","bad id"]}
            ]}"#,
        )
        .unwrap();
        assert_eq!(
            map["b2813a7bbba34b00ae624a6bdad17a91"],
            ["creator", "tester"]
        );
    }
}
