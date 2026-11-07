//! The Aerion mod updates on its own, between launcher releases. `scripts/release-mod.sh` builds
//! the jars, signs each with the launcher's updater key and writes `mod-latest.json` (kept in the
//! repository, pointing at the jars of a `mod-v<version>` GitHub release). Before a launch the
//! launcher reads it (cached for an hour, never waiting long) and, when it lists a newer mod this
//! launcher can run, downloads the jars into `<root>/aerion-mod/updates`, checking each one's
//! SHA-256 and signature. Anything missing or wrong keeps the mod bundled with the launcher.
//! A mod update may also bring jars for new Minecraft versions.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::time::Duration;

use base64::Engine as _;
use serde::{Deserialize, Serialize};

use crate::engine::Engine;
use crate::net::cache::HOUR;
use crate::paths::Paths;

const MANIFEST: &str = "https://raw.githubusercontent.com/0giel/Aerion/main/mod-latest.json";
/// The updater public key from `src-tauri/tauri.conf.json` (`plugins.updater.pubkey`); a test in
/// src-tauri checks that the two stay the same.
pub const PUBLIC_KEY: &str = "dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk6IEM0QTU1MDk1OUZDREJEMDEKUldRQnZjMmZsVkNseE9jZVFaNXNwTStyQWRjZ3dZUFc4MWY0UG9GMlRWWXBYZUZxT2tuN1MzcTQK";
/// The bundled mod's version: `version=` in `mod/gradle.properties`, which also builds the jars
/// in `src-tauri/resources`. It moves on its own when a mod update is released.
pub fn bundled() -> &'static str {
    static VERSION: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    VERSION.get_or_init(|| {
        include_str!("../../../../mod/gradle.properties")
            .lines()
            .find_map(|l| l.trim().strip_prefix("version="))
            .unwrap_or("0.0.0")
            .trim()
            .to_owned()
    })
}

/// This launcher's own version, for a mod's `minLauncher`.
const LAUNCHER: &str = env!("CARGO_PKG_VERSION");
/// A launch waits at most this long for the manifest and the jars.
const WAIT: Duration = Duration::from_secs(8);
const MAX_JAR: usize = 16 * 1024 * 1024;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Manifest {
    version: String,
    /// Oldest launcher that can run this mod (it may rely on newer hud.json fields).
    min_launcher: String,
    jars: BTreeMap<String, Jar>,
}

#[derive(Debug, Deserialize)]
struct Jar {
    url: String,
    sha256: String,
    /// The `.sig` file `tauri signer sign` writes (base64 of a minisign signature).
    signature: String,
}

/// What the updates folder holds.
#[derive(Debug, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Installed {
    pub version: String,
    pub game_versions: Vec<String>,
}

fn dir(paths: &Paths) -> PathBuf {
    paths.aerion_mod_dir().join("updates")
}

fn state_file(paths: &Paths) -> PathBuf {
    dir(paths).join("installed.json")
}

/// `1.2.3` as numbers; anything else counts as 0.0.0.
fn parse(v: &str) -> (u32, u32, u32) {
    let mut it = v.trim().split('.').map(|p| p.parse::<u32>().unwrap_or(0));
    (
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
    )
}

fn newer(a: &str, b: &str) -> bool {
    parse(a) > parse(b)
}

/// The downloaded mod, when it is newer than the bundled one.
pub fn installed(paths: &Paths) -> Option<Installed> {
    let s: Installed = serde_json::from_slice(&std::fs::read(state_file(paths)).ok()?).ok()?;
    newer(&s.version, bundled()).then_some(s)
}

/// The downloaded jar for a game version, when there is one newer than the bundled mod.
pub fn jar(paths: &Paths, game_version: &str) -> Option<PathBuf> {
    let s = installed(paths)?;
    if !s.game_versions.iter().any(|v| v == game_version) {
        return None;
    }
    let jar = dir(paths).join(super::jar_name(game_version));
    jar.is_file().then_some(jar)
}

/// A jar signed with the updater key (and matching its published checksum).
fn verify(bytes: &[u8], jar: &Jar) -> bool {
    verify_with(PUBLIC_KEY, bytes, jar)
}

fn verify_with(public_key: &str, bytes: &[u8], jar: &Jar) -> bool {
    let sum = crate::util::hash::sha256_hex(bytes);
    if !sum.eq_ignore_ascii_case(jar.sha256.trim()) {
        return false;
    }
    let decode = |s: &str| {
        base64::engine::general_purpose::STANDARD
            .decode(s.trim())
            .ok()
            .and_then(|b| String::from_utf8(b).ok())
    };
    let (Some(key), Some(sig)) = (decode(public_key), decode(&jar.signature)) else {
        return false;
    };
    let (Ok(key), Ok(sig)) = (
        minisign_verify::PublicKey::decode(&key),
        minisign_verify::Signature::decode(&sig),
    ) else {
        return false;
    };
    key.verify(bytes, &sig, false).is_ok()
}

/// Only release asset URLs of the project are downloaded.
fn url_ok(url: &str) -> bool {
    url.starts_with("https://github.com/0giel/Aerion/releases/download/mod-v")
}

/// Bring the downloaded mod up to date; on any failure the previous state stays.
pub async fn refresh(engine: &Engine) {
    match tokio::time::timeout(WAIT, update(engine)).await {
        Ok(Ok(Some(v))) => tracing::info!(version = %v, "Aerion mod updated"),
        Ok(Ok(None)) => {}
        Ok(Err(e)) => tracing::debug!(error = %e, "Aerion mod update not available"),
        Err(_) => tracing::debug!("Aerion mod update timed out; trying again next launch"),
    }
}

async fn update(engine: &Engine) -> crate::error::Result<Option<String>> {
    let m: Manifest = engine.cache.get_json(MANIFEST, HOUR).await?;
    let current = installed(&engine.paths).map_or_else(|| bundled().to_owned(), |s| s.version);
    if !newer(&m.version, &current) || newer(&m.min_launcher, LAUNCHER) {
        return Ok(None);
    }
    let dir = dir(&engine.paths);
    let staging = dir.join(format!(".{}", m.version));
    let _ = tokio::fs::remove_dir_all(&staging).await;
    tokio::fs::create_dir_all(&staging)
        .await
        .map_err(|e| crate::error::Error::Invalid(e.to_string()))?;
    let mut versions = Vec::new();
    for (game_version, jar) in &m.jars {
        let name_ok = !game_version.is_empty()
            && game_version
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-');
        if !name_ok || !url_ok(&jar.url) {
            continue;
        }
        let bytes = engine.http.get_bytes(&jar.url).await?;
        if bytes.len() > MAX_JAR || !verify(&bytes, jar) {
            tracing::warn!(url = %jar.url, "Aerion mod jar failed its signature check; not used");
            let _ = tokio::fs::remove_dir_all(&staging).await;
            return Ok(None);
        }
        let file = staging.join(super::jar_name(game_version));
        tokio::fs::write(&file, &bytes)
            .await
            .map_err(|e| crate::error::Error::Invalid(e.to_string()))?;
        versions.push(game_version.clone());
    }
    if versions.is_empty() {
        let _ = tokio::fs::remove_dir_all(&staging).await;
        return Ok(None);
    }
    // Every jar checked: move them in, then record the version, so a game never sees half an update.
    for v in &versions {
        let name = super::jar_name(v);
        tokio::fs::rename(staging.join(&name), dir.join(&name))
            .await
            .map_err(|e| crate::error::Error::Invalid(e.to_string()))?;
    }
    let _ = tokio::fs::remove_dir_all(&staging).await;
    let state = Installed {
        version: m.version.clone(),
        game_versions: versions,
    };
    crate::util::fs::write_json(state_file(&engine.paths), &state).await?;
    Ok(Some(m.version))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_compare_by_number() {
        assert!(newer("1.0.10", "1.0.9"));
        assert!(newer("1.1.0", "1.0.99"));
        assert!(!newer("1.0.0", "1.0.0"));
        assert!(!newer("junk", "1.0.0"));
    }

    #[test]
    fn rejects_unsigned_or_changed_jars() {
        let jar = Jar {
            url: "https://github.com/0giel/Aerion/releases/download/mod-v1.0.1/aerion-ui-26.3.jar"
                .into(),
            sha256: crate::util::hash::sha256_hex(b"jar"),
            signature: base64::engine::general_purpose::STANDARD.encode("not a signature"),
        };
        assert!(!verify(b"jar", &jar));
        assert!(!verify(b"other", &jar));
        assert!(url_ok(&jar.url));
        assert!(!url_ok("https://example.com/aerion-ui-26.3.jar"));
    }

    #[test]
    fn accepts_a_jar_signed_like_a_release() {
        // A throwaway key made with `tauri signer generate`, not the release key.
        const KEY: &str = "dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk6IDNBQ0Y0RUI4RDY1MzVFREQKUldUZFhsUFd1RTdQT3FuN2xGUHc3azVRNTFvS01WTTNJazdUMGRHMGtjVXpYVFoyUmZoaWFLTlgK";
        let jar = Jar {
            url: String::new(),
            sha256: crate::util::hash::sha256_hex(b"jar"),
            signature: "dW50cnVzdGVkIGNvbW1lbnQ6IHNpZ25hdHVyZSBmcm9tIHRhdXJpIHNlY3JldCBrZXkKUlVUZFhsUFd1RTdQT29OT2xQWno5SEtMV0VCeGNWNUlqTjNieWlGUFVwUzVXMjFKRkVLcThQRHpqN285NEo5ZlNDM3hkVlJTS3ZWaE1DakY2ZXlzRU0ydWtCd0wyTjUzOWcwPQp0cnVzdGVkIGNvbW1lbnQ6IHRpbWVzdGFtcDoxNzk0MDU2OTM5CWZpbGU6YS5qYXIKNnlBdmt1SzF1YS95ZXdRaUgwYVYvZkU0am5zcmlUU1Ezc2dFNjVLMHkzUElRT292dVY0dUVoeEQ1RnE4N3hEV3pYaUdYdjhMZ3hRb3Z1cHRaNkhMQlE9PQo=".into(),
        };
        assert!(verify_with(KEY, b"jar", &jar));
        assert!(!verify(b"jar", &jar), "signed by another key");
    }

    #[test]
    fn older_downloads_give_way_to_the_bundled_mod() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::at(dir.path().to_path_buf(), false);
        std::fs::create_dir_all(super::dir(&paths)).unwrap();
        std::fs::write(super::dir(&paths).join("aerion-ui-26.4.jar"), b"jar").unwrap();
        let write = |v: &str| {
            let s = Installed {
                version: v.into(),
                game_versions: vec!["26.4".into()],
            };
            std::fs::write(state_file(&paths), serde_json::to_vec(&s).unwrap()).unwrap();
        };
        write("0.0.1");
        assert!(jar(&paths, "26.4").is_none());
        write("999.0.0");
        assert!(jar(&paths, "26.4").is_some());
        assert!(jar(&paths, "26.3").is_none());
    }

    #[test]
    fn bundled_version_is_read_from_the_mod() {
        assert_ne!(parse(bundled()), (0, 0, 0));
    }
}
