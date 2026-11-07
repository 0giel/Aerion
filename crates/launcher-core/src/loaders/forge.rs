//! Forge and NeoForge through their official installer jars.
//!
//! Handles all three install profile generations:
//! 1. legacy (`install` + embedded `versionInfo`, universal jar inside the installer; ≤ 1.12.2),
//! 2. spec 0 with `/version.json` and libraries shipped in the installer's `maven/` folder,
//! 3. spec 1 with processors (1.13+, NeoForge) run headlessly with the instance's Java.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::Deserialize;

use crate::Engine;
use crate::error::{Error, IoContext, Result};
use crate::game::install::prepare;
use crate::loaders::LoaderKind;
use crate::loaders::{LoaderVersion, is_prerelease, safe_version_id};
use crate::mojang::library::{MavenCoord, resolve_libraries};
use crate::mojang::version::{Library, VersionJson};
use crate::net::cache::HOUR;
use crate::net::download::{DownloadItem, VerifyMode};
use crate::paths::display_path;
use crate::progress::TaskCtx;
use crate::util::archive::{extract_zip_entry, list_zip_entries, read_zip_entry};
use crate::util::fs::write_json;
use crate::util::hash::{Hash, sha1_file};

const FORGE_MAVEN: &str = "https://maven.minecraftforge.net";
const FORGE_METADATA: &str =
    "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
const FORGE_PROMOS: &str =
    "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
const NEO_MAVEN: &str = "https://maven.neoforged.net/releases";
const NEO_VERSIONS: &str =
    "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";
const NEO_LEGACY_VERSIONS: &str =
    "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/forge";

fn version_key(v: &str) -> Vec<u64> {
    v.split(|c: char| !c.is_ascii_digit())
        .filter(|s| !s.is_empty())
        .map(|s| s.parse().unwrap_or(0))
        .collect()
}

fn sort_desc(list: &mut [LoaderVersion]) {
    list.sort_by_key(|v| std::cmp::Reverse(version_key(&v.version)));
}

/// `<version>` entries of a Maven metadata file.
pub fn parse_maven_versions(xml: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut rest = xml;
    while let Some(s) = rest.find("<version>") {
        let after = &rest[s + 9..];
        let Some(e) = after.find("</version>") else {
            break;
        };
        out.push(after[..e].trim().to_owned());
        rest = &after[e + 10..];
    }
    out
}

#[derive(Deserialize)]
struct Promotions {
    promos: BTreeMap<String, String>,
}

pub async fn forge_versions(engine: &Engine, game: &str) -> Result<Vec<LoaderVersion>> {
    let xml = String::from_utf8_lossy(&engine.cache.get(FORGE_METADATA, HOUR).await?).into_owned();
    let promos: Option<Promotions> = engine.cache.get_json(FORGE_PROMOS, HOUR).await.ok();
    let recommended = promos
        .as_ref()
        .and_then(|p| p.promos.get(&format!("{game}-recommended")).cloned());
    let prefix = format!("{game}-");
    let mut list: Vec<LoaderVersion> = parse_maven_versions(&xml)
        .into_iter()
        .filter_map(|v| v.strip_prefix(&prefix).map(str::to_owned))
        .map(|v| LoaderVersion {
            stable: recommended
                .as_deref()
                .is_some_and(|r| v == r || v.starts_with(&format!("{r}-"))),
            version: v,
        })
        .collect();
    sort_desc(&mut list);
    Ok(list)
}

/// Minecraft version a NeoForge version targets: `21.1.77` → `1.21.1`, `26.3.0.99` → `26.3`.
pub fn neoforge_game_version(v: &str) -> Option<String> {
    let core = v.split('-').next()?;
    let parts: Vec<u32> = core
        .split('.')
        .map(|p| p.parse().ok())
        .collect::<Option<_>>()?;
    match parts.as_slice() {
        [0, ..] => None,
        [y, d, h, _build] if *y >= 25 => Some(if *h == 0 {
            format!("{y}.{d}")
        } else {
            format!("{y}.{d}.{h}")
        }),
        [minor, patch, _build] if (20..25).contains(minor) => Some(if *patch == 0 {
            format!("1.{minor}")
        } else {
            format!("1.{minor}.{patch}")
        }),
        _ => None,
    }
}

#[derive(Deserialize)]
struct NeoVersions {
    versions: Vec<String>,
}

pub async fn neoforge_versions(engine: &Engine, game: &str) -> Result<Vec<LoaderVersion>> {
    let mut list: Vec<LoaderVersion> = if game == "1.20.1" {
        let v: NeoVersions = engine.cache.get_json(NEO_LEGACY_VERSIONS, HOUR).await?;
        v.versions
            .into_iter()
            .filter_map(|v| v.strip_prefix("1.20.1-").map(str::to_owned))
            .map(|v| LoaderVersion {
                stable: !is_prerelease(&v),
                version: v,
            })
            .collect()
    } else {
        let v: NeoVersions = engine.cache.get_json(NEO_VERSIONS, HOUR).await?;
        v.versions
            .into_iter()
            .filter(|v| neoforge_game_version(v).as_deref() == Some(game))
            .map(|v| LoaderVersion {
                stable: !is_prerelease(&v),
                version: v,
            })
            .collect()
    };
    sort_desc(&mut list);
    Ok(list)
}

fn installer_url(kind: LoaderKind, game: &str, version: &str) -> Result<(String, String)> {
    if version.contains(['/', '\\']) || version.contains("..") {
        return Err(Error::UnsafePath(version.to_owned()));
    }
    Ok(match kind {
        LoaderKind::Forge => {
            let full = format!("{game}-{version}");
            let name = format!("forge-{full}-installer.jar");
            (
                format!("{FORGE_MAVEN}/net/minecraftforge/forge/{full}/{name}"),
                name,
            )
        }
        LoaderKind::Neoforge if game == "1.20.1" => {
            let full = format!("1.20.1-{version}");
            let name = format!("forge-{full}-installer.jar");
            (
                format!("{NEO_MAVEN}/net/neoforged/forge/{full}/{name}"),
                format!("neoforge-{name}"),
            )
        }
        LoaderKind::Neoforge => {
            let name = format!("neoforge-{version}-installer.jar");
            (
                format!("{NEO_MAVEN}/net/neoforged/neoforge/{version}/{name}"),
                name,
            )
        }
        _ => return Err(Error::Invalid("not an installer-based loader".into())),
    })
}

#[derive(Debug, Deserialize)]
struct InstallProfile {
    #[serde(default)]
    json: Option<String>,
    #[serde(default)]
    data: BTreeMap<String, DataEntry>,
    #[serde(default)]
    processors: Vec<Processor>,
    #[serde(default)]
    libraries: Vec<Library>,
}

#[derive(Debug, Deserialize)]
struct DataEntry {
    client: String,
}

#[derive(Debug, Deserialize)]
struct Processor {
    jar: String,
    #[serde(default)]
    classpath: Vec<String>,
    #[serde(default)]
    args: Vec<String>,
    #[serde(default)]
    outputs: BTreeMap<String, String>,
    #[serde(default)]
    sides: Option<Vec<String>>,
}

async fn blocking<T: Send + 'static>(f: impl FnOnce() -> Result<T> + Send + 'static) -> Result<T> {
    tokio::task::spawn_blocking(f)
        .await
        .map_err(|e| Error::Invalid(e.to_string()))?
}

/// Install a Forge or NeoForge version and return the launchable version id.
pub async fn install(
    engine: &Engine,
    kind: LoaderKind,
    game: &str,
    version: &str,
    ctx: &TaskCtx,
) -> Result<String> {
    let (url, name) = installer_url(kind, game, version)?;
    let installer = engine.paths.installers_dir().join(&name);
    let sha1 = engine
        .http
        .get_bytes(&format!("{url}.sha1"))
        .await
        .ok()
        .map(|b| String::from_utf8_lossy(&b).trim().to_owned())
        .filter(|s| s.len() == 40);
    let item = DownloadItem::new(&url, &installer).hash(sha1.map(Hash::Sha1));
    match engine
        .downloader
        .run(vec![item], VerifyMode::Full, ctx)
        .await
    {
        Err(Error::Http { status: 404, .. }) => {
            return Err(Error::LoaderUnavailable {
                loader: kind.as_str().into(),
                version: version.into(),
                game_version: game.into(),
            });
        }
        r => r?,
    }

    // Processors need the vanilla client jar and libraries.
    let vanilla = prepare(&engine.meta, &engine.env, game, VerifyMode::Fast, ctx).await?;
    ctx.stage("stage.loader", Some(format!("{} {version}", kind.as_str())));

    let inst = installer.clone();
    let profile_bytes = blocking(move || read_zip_entry(&inst, "install_profile.json"))
        .await?
        .ok_or_else(|| Error::LoaderInstall("installer has no install_profile.json".into()))?;
    let raw: serde_json::Value = serde_json::from_slice(&profile_bytes)
        .map_err(|e| Error::parse("install_profile.json", e))?;
    let libs_dir = engine.paths.libraries_dir();

    let mut version_json: VersionJson = if let Some(info) = raw.get("versionInfo") {
        // Generation 1: everything is in install_profile.json.
        let mut v: VersionJson =
            serde_json::from_value(info.clone()).map_err(|e| Error::parse("versionInfo", e))?;
        let file_path = raw
            .pointer("/install/filePath")
            .and_then(|x| x.as_str())
            .unwrap_or_default()
            .to_owned();
        let coords = raw
            .pointer("/install/path")
            .and_then(|x| x.as_str())
            .unwrap_or_default();
        let coord = MavenCoord::parse(coords)
            .ok_or_else(|| Error::LoaderInstall(format!("bad install.path {coords}")))?;
        let dest = libs_dir.join(coord.path());
        let inst = installer.clone();
        let found = blocking(move || extract_zip_entry(&inst, &file_path, &dest)).await?;
        if !found {
            return Err(Error::LoaderInstall(
                "universal jar missing from installer".into(),
            ));
        }
        for lib in &mut v.libraries {
            if let Some(u) = &lib.url {
                lib.url = Some(
                    u.replace(
                        "http://files.minecraftforge.net/maven/",
                        "https://maven.minecraftforge.net/",
                    )
                    .replace("http://", "https://"),
                );
            }
        }
        v
    } else {
        let profile: InstallProfile =
            serde_json::from_value(raw).map_err(|e| Error::parse("install_profile.json", e))?;
        let json_name = profile
            .json
            .clone()
            .unwrap_or_else(|| "/version.json".into());
        let inst = installer.clone();
        let bytes = blocking(move || read_zip_entry(&inst, &json_name))
            .await?
            .ok_or_else(|| Error::LoaderInstall("installer has no version.json".into()))?;
        let v: VersionJson =
            serde_json::from_slice(&bytes).map_err(|e| Error::parse("version.json", e))?;
        provide_libraries(
            engine,
            &installer,
            v.libraries.iter().chain(profile.libraries.iter()),
            ctx,
        )
        .await?;
        if !profile.processors.is_empty() {
            let java = engine
                .runtimes()
                .ensure(&vanilla.java.component, vanilla.java.major_version, ctx)
                .await?;
            let vanilla_jar = engine.paths.client_jar(game);
            run_processors(
                engine,
                &profile,
                &installer,
                game,
                &vanilla_jar,
                &java.path,
                ctx,
            )
            .await?;
        }
        v
    };
    if version_json.inherits_from.is_none() {
        version_json.inherits_from = Some(game.to_owned());
    }
    safe_version_id(&version_json.id)?;
    write_json(engine.paths.version_json(&version_json.id), &version_json).await?;
    Ok(version_json.id)
}

/// Make all libraries available: copies inside the installer's `maven/` folder win,
/// everything else is downloaded (with checksums).
async fn provide_libraries<'a>(
    engine: &Engine,
    installer: &Path,
    libs: impl Iterator<Item = &'a Library>,
    ctx: &TaskCtx,
) -> Result<()> {
    let inst = installer.to_path_buf();
    let entries: HashSet<String> = blocking(move || list_zip_entries(&inst))
        .await?
        .into_iter()
        .collect();
    let libs_dir = engine.paths.libraries_dir();
    let all: Vec<Library> = libs.cloned().collect();
    let mut downloads = Vec::new();
    for lib in resolve_libraries(&all, &engine.env) {
        let dest = libs_dir.join(&lib.path);
        let entry = format!("maven/{}", lib.path.to_string_lossy().replace('\\', "/"));
        if entries.contains(&entry) {
            let (inst, d) = (installer.to_path_buf(), dest.clone());
            let up_to_date = match (&lib.hash, dest.is_file()) {
                (Some(Hash::Sha1(h)), true) => sha1_file(&dest)
                    .await
                    .map(|a| a.eq_ignore_ascii_case(h))
                    .unwrap_or(false),
                (None, true) => true,
                _ => false,
            };
            if !up_to_date {
                blocking(move || extract_zip_entry(&inst, &entry, &d)).await?;
            }
        } else if let Some(url) = lib.url {
            downloads.push(DownloadItem::new(url, dest).hash(lib.hash).size(lib.size));
        } else if !dest.is_file() {
            return Err(Error::LoaderInstall(format!(
                "library {} is not available",
                lib.name
            )));
        }
    }
    engine
        .downloader
        .run(downloads, VerifyMode::Fast, ctx)
        .await
}

fn substitute(arg: &str, data: &HashMap<String, String>, libs: &Path) -> String {
    if let Some(inner) = arg.strip_prefix('[').and_then(|a| a.strip_suffix(']'))
        && let Some(c) = MavenCoord::parse(inner)
    {
        return display_path(&libs.join(c.local_path()));
    }
    let mut out = String::with_capacity(arg.len());
    let mut rest = arg;
    while let Some(s) = rest.find('{') {
        if let Some(e) = rest[s..].find('}')
            && let Some(v) = data.get(&rest[s + 1..s + e])
        {
            out.push_str(&rest[..s]);
            out.push_str(v);
            rest = &rest[s + e + 1..];
            continue;
        }
        out.push_str(&rest[..=s]);
        rest = &rest[s + 1..];
    }
    out.push_str(rest);
    out
}

fn main_class(jar: &Path) -> Result<String> {
    let bytes = read_zip_entry(jar, "META-INF/MANIFEST.MF")?
        .ok_or_else(|| Error::LoaderInstall(format!("{} has no manifest", jar.display())))?;
    let text = String::from_utf8_lossy(&bytes);
    let mut unfolded = String::new();
    for line in text.lines() {
        match line.strip_prefix(' ') {
            Some(cont) => unfolded.push_str(cont),
            None => {
                unfolded.push('\n');
                unfolded.push_str(line);
            }
        }
    }
    unfolded
        .lines()
        .find_map(|l| l.strip_prefix("Main-Class:"))
        .map(|s| s.trim().to_owned())
        .ok_or_else(|| Error::LoaderInstall(format!("{} has no Main-Class", jar.display())))
}

async fn run_processors(
    engine: &Engine,
    profile: &InstallProfile,
    installer: &Path,
    game: &str,
    vanilla_jar: &Path,
    java: &Path,
    ctx: &TaskCtx,
) -> Result<()> {
    let libs = engine.paths.libraries_dir();
    let tmp = engine
        .paths
        .temp_dir()
        .join(format!("installer-{}", uuid::Uuid::new_v4().simple()));
    tokio::fs::create_dir_all(&tmp).await.at(&tmp)?;
    let mut data: HashMap<String, String> = HashMap::new();
    data.insert("SIDE".into(), "client".into());
    data.insert("MINECRAFT_JAR".into(), display_path(vanilla_jar));
    data.insert("MINECRAFT_VERSION".into(), game.into());
    data.insert("ROOT".into(), display_path(&engine.paths.data));
    data.insert("INSTALLER".into(), display_path(installer));
    data.insert("LIBRARY_DIR".into(), display_path(&libs));
    for (key, entry) in &profile.data {
        let v = &entry.client;
        let value = if let Some(inner) = v.strip_prefix('[').and_then(|x| x.strip_suffix(']')) {
            let c = MavenCoord::parse(inner)
                .ok_or_else(|| Error::LoaderInstall(format!("bad coordinates {inner}")))?;
            display_path(&libs.join(c.local_path()))
        } else if let Some(inner) = v.strip_prefix('\'').and_then(|x| x.strip_suffix('\'')) {
            inner.to_owned()
        } else if v.starts_with('/') {
            let out = crate::util::fs::safe_join(&tmp, v.trim_start_matches('/'))?;
            let (inst, name, o) = (installer.to_path_buf(), v.clone(), out.clone());
            blocking(move || extract_zip_entry(&inst, &name, &o)).await?;
            display_path(&out)
        } else {
            v.clone()
        };
        data.insert(key.clone(), value);
    }

    let sep = if cfg!(windows) { ";" } else { ":" };
    let client: Vec<&Processor> = profile
        .processors
        .iter()
        .filter(|p| {
            p.sides
                .as_ref()
                .is_none_or(|s| s.iter().any(|x| x == "client"))
        })
        .collect();
    let total = client.len();
    for (i, p) in client.into_iter().enumerate() {
        ctx.check()?;
        ctx.stage("stage.loaderProcessors", Some(format!("{}/{total}", i + 1)));
        let outputs: Vec<(PathBuf, String)> = p
            .outputs
            .iter()
            .map(|(k, v)| {
                (
                    PathBuf::from(substitute(k, &data, &libs)),
                    substitute(v, &data, &libs).trim_matches('\'').to_owned(),
                )
            })
            .collect();
        if !outputs.is_empty() {
            let mut fresh = true;
            for (path, sha) in &outputs {
                if !sha1_file(path)
                    .await
                    .map(|a| a.eq_ignore_ascii_case(sha))
                    .unwrap_or(false)
                {
                    fresh = false;
                    break;
                }
            }
            if fresh {
                continue;
            }
        }
        let jar_coord = MavenCoord::parse(&p.jar)
            .ok_or_else(|| Error::LoaderInstall(format!("bad processor {}", p.jar)))?;
        let jar = libs.join(jar_coord.local_path());
        let j = jar.clone();
        let main = blocking(move || main_class(&j)).await?;
        let mut cp = vec![display_path(&jar)];
        for c in &p.classpath {
            let coord = MavenCoord::parse(c)
                .ok_or_else(|| Error::LoaderInstall(format!("bad classpath entry {c}")))?;
            cp.push(display_path(&libs.join(coord.local_path())));
        }
        let args: Vec<String> = p.args.iter().map(|a| substitute(a, &data, &libs)).collect();
        let mut cmd = crate::util::process::command(java);
        cmd.arg("-cp")
            .arg(cp.join(sep))
            .arg(&main)
            .args(&args)
            .current_dir(&tmp)
            .kill_on_drop(true);
        tracing::info!(processor = %p.jar, "running loader processor");
        let out = tokio::select! {
            _ = ctx.cancel.cancelled() => return Err(Error::Cancelled),
            r = tokio::time::timeout(Duration::from_secs(900), cmd.output()) => r,
        }
        .map_err(|_| Error::LoaderInstall(format!("{} timed out", p.jar)))?
        .map_err(|e| Error::LoaderInstall(format!("{}: {e}", p.jar)))?;
        if !out.status.success() {
            let text = format!(
                "{}\n{}",
                String::from_utf8_lossy(&out.stdout),
                String::from_utf8_lossy(&out.stderr)
            );
            let tail: Vec<&str> = text
                .lines()
                .rev()
                .take(15)
                .collect::<Vec<_>>()
                .into_iter()
                .rev()
                .collect();
            return Err(Error::LoaderInstall(format!(
                "{} failed:\n{}",
                p.jar,
                tail.join("\n")
            )));
        }
        for (path, sha) in &outputs {
            let actual = sha1_file(path).await?;
            if !actual.eq_ignore_ascii_case(sha) {
                return Err(Error::HashMismatch {
                    path: path.clone(),
                    expected: sha.clone(),
                    actual,
                });
            }
        }
    }
    let _ = tokio::fs::remove_dir_all(&tmp).await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn neoforge_version_mapping() {
        assert_eq!(neoforge_game_version("21.1.77").as_deref(), Some("1.21.1"));
        assert_eq!(neoforge_game_version("21.0.167").as_deref(), Some("1.21"));
        assert_eq!(neoforge_game_version("20.4.237").as_deref(), Some("1.20.4"));
        assert_eq!(
            neoforge_game_version("21.11.3-beta").as_deref(),
            Some("1.21.11")
        );
        assert_eq!(neoforge_game_version("26.3.0.99").as_deref(), Some("26.3"));
        assert_eq!(neoforge_game_version("26.1.2.4").as_deref(), Some("26.1.2"));
        assert_eq!(neoforge_game_version("0.25w14craftmine.3-beta"), None);
    }

    #[test]
    fn installer_urls() {
        let (u, _) = installer_url(LoaderKind::Forge, "1.8.9", "11.15.1.2318-1.8.9").unwrap();
        assert_eq!(
            u,
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-installer.jar"
        );
        let (u, _) = installer_url(LoaderKind::Neoforge, "26.3", "26.3.0.99").unwrap();
        assert_eq!(
            u,
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.99/neoforge-26.3.0.99-installer.jar"
        );
        let (u, n) = installer_url(LoaderKind::Neoforge, "1.20.1", "47.1.106").unwrap();
        assert!(
            u.ends_with("/net/neoforged/forge/1.20.1-47.1.106/forge-1.20.1-47.1.106-installer.jar")
        );
        assert!(n.starts_with("neoforge-"));
        assert!(installer_url(LoaderKind::Forge, "1.20.1", "../x").is_err());
    }

    #[test]
    fn processor_substitution() {
        let mut data = HashMap::new();
        data.insert("ROOT".to_owned(), "/data".to_owned());
        data.insert("BINPATCH".to_owned(), "/tmp/client.lzma".to_owned());
        let libs = Path::new("/data/libraries");
        assert_eq!(
            substitute("{ROOT}/libraries/", &data, libs),
            "/data/libraries/"
        );
        assert_eq!(
            substitute("--apply-patches={BINPATCH}", &data, libs),
            "--apply-patches=/tmp/client.lzma"
        );
        assert_eq!(substitute("{UNKNOWN}", &data, libs), "{UNKNOWN}");
        assert_eq!(
            substitute(
                "[net.neoforged:minecraft-client-patched:26.3.0.99]",
                &data,
                libs
            ),
            display_path(&libs.join(
                "net/neoforged/minecraft-client-patched/26.3.0.99/minecraft-client-patched-26.3.0.99.jar"
                    .split('/')
                    .collect::<PathBuf>()
            ))
        );
    }

    #[test]
    fn install_profiles_parse() {
        let dir = format!("{}/tests/fixtures/forge", env!("CARGO_MANIFEST_DIR"));
        for f in [
            "neoforge-26.3-install_profile.json",
            "forge-1.20.1-install_profile.json",
            "forge-1.12.2-install_profile.json",
        ] {
            let p: InstallProfile =
                serde_json::from_str(&std::fs::read_to_string(format!("{dir}/{f}")).unwrap())
                    .unwrap();
            assert!(p.json.is_some(), "{f}");
        }
        let neo: InstallProfile = serde_json::from_str(
            &std::fs::read_to_string(format!("{dir}/neoforge-26.3-install_profile.json")).unwrap(),
        )
        .unwrap();
        let client: Vec<_> = neo
            .processors
            .iter()
            .filter(|p| {
                p.sides
                    .as_ref()
                    .is_none_or(|s| s.contains(&"client".to_owned()))
            })
            .collect();
        assert_eq!(client.len(), 1, "26.x needs a single client processor");
        let forge: InstallProfile = serde_json::from_str(
            &std::fs::read_to_string(format!("{dir}/forge-1.20.1-install_profile.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(
            forge
                .processors
                .iter()
                .filter(|p| p
                    .sides
                    .as_ref()
                    .is_none_or(|s| s.contains(&"client".to_owned())))
                .count(),
            6
        );
        for f in [
            "forge-1.20.1-version.json",
            "neoforge-26.3-version.json",
            "forge-1.12.2-version.json",
        ] {
            let v: VersionJson =
                serde_json::from_str(&std::fs::read_to_string(format!("{dir}/{f}")).unwrap())
                    .unwrap();
            assert!(v.inherits_from.is_some(), "{f}");
        }
    }

    #[test]
    fn maven_metadata_versions() {
        let xml = "<metadata><versioning><versions><version>1.20.1-47.4.28</version><version>26.3-66.1.2</version></versions></versioning></metadata>";
        assert_eq!(parse_maven_versions(xml), ["1.20.1-47.4.28", "26.3-66.1.2"]);
    }
}
