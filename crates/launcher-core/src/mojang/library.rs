//! Maven coordinates and resolution of a version's libraries for a given environment.

use std::path::PathBuf;

use crate::mojang::rules::{Environment, rules_allow};
use crate::mojang::version::{Artifact, Library};
use crate::util::hash::Hash;

pub const MOJANG_LIBRARIES: &str = "https://libraries.minecraft.net/";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MavenCoord {
    pub group: String,
    pub artifact: String,
    pub version: String,
    pub classifier: Option<String>,
    pub ext: String,
}

impl MavenCoord {
    /// `group:artifact:version[:classifier][@ext]`
    pub fn parse(s: &str) -> Option<Self> {
        let (main, ext) = match s.split_once('@') {
            Some((m, e)) => (m, e.to_owned()),
            None => (s, "jar".to_owned()),
        };
        let parts: Vec<&str> = main.split(':').collect();
        let (group, artifact, version, classifier) = match parts.as_slice() {
            [g, a, v] => (g, a, v, None),
            [g, a, v, c] => (g, a, v, Some((*c).to_owned())),
            _ => return None,
        };
        if [group, artifact, version]
            .iter()
            .any(|p| p.is_empty() || p.contains("..") || p.contains('/'))
        {
            return None;
        }
        Some(Self {
            group: (*group).to_owned(),
            artifact: (*artifact).to_owned(),
            version: (*version).to_owned(),
            classifier,
            ext,
        })
    }

    /// Relative repository path, always with `/` separators.
    pub fn path(&self) -> String {
        let classifier = self
            .classifier
            .as_ref()
            .map(|c| format!("-{c}"))
            .unwrap_or_default();
        format!(
            "{}/{}/{}/{}-{}{}.{}",
            self.group.replace('.', "/"),
            self.artifact,
            self.version,
            self.artifact,
            self.version,
            classifier,
            self.ext
        )
    }

    /// Identity used to de-duplicate libraries across versions.
    pub fn key(&self) -> String {
        match &self.classifier {
            Some(c) => format!("{}:{}:{}", self.group, self.artifact, c),
            None => format!("{}:{}", self.group, self.artifact),
        }
    }

    pub fn with_classifier(&self, classifier: &str) -> Self {
        Self {
            classifier: Some(classifier.to_owned()),
            ..self.clone()
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LibKind {
    Classpath,
    /// Legacy native jar to unpack into the natives directory.
    Native {
        exclude: Vec<String>,
    },
}

#[derive(Debug, Clone)]
pub struct ResolvedLibrary {
    pub name: String,
    /// Relative to the libraries directory.
    pub path: PathBuf,
    /// `None` = not downloadable (must come from a loader installer).
    pub url: Option<String>,
    pub hash: Option<Hash>,
    pub size: Option<u64>,
    pub kind: LibKind,
}

fn rel_path(p: &str) -> PathBuf {
    p.split('/')
        .filter(|s| !s.is_empty() && *s != "..")
        .collect()
}

fn from_artifact(
    lib: &Library,
    art: &Artifact,
    fallback_path: Option<String>,
    kind: LibKind,
) -> Option<ResolvedLibrary> {
    let path = art.path.clone().or(fallback_path)?;
    Some(ResolvedLibrary {
        name: lib.name.clone(),
        path: rel_path(&path),
        url: Some(art.url.clone()).filter(|u| !u.is_empty()),
        hash: art.sha1.clone().filter(|s| !s.is_empty()).map(Hash::Sha1),
        size: art.size.filter(|s| *s > 0),
        kind,
    })
}

fn maven_hash(lib: &Library) -> Option<Hash> {
    lib.sha1
        .clone()
        .map(Hash::Sha1)
        .or_else(|| lib.sha256.clone().map(Hash::Sha256))
        .or_else(|| lib.sha512.clone().map(Hash::Sha512))
        .or_else(|| match lib.checksums.as_deref() {
            Some([single]) => Some(Hash::Sha1(single.clone())),
            _ => None,
        })
}

fn maven_url(base: Option<&str>, path: &str) -> String {
    let base = base.filter(|b| !b.is_empty()).unwrap_or(MOJANG_LIBRARIES);
    if base.ends_with('/') {
        format!("{base}{path}")
    } else {
        format!("{base}/{path}")
    }
}

/// Libraries that apply to `env`, in classpath order.
pub fn resolve_libraries(libs: &[Library], env: &Environment) -> Vec<ResolvedLibrary> {
    let mut out = Vec::new();
    for lib in libs {
        if !rules_allow(&lib.rules, env) || lib.clientreq == Some(false) {
            continue;
        }
        let coord = MavenCoord::parse(&lib.name);
        let downloads = lib.downloads.as_ref();

        if let Some(natives) = &lib.natives {
            if let Some(template) = natives.get(env.os_key()) {
                let classifier = template.replace("${arch}", env.arch_bits());
                let exclude = lib
                    .extract
                    .as_ref()
                    .map(|e| e.exclude.clone())
                    .unwrap_or_default();
                let kind = LibKind::Native { exclude };
                let fallback = coord
                    .as_ref()
                    .map(|c| c.with_classifier(&classifier).path());
                let resolved = match downloads
                    .and_then(|d| d.classifiers.as_ref())
                    .and_then(|c| c.get(&classifier))
                {
                    Some(art) => from_artifact(lib, art, fallback, kind),
                    None => fallback.map(|path| ResolvedLibrary {
                        name: lib.name.clone(),
                        url: Some(maven_url(lib.url.as_deref(), &path)),
                        path: rel_path(&path),
                        hash: None,
                        size: None,
                        kind,
                    }),
                };
                out.extend(resolved);
            }
            if let Some(art) = downloads.and_then(|d| d.artifact.as_ref()) {
                out.extend(from_artifact(
                    lib,
                    art,
                    coord.as_ref().map(MavenCoord::path),
                    LibKind::Classpath,
                ));
            }
            continue;
        }

        if let Some(art) = downloads.and_then(|d| d.artifact.as_ref()) {
            out.extend(from_artifact(
                lib,
                art,
                coord.as_ref().map(MavenCoord::path),
                LibKind::Classpath,
            ));
        } else if let Some(c) = &coord {
            let path = c.path();
            out.push(ResolvedLibrary {
                name: lib.name.clone(),
                url: Some(maven_url(lib.url.as_deref(), &path)),
                path: rel_path(&path),
                hash: maven_hash(lib),
                size: lib.size.filter(|s| *s > 0),
                kind: LibKind::Classpath,
            });
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mojang::version::VersionJson;
    use crate::util::sys::{Arch, Os};

    fn fixture(name: &str) -> VersionJson {
        let path = format!("{}/tests/fixtures/{name}", env!("CARGO_MANIFEST_DIR"));
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    #[test]
    fn maven_paths() {
        let c = MavenCoord::parse("net.fabricmc:fabric-loader:0.20.1").unwrap();
        assert_eq!(
            c.path(),
            "net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar"
        );
        let c = MavenCoord::parse("org.lwjgl:lwjgl:3.4.3:natives-windows").unwrap();
        assert_eq!(
            c.path(),
            "org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3-natives-windows.jar"
        );
        assert_eq!(c.key(), "org.lwjgl:lwjgl:natives-windows");
        let c =
            MavenCoord::parse("de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412@zip").unwrap();
        assert_eq!(
            c.path(),
            "de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412.zip"
        );
        assert!(MavenCoord::parse("a:../b:1").is_none());
        assert!(MavenCoord::parse("just-a-name").is_none());
    }

    #[test]
    fn legacy_natives_per_os() {
        let v = fixture("versions/1.8.9.json");
        let natives = |os, arch| -> Vec<String> {
            resolve_libraries(&v.libraries, &Environment::for_os(os, arch))
                .into_iter()
                .filter(|l| matches!(l.kind, LibKind::Native { .. }))
                .map(|l| l.path.to_string_lossy().replace('\\', "/"))
                .collect()
        };
        let win = natives(Os::Windows, Arch::X64);
        assert!(
            win.iter()
                .any(|p| p.ends_with("lwjgl-platform-2.9.4-nightly-20150209-natives-windows.jar"))
        );
        assert!(
            win.iter()
                .any(|p| p.ends_with("twitch-platform-6.5-natives-windows-64.jar")),
            "${{arch}} expands to 64"
        );
        let win32 = natives(Os::Windows, Arch::X86);
        assert!(
            win32
                .iter()
                .any(|p| p.ends_with("twitch-platform-6.5-natives-windows-32.jar"))
        );
        let mac = natives(Os::Macos, Arch::X64);
        assert!(
            mac.iter()
                .any(|p| p.contains("lwjgl-platform-2.9.2-nightly")),
            "macOS uses lwjgl 2.9.2"
        );
        assert!(!mac.iter().any(|p| p.contains("2.9.4")));
        let linux = natives(Os::Linux, Arch::X64);
        assert!(
            !linux.iter().any(|p| p.contains("twitch-platform")),
            "twitch natives are disallowed on linux"
        );
    }

    #[test]
    fn modern_natives_are_classpath_jars() {
        let v = fixture("versions/26.3.json");
        let linux = resolve_libraries(&v.libraries, &Environment::for_os(Os::Linux, Arch::X64));
        assert!(linux.iter().all(|l| l.kind == LibKind::Classpath));
        assert!(
            linux
                .iter()
                .any(|l| l.name == "org.lwjgl:lwjgl:3.4.3:natives-linux")
        );
        assert!(!linux.iter().any(|l| l.name.ends_with("natives-windows")));
        let win = resolve_libraries(&v.libraries, &Environment::for_os(Os::Windows, Arch::X64));
        assert!(
            win.iter()
                .any(|l| l.name == "org.lwjgl:lwjgl:3.4.3:natives-windows")
        );
        assert!(win.iter().all(
            |l| l.url.as_deref().is_some_and(|u| u.starts_with("https://")) && l.hash.is_some()
        ));
    }

    #[test]
    fn fabric_maven_libraries() {
        let v = fixture("loaders/fabric-profile-1.21.11.json");
        let libs = resolve_libraries(&v.libraries, &Environment::for_os(Os::Linux, Arch::X64));
        let loader = libs
            .iter()
            .find(|l| l.name.starts_with("net.fabricmc:fabric-loader"))
            .unwrap();
        assert_eq!(
            loader.url.as_deref(),
            Some(
                "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar"
            )
        );
        // Fabric meta omits hashes for the loader/intermediary; the loader installer fills
        // them in from the Maven `.sha1` files.
        assert!(loader.hash.is_none());
        let asm = libs
            .iter()
            .find(|l| l.name.starts_with("org.ow2.asm:asm:"))
            .unwrap();
        assert!(matches!(asm.hash, Some(Hash::Sha1(_))));
        assert_eq!(asm.size, Some(126151));
    }
}
