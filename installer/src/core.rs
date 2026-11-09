//! What the installer does, without the window: find the newest release, download it and check
//! its signature. The same files and the same key as the launcher's own updates.
use std::io::Read;
use std::time::Duration;

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;
use minisign_verify::{PublicKey, Signature};

pub const MANIFEST_URL: &str =
    "https://github.com/0giel/Aerion/releases/latest/download/latest.json";
const ALLOWED_URL: &str = "https://github.com/0giel/Aerion/releases/download/";
/// The launcher's update key (`plugins.updater.pubkey` in `src-tauri/tauri.conf.json`).
pub const PUBLIC_KEY: &str = "dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk6IEM0QTU1MDk1OUZDREJEMDEKUldRQnZjMmZsVkNseE9jZVFaNXNwTStyQWRjZ3dZUFc4MWY0UG9GMlRWWXBYZUZxT2tuN1MzcTQK";
const MAX_MANIFEST: u64 = 1 << 20;
const MAX_INSTALLER: usize = 256 << 20;

#[derive(Debug, PartialEq, Eq)]
pub struct Release {
    pub version: String,
    pub url: String,
    pub signature: String,
}

/// Why installing stopped; the window turns it into a sentence.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Failure {
    Network(String),
    Manifest(String),
    Signature,
    Running,
    Install(String),
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(20))
        .timeout_read(Duration::from_secs(45))
        .user_agent("AerionSetup/1.0")
        .build()
}

/// The Windows entry of `latest.json`.
pub fn parse_manifest(text: &str) -> Result<Release, Failure> {
    let bad = |what: &str| Failure::Manifest(what.to_owned());
    let json: serde_json::Value = serde_json::from_str(text).map_err(|e| bad(&e.to_string()))?;
    let version = json["version"]
        .as_str()
        .ok_or_else(|| bad("no version"))?
        .to_owned();
    let platforms = &json["platforms"];
    let entry = [
        &platforms["windows-x86_64-nsis"],
        &platforms["windows-x86_64"],
    ]
    .into_iter()
    .find(|p| p.is_object())
    .ok_or_else(|| bad("no Windows build"))?;
    let url = entry["url"]
        .as_str()
        .ok_or_else(|| bad("no address"))?
        .to_owned();
    let signature = entry["signature"]
        .as_str()
        .ok_or_else(|| bad("no signature"))?
        .to_owned();
    // Only the project's own releases, whatever the file says.
    if !url.starts_with(ALLOWED_URL) || !url.ends_with("-setup.exe") {
        return Err(bad("unexpected address"));
    }
    Ok(Release {
        version,
        url,
        signature,
    })
}

pub fn latest() -> Result<Release, Failure> {
    let net = |e: String| Failure::Network(e);
    let response = agent()
        .get(MANIFEST_URL)
        .call()
        .map_err(|e| net(e.to_string()))?;
    let mut text = String::new();
    response
        .into_reader()
        .take(MAX_MANIFEST)
        .read_to_string(&mut text)
        .map_err(|e| net(e.to_string()))?;
    parse_manifest(&text)
}

/// The file's bytes; `progress(received, total)` follows along (`total` 0 when unknown).
pub fn download(url: &str, mut progress: impl FnMut(u64, u64)) -> Result<Vec<u8>, Failure> {
    let net = |e: String| Failure::Network(e);
    let response = agent().get(url).call().map_err(|e| net(e.to_string()))?;
    let total = response
        .header("content-length")
        .and_then(|v| v.parse().ok())
        .unwrap_or(0u64);
    let mut reader = response.into_reader();
    let mut bytes = Vec::with_capacity(total.min(MAX_INSTALLER as u64) as usize);
    let mut chunk = [0u8; 64 * 1024];
    loop {
        let n = reader.read(&mut chunk).map_err(|e| net(e.to_string()))?;
        if n == 0 {
            break;
        }
        bytes.extend_from_slice(&chunk[..n]);
        if bytes.len() > MAX_INSTALLER {
            return Err(net("the file is too large".into()));
        }
        progress(bytes.len() as u64, total);
    }
    if total != 0 && bytes.len() as u64 != total {
        return Err(net("the download was cut short".into()));
    }
    Ok(bytes)
}

/// Both values are base64 of minisign's text form, as the updater stores them.
pub fn verify(public_key: &str, signature: &str, bytes: &[u8]) -> Result<(), Failure> {
    let text = |v: &str| -> Result<String, Failure> {
        let raw = STANDARD.decode(v.trim()).map_err(|_| Failure::Signature)?;
        String::from_utf8(raw).map_err(|_| Failure::Signature)
    };
    let key = PublicKey::decode(&text(public_key)?).map_err(|_| Failure::Signature)?;
    let signature = Signature::decode(&text(signature)?).map_err(|_| Failure::Signature)?;
    key.verify(bytes, &signature, true)
        .map_err(|_| Failure::Signature)
}

#[cfg(test)]
mod tests {
    use super::*;

    const GOOD: &str = r#"{"version":"1.2.3","platforms":{
        "linux-x86_64":{"url":"https://example.com/a","signature":"x"},
        "windows-x86_64-nsis":{"url":"https://github.com/0giel/Aerion/releases/download/v1.2.3/Aerion_1.2.3_x64-setup.exe","signature":"sig"}}}"#;

    #[test]
    fn reads_the_windows_entry() {
        let r = parse_manifest(GOOD).unwrap();
        assert_eq!((r.version.as_str(), r.signature.as_str()), ("1.2.3", "sig"));
        assert!(r.url.ends_with("x64-setup.exe"));
    }

    #[test]
    fn refuses_foreign_addresses_and_broken_files() {
        let evil = GOOD.replace(
            "https://github.com/0giel/Aerion",
            "https://evil.example/0giel/Aerion",
        );
        assert!(matches!(parse_manifest(&evil), Err(Failure::Manifest(_))));
        let not_setup = GOOD.replace("x64-setup.exe", "x64.msi");
        assert!(matches!(
            parse_manifest(&not_setup),
            Err(Failure::Manifest(_))
        ));
        assert!(parse_manifest("not json").is_err());
        assert!(parse_manifest(r#"{"version":"1","platforms":{}}"#).is_err());
    }

    #[test]
    fn the_key_is_the_launchers() {
        let conf = include_str!("../../src-tauri/tauri.conf.json");
        assert!(
            conf.contains(PUBLIC_KEY),
            "update PUBLIC_KEY together with tauri.conf.json"
        );
    }

    #[test]
    fn garbage_signatures_are_refused() {
        assert_eq!(
            verify(PUBLIC_KEY, "bm90IGEgc2lnbmF0dXJl", b"x"),
            Err(Failure::Signature)
        );
        assert_eq!(verify(PUBLIC_KEY, "%%%", b"x"), Err(Failure::Signature));
    }

    /// Against the real newest release: `cargo test -- --ignored`.
    #[test]
    #[ignore]
    fn the_published_release_verifies() {
        let release = latest().unwrap();
        let mut bytes = download(&release.url, |_, _| {}).unwrap();
        verify(PUBLIC_KEY, &release.signature, &bytes).unwrap();
        bytes[1000] ^= 1;
        assert_eq!(
            verify(PUBLIC_KEY, &release.signature, &bytes),
            Err(Failure::Signature)
        );
    }
}
