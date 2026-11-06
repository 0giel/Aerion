//! Version JSON model (vanilla `client.json`, loader profiles) and `inheritsFrom` merging.

use std::collections::{BTreeMap, HashSet};

use serde::{Deserialize, Serialize};

use crate::mojang::library::MavenCoord;
use crate::mojang::rules::Rule;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionJson {
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub inherits_from: Option<String>,
    #[serde(rename = "type", default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub main_class: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub minecraft_arguments: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub arguments: Option<Arguments>,
    #[serde(default)]
    pub libraries: Vec<Library>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub asset_index: Option<AssetIndexRef>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub assets: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub downloads: Option<VersionDownloads>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub java_version: Option<JavaVersionReq>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub logging: Option<Logging>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub release_time: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub jar: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<Argument>,
    #[serde(default)]
    pub jvm: Vec<Argument>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum Argument {
    Plain(String),
    Conditional { rules: Vec<Rule>, value: ArgValue },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum ArgValue {
    One(String),
    Many(Vec<String>),
}

impl ArgValue {
    pub fn values(&self) -> &[String] {
        match self {
            ArgValue::One(s) => std::slice::from_ref(s),
            ArgValue::Many(v) => v,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Library {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub downloads: Option<LibraryDownloads>,
    /// Maven repository base (Fabric/Quilt profiles, legacy Forge).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub natives: Option<BTreeMap<String, String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub extract: Option<Extract>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub rules: Vec<Rule>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sha1: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sha256: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sha512: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub size: Option<u64>,
    /// Legacy Forge: list of acceptable SHA-1s.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub checksums: Option<Vec<String>>,
    /// Legacy Forge: `false` marks server-only libraries.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub clientreq: Option<bool>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct LibraryDownloads {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub artifact: Option<Artifact>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub classifiers: Option<BTreeMap<String, Artifact>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Artifact {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(default)]
    pub url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sha1: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub size: Option<u64>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Extract {
    #[serde(default)]
    pub exclude: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetIndexRef {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub total_size: Option<u64>,
    pub url: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct VersionDownloads {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub client: Option<Artifact>,
    /// The dedicated server jar (Horizon prepares its resources from it).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server: Option<Artifact>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaVersionReq {
    #[serde(default = "default_component")]
    pub component: String,
    pub major_version: u32,
}

fn default_component() -> String {
    "jre-legacy".to_owned()
}

impl Default for JavaVersionReq {
    /// Versions without `javaVersion` predate the field and run on Java 8.
    fn default() -> Self {
        Self {
            component: default_component(),
            major_version: 8,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Logging {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub client: Option<LoggingConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoggingConfig {
    pub argument: String,
    pub file: LoggingFile,
    #[serde(rename = "type", default)]
    pub kind: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoggingFile {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

impl VersionJson {
    pub fn java_requirement(&self) -> JavaVersionReq {
        self.java_version.clone().unwrap_or_default()
    }

    pub fn asset_index_id(&self) -> Option<String> {
        self.asset_index
            .as_ref()
            .map(|a| a.id.clone())
            .or_else(|| self.assets.clone())
    }
}

fn lib_key(lib: &Library) -> String {
    MavenCoord::parse(&lib.name)
        .map(|c| c.key())
        .unwrap_or_else(|| lib.name.clone())
}

/// Merge a child profile (loader) onto its parent, like the official launcher:
/// child libraries first (and replacing parent libraries with the same group:artifact[:classifier]),
/// parent arguments followed by child arguments, scalar fields from the child when present.
pub fn merge(child: VersionJson, parent: VersionJson) -> VersionJson {
    let child_keys: HashSet<String> = child.libraries.iter().map(lib_key).collect();
    let mut libraries = child.libraries;
    libraries.extend(
        parent
            .libraries
            .into_iter()
            .filter(|l| !child_keys.contains(&lib_key(l))),
    );

    let arguments = match (parent.arguments, child.arguments) {
        (Some(mut p), Some(c)) => {
            p.game.extend(c.game);
            p.jvm.extend(c.jvm);
            Some(p)
        }
        (p, c) => c.or(p),
    };

    VersionJson {
        id: child.id,
        inherits_from: parent.inherits_from,
        kind: child.kind.or(parent.kind),
        main_class: child.main_class.or(parent.main_class),
        minecraft_arguments: child.minecraft_arguments.or(parent.minecraft_arguments),
        arguments,
        libraries,
        asset_index: child.asset_index.or(parent.asset_index),
        assets: child.assets.or(parent.assets),
        downloads: child.downloads.or(parent.downloads),
        java_version: child.java_version.or(parent.java_version),
        logging: child.logging.or(parent.logging),
        release_time: parent.release_time.or(child.release_time),
        jar: child.jar.or(parent.jar),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    pub(crate) fn fixture(name: &str) -> VersionJson {
        let path = format!("{}/tests/fixtures/{name}", env!("CARGO_MANIFEST_DIR"));
        serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap()
    }

    #[test]
    fn parses_all_fixture_generations() {
        for (file, java, legacy) in [
            ("versions/1.8.9.json", 8, true),
            ("versions/1.12.2.json", 8, true),
            ("versions/1.16.5.json", 8, false),
            ("versions/1.20.1.json", 17, false),
            ("versions/1.21.11.json", 21, false),
            ("versions/26.3.json", 25, false),
            ("versions/26.4-snapshot-6.json", 25, false),
        ] {
            let v = fixture(file);
            assert_eq!(v.java_requirement().major_version, java, "{file}");
            assert_eq!(v.minecraft_arguments.is_some(), legacy, "{file}");
            assert!(
                v.downloads
                    .as_ref()
                    .and_then(|d| d.client.as_ref())
                    .is_some(),
                "{file}"
            );
            assert!(v.logging.is_some(), "{file}");
            assert!(!v.libraries.is_empty());
        }
    }

    #[test]
    fn merges_fabric_profile_onto_vanilla() {
        let vanilla = fixture("versions/26.3.json");
        let fabric = fixture("loaders/fabric-profile-26.3.json");
        let vanilla_libs = vanilla.libraries.len();
        let merged = merge(fabric, vanilla);
        assert_eq!(merged.id, "fabric-loader-0.20.1-26.3");
        assert_eq!(
            merged.main_class.as_deref(),
            Some("net.fabricmc.loader.impl.launch.knot.KnotClient")
        );
        assert_eq!(merged.libraries.len(), vanilla_libs + 7);
        assert!(
            merged.libraries[0].name.starts_with("org.ow2.asm:asm:"),
            "loader libraries come first"
        );
        let jvm = &merged.arguments.as_ref().unwrap().jvm;
        assert!(matches!(jvm.last(), Some(Argument::Plain(s)) if s.starts_with("-DFabricMcEmu=")));
        assert_eq!(merged.java_requirement().major_version, 25);
        assert_eq!(merged.asset_index_id().as_deref(), Some("34"));
    }

    #[test]
    fn child_library_replaces_parent_version() {
        let parent: VersionJson = serde_json::from_value(serde_json::json!({
            "id": "p", "libraries": [{"name": "com.google.guava:guava:21.0"}, {"name": "a:b:1"}]
        }))
        .unwrap();
        let child: VersionJson = serde_json::from_value(serde_json::json!({
            "id": "c", "inheritsFrom": "p", "libraries": [{"name": "com.google.guava:guava:32.1"}]
        }))
        .unwrap();
        let merged = merge(child, parent);
        let names: Vec<_> = merged.libraries.iter().map(|l| l.name.as_str()).collect();
        assert_eq!(names, ["com.google.guava:guava:32.1", "a:b:1"]);
    }
}
