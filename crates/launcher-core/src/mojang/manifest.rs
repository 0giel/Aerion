//! Mojang version manifest.

use serde::{Deserialize, Serialize};

pub const MANIFEST_URL: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionManifest {
    pub latest: Latest,
    pub versions: Vec<ManifestEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Latest {
    pub release: String,
    pub snapshot: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestEntry {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub url: String,
    #[serde(default)]
    pub release_time: String,
    #[serde(default)]
    pub sha1: Option<String>,
}

impl VersionManifest {
    pub fn get(&self, id: &str) -> Option<&ManifestEntry> {
        self.versions.iter().find(|v| v.id == id)
    }

    /// Position in the manifest (0 = newest). The manifest is ordered by release time, which is
    /// the only reliable ordering since ids switched to `year.drop` (26.1, 26.3, …).
    pub fn rank(&self, id: &str) -> Option<usize> {
        self.versions.iter().position(|v| v.id == id)
    }

    /// `a` is newer than or equal to `b` (unknown ids count as newest, e.g. loader-only ids).
    pub fn is_at_least(&self, a: &str, b: &str) -> bool {
        match (self.rank(a), self.rank(b)) {
            (Some(ra), Some(rb)) => ra <= rb,
            (None, _) => true,
            (_, None) => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ordering_uses_manifest_position() {
        let path = format!(
            "{}/tests/fixtures/versions/manifest-subset.json",
            env!("CARGO_MANIFEST_DIR")
        );
        let m: VersionManifest =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        assert_eq!(m.latest.release, "26.3");
        assert!(
            m.is_at_least("26.1", "1.21.11"),
            "26.1 is newer than 1.21.11 despite string order"
        );
        assert!(m.is_at_least("1.21.11", "1.8.9"));
        assert!(!m.is_at_least("1.8.9", "1.12.2"));
        assert_eq!(m.get("26.4-snapshot-6").unwrap().kind, "snapshot");
    }
}
