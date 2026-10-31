//! Managed Java runtimes: Mojang's runtime repository first, Eclipse Adoptium as a fallback.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::Deserialize;

use crate::error::{Error, IoContext, Result};
use crate::java::detect::inspect;
use crate::java::{JavaInstall, JavaSource, java_exe_name};
use crate::net::cache::{DAY, HttpCache};
use crate::net::download::{DownloadItem, Downloader, VerifyMode};
use crate::paths::Paths;
use crate::progress::TaskCtx;
use crate::util::fs::{atomic_write, safe_join};
use crate::util::hash::Hash;
use crate::util::sys::{Arch, Os};

pub const MOJANG_RUNTIMES: &str = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
const MARKER: &str = ".luncher-runtime";

type RuntimeIndex = BTreeMap<String, BTreeMap<String, Vec<RuntimeEntry>>>;

#[derive(Debug, Deserialize)]
struct RuntimeEntry {
    manifest: ManifestRef,
    version: RuntimeVersion,
}

#[derive(Debug, Deserialize)]
struct ManifestRef {
    sha1: String,
    url: String,
}

#[derive(Debug, Deserialize)]
struct RuntimeVersion {
    name: String,
}

#[derive(Debug, Deserialize)]
struct RuntimeManifest {
    files: BTreeMap<String, RuntimeFile>,
}

#[derive(Debug, Deserialize)]
struct RuntimeFile {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    executable: bool,
    #[serde(default)]
    downloads: Option<RuntimeDownloads>,
    #[serde(default)]
    target: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RuntimeDownloads {
    raw: RawDownload,
}

#[derive(Debug, Deserialize)]
struct RawDownload {
    sha1: String,
    size: u64,
    url: String,
}

/// Mojang platform keys to try, best first. ARM Windows and Apple Silicon fall back to x64
/// runtimes, which run under the OS's emulation (needed for e.g. Java 8 on Apple Silicon).
pub fn mojang_platforms(os: Os, arch: Arch) -> Vec<&'static str> {
    match (os, arch) {
        (Os::Windows, Arch::X64) => vec!["windows-x64"],
        (Os::Windows, Arch::X86) => vec!["windows-x86"],
        (Os::Windows, Arch::Arm64) => vec!["windows-arm64", "windows-x64"],
        (Os::Macos, Arch::Arm64) => vec!["mac-os-arm64", "mac-os"],
        (Os::Macos, _) => vec!["mac-os"],
        (Os::Linux, Arch::X64) => vec!["linux"],
        (Os::Linux, Arch::X86) => vec!["linux-i386"],
        _ => vec![],
    }
}

fn find_java(dir: &Path) -> Option<PathBuf> {
    let exe = java_exe_name();
    let direct = [
        dir.join("bin").join(exe),
        dir.join("jre.bundle/Contents/Home/bin").join(exe),
        dir.join("Contents/Home/bin").join(exe),
    ];
    if let Some(p) = direct.into_iter().find(|p| p.is_file()) {
        return Some(p);
    }
    // Archives usually wrap everything in one top-level folder (jdk-25.0.5+8-jre/...).
    std::fs::read_dir(dir)
        .ok()?
        .flatten()
        .filter(|e| e.path().is_dir())
        .find_map(|e| {
            let d = e.path();
            [
                d.join("bin").join(exe),
                d.join("Contents/Home/bin").join(exe),
            ]
            .into_iter()
            .find(|p| p.is_file())
        })
}

pub struct RuntimeManager<'a> {
    pub paths: &'a Paths,
    pub cache: &'a HttpCache,
    pub downloader: &'a Downloader,
    pub os: Os,
    pub arch: Arch,
}

impl RuntimeManager<'_> {
    fn mojang_dir(&self, platform: &str, component: &str) -> PathBuf {
        self.paths
            .runtimes_dir()
            .join("mojang")
            .join(platform)
            .join(component)
    }

    fn adoptium_dir(&self, major: u32) -> PathBuf {
        self.paths
            .runtimes_dir()
            .join("adoptium")
            .join(format!("{major}"))
    }

    /// Already-installed managed runtime for `component`/`major`, without network access.
    pub async fn installed(&self, component: &str, major: u32) -> Option<JavaInstall> {
        for platform in mojang_platforms(self.os, self.arch) {
            let dir = self.mojang_dir(platform, component);
            if dir.join(MARKER).is_file()
                && let Some(java) = find_java(&dir)
                && let Ok(i) = inspect(&java, JavaSource::Mojang).await
            {
                return Some(i);
            }
        }
        let dir = self.adoptium_dir(major);
        if dir.join(MARKER).is_file()
            && let Some(java) = find_java(&dir)
        {
            return inspect(&java, JavaSource::Adoptium).await.ok();
        }
        None
    }

    /// Make sure a runtime for the version requirement exists, installing it if needed.
    pub async fn ensure(&self, component: &str, major: u32, ctx: &TaskCtx) -> Result<JavaInstall> {
        if let Some(i) = self.installed(component, major).await {
            return Ok(i);
        }
        match self.install_mojang(component, ctx).await {
            Ok(Some(i)) => return Ok(i),
            Ok(None) => tracing::info!(
                component,
                "no Mojang runtime for this platform, trying Adoptium"
            ),
            Err(Error::Cancelled) => return Err(Error::Cancelled),
            Err(e) => {
                tracing::warn!(error = %e, component, "Mojang runtime install failed, trying Adoptium")
            }
        }
        self.install_adoptium(major, ctx).await
    }

    async fn install_mojang(&self, component: &str, ctx: &TaskCtx) -> Result<Option<JavaInstall>> {
        let index: RuntimeIndex = self.cache.get_json(MOJANG_RUNTIMES, DAY).await?;
        for platform in mojang_platforms(self.os, self.arch) {
            let Some(entry) = index
                .get(platform)
                .and_then(|p| p.get(component))
                .and_then(|v| v.first())
            else {
                continue;
            };
            ctx.stage(
                "stage.javaInstall",
                Some(format!("Java {}", entry.version.name)),
            );
            let dir = self.mojang_dir(platform, component);
            let manifest_path = dir.with_extension("manifest.json");
            let item = DownloadItem::new(&entry.manifest.url, &manifest_path)
                .sha1(Some(&entry.manifest.sha1));
            self.downloader
                .run(vec![item], VerifyMode::Full, ctx)
                .await?;
            let bytes = tokio::fs::read(&manifest_path).await.at(&manifest_path)?;
            let manifest: RuntimeManifest = serde_json::from_slice(&bytes)
                .map_err(|e| Error::parse("java runtime manifest", e))?;

            let mut items = Vec::new();
            let mut links = Vec::new();
            for (rel, file) in &manifest.files {
                let path = safe_join(&dir, rel)?;
                match file.kind.as_str() {
                    "directory" => tokio::fs::create_dir_all(&path).await.at(&path)?,
                    "file" => {
                        if let Some(d) = &file.downloads {
                            items.push(
                                DownloadItem::new(&d.raw.url, path)
                                    .sha1(Some(&d.raw.sha1))
                                    .size(Some(d.raw.size))
                                    .executable(file.executable),
                            );
                        }
                    }
                    "link" => {
                        if let Some(target) = &file.target {
                            links.push((path, target.clone()));
                        }
                    }
                    _ => {}
                }
            }
            self.downloader.run(items, VerifyMode::Fast, ctx).await?;
            #[cfg(unix)]
            for (path, target) in links {
                if target.starts_with('/') || target.split('/').filter(|p| *p == "..").count() > 8 {
                    return Err(Error::UnsafePath(target));
                }
                let _ = tokio::fs::remove_file(&path).await;
                if let Some(parent) = path.parent() {
                    tokio::fs::create_dir_all(parent).await.at(parent)?;
                }
                tokio::fs::symlink(&target, &path).await.at(&path)?;
            }
            #[cfg(not(unix))]
            drop(links);
            atomic_write(dir.join(MARKER), &entry.version.name).await?;
            let java = find_java(&dir).ok_or_else(|| Error::JavaInvalid {
                path: dir.clone(),
                reason: "runtime has no java executable".into(),
            })?;
            return inspect(&java, JavaSource::Mojang).await.map(Some);
        }
        Ok(None)
    }

    async fn install_adoptium(&self, major: u32, ctx: &TaskCtx) -> Result<JavaInstall> {
        let os = match self.os {
            Os::Windows => "windows",
            Os::Macos => "mac",
            Os::Linux => "linux",
        };
        let arch = match self.arch {
            Arch::X64 => "x64",
            Arch::Arm64 => "aarch64",
            Arch::X86 => "x86",
            Arch::Arm => "arm",
        };
        #[derive(Deserialize)]
        struct Asset {
            binary: Binary,
            release_name: String,
        }
        #[derive(Deserialize)]
        struct Binary {
            package: Package,
        }
        #[derive(Deserialize)]
        struct Package {
            link: String,
            checksum: String,
            size: u64,
            name: String,
        }
        let mut asset = None;
        for image in ["jre", "jdk"] {
            let url = format!(
                "https://api.adoptium.net/v3/assets/latest/{major}/hotspot?architecture={arch}&image_type={image}&os={os}&vendor=eclipse"
            );
            let list: Vec<Asset> = self
                .downloader
                .http()
                .get_json(&url)
                .await
                .unwrap_or_default();
            if let Some(a) = list.into_iter().next() {
                asset = Some(a);
                break;
            }
        }
        let asset = asset.ok_or(Error::JavaNotFound(major))?;
        ctx.stage("stage.javaInstall", Some(asset.release_name.clone()));
        let pkg = asset.binary.package;
        if pkg.name.contains(['/', '\\']) {
            return Err(Error::UnsafePath(pkg.name));
        }
        let archive = self.paths.temp_dir().join(&pkg.name);
        let item = DownloadItem::new(&pkg.link, &archive)
            .hash(Some(Hash::Sha256(pkg.checksum)))
            .size(Some(pkg.size));
        self.downloader
            .run(vec![item], VerifyMode::Full, ctx)
            .await?;

        let dir = self.adoptium_dir(major);
        let _ = tokio::fs::remove_dir_all(&dir).await;
        let (a, d) = (archive.clone(), dir.clone());
        tokio::task::spawn_blocking(move || {
            if a.extension().is_some_and(|e| e == "zip") {
                crate::util::archive::extract_zip(&a, &d, &|_| true).map(|_| ())
            } else {
                crate::util::archive::extract_tar_gz(&a, &d)
            }
        })
        .await
        .map_err(|e| Error::Invalid(e.to_string()))??;
        let _ = tokio::fs::remove_file(&archive).await;
        atomic_write(dir.join(MARKER), &asset.release_name).await?;
        let java = find_java(&dir).ok_or(Error::JavaNotFound(major))?;
        inspect(&java, JavaSource::Adoptium).await
    }

    /// All managed runtimes on disk (for the Java list in settings).
    pub async fn list_installed(&self) -> Vec<JavaInstall> {
        let mut out = Vec::new();
        let mojang = self.paths.runtimes_dir().join("mojang");
        for platform in std::fs::read_dir(&mojang).into_iter().flatten().flatten() {
            for comp in std::fs::read_dir(platform.path())
                .into_iter()
                .flatten()
                .flatten()
            {
                let dir = comp.path();
                if dir.join(MARKER).is_file()
                    && let Some(java) = find_java(&dir)
                    && let Ok(i) = inspect(&java, JavaSource::Mojang).await
                {
                    out.push(i);
                }
            }
        }
        let adoptium = self.paths.runtimes_dir().join("adoptium");
        for d in std::fs::read_dir(&adoptium).into_iter().flatten().flatten() {
            if d.path().join(MARKER).is_file()
                && let Some(java) = find_java(&d.path())
                && let Ok(i) = inspect(&java, JavaSource::Adoptium).await
            {
                out.push(i);
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn platform_fallbacks() {
        assert_eq!(
            mojang_platforms(Os::Macos, Arch::Arm64),
            ["mac-os-arm64", "mac-os"]
        );
        assert_eq!(
            mojang_platforms(Os::Windows, Arch::Arm64),
            ["windows-arm64", "windows-x64"]
        );
        assert!(
            mojang_platforms(Os::Linux, Arch::Arm64).is_empty(),
            "Linux ARM goes straight to Adoptium"
        );
    }

    #[test]
    fn runtime_index_fixture_has_java_25() {
        let path = format!(
            "{}/tests/fixtures/java/all.json",
            env!("CARGO_MANIFEST_DIR")
        );
        let index: RuntimeIndex =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let e = &index["windows-x64"]["java-runtime-epsilon"][0];
        assert!(e.version.name.starts_with("25"));
        assert!(
            index["mac-os-arm64"]["jre-legacy"].is_empty(),
            "no Java 8 for Apple Silicon → x64 fallback"
        );
        let path = format!(
            "{}/tests/fixtures/java/runtime-manifest-sample.json",
            env!("CARGO_MANIFEST_DIR")
        );
        let m: RuntimeManifest =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        assert!(
            m.files
                .values()
                .any(|f| f.kind == "file" && f.downloads.is_some())
        );
    }
}
