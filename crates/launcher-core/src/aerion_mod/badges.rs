//! Badges next to players' names in the game (bug hunters, testers…). The list is `badges.json`
//! in the Aerion repository, edited by hand (`scripts/badge.sh`); there is no server. The launcher
//! fetches it before a launch (cached for an hour, the last copy is used offline) and writes the
//! players the mod should mark to `<root>/aerion-mod/badges.json`.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::time::Duration;

use serde::Deserialize;

use crate::engine::Engine;
use crate::net::cache::HOUR;
use crate::paths::Paths;

const URL: &str = "https://raw.githubusercontent.com/0giel/Aerion/main/badges.json";
/// A launch never waits longer than this for the list.
const WAIT: Duration = Duration::from_secs(3);
const MAX_PLAYERS: usize = 10_000;

#[derive(Deserialize)]
struct List {
    players: Vec<Player>,
}

#[derive(Deserialize)]
struct Player {
    uuid: String,
    badge: String,
}

pub fn file(paths: &Paths) -> PathBuf {
    paths.aerion_mod_dir().join("badges.json")
}

/// Badge per player (UUID without dashes, lower case). Entries with a malformed UUID or badge
/// id are skipped; the mod ignores badge ids it doesn't know.
fn parse(bytes: &[u8]) -> Option<BTreeMap<String, String>> {
    let list: List = serde_json::from_slice(bytes).ok()?;
    let map = list
        .players
        .into_iter()
        .filter_map(|p| {
            let uuid = uuid::Uuid::parse_str(p.uuid.trim())
                .ok()?
                .simple()
                .to_string();
            let ok = (1..=24).contains(&p.badge.len())
                && p.badge.chars().all(|c| c.is_ascii_alphanumeric());
            ok.then_some((uuid, p.badge))
        })
        .take(MAX_PLAYERS)
        .collect();
    Some(map)
}

/// Bring the mod's copy up to date; on any failure the previous copy stays.
pub async fn refresh(engine: &Engine) {
    let fetched = tokio::time::timeout(WAIT, engine.cache.get(URL, HOUR)).await;
    let Ok(Ok(bytes)) = fetched else {
        tracing::debug!("badges: list not available, keeping the last copy");
        return;
    };
    let Some(map) = parse(&bytes) else {
        tracing::warn!("badges: list is not valid JSON");
        return;
    };
    if let Err(e) = crate::util::fs::write_json(file(&engine.paths), &map).await {
        tracing::warn!(error = %e, "badges: can't save the list");
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
        assert_eq!(map["b2813a7bbba34b00ae624a6bdad17a91"], "creator");
        assert!(parse(b"<html>").is_none());
    }
}
