//! Instances of other launchers (official launcher, Prism/MultiMC, Modrinth App, CurseForge,
//! SKlauncher) found on this computer, and importing one (copy, or use its folder in place).

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};
use crate::loaders::LoaderKind;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub enum ExternalSource {
    Official,
    Prism,
    Modrinth,
    Curseforge,
    Sklauncher,
    Folder,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ExternalInstance {
    pub source: ExternalSource,
    pub name: String,
    pub game_dir: PathBuf,
    /// `None` when it couldn't be determined (the user picks it).
    pub game_version: Option<String>,
    pub loader: LoaderKind,
    pub loader_version: Option<String>,
    pub mods: u32,
    pub worlds: u32,
}

/// Never copied: other launchers' own data, and anything holding credentials.
pub fn skip_on_copy(name: &str) -> bool {
    let n = name.to_ascii_lowercase();
    n.starts_with("launcher_")
        || n.contains("credential")
        || n.contains("accounts")
        || matches!(
            n.as_str(),
            "versions"
                | "libraries"
                | "assets"
                | "runtime"
                | "natives"
                | "bin"
                | "cache"
                | ".cache"
                | "webcache"
                | "webcache2"
                | "logs"
                | "crash-reports"
                | ".fabric"
                | ".quilt"
                | "treatment_tags.json"
        )
}

/// Version ids of the official launcher / SKlauncher: `fabric-loader-0.16.5-1.21.1`,
/// `quilt-loader-0.26.0-1.21.1`, `1.20.1-forge-47.2.0`, `neoforge-21.1.77`, `1.21.1`.
pub fn parse_version_id(id: &str) -> (Option<String>, LoaderKind, Option<String>) {
    let id = id.trim();
    for (prefix, kind) in [
        ("fabric-loader-", LoaderKind::Fabric),
        ("quilt-loader-", LoaderKind::Quilt),
    ] {
        if let Some(rest) = id.strip_prefix(prefix)
            && let Some((loader, game)) = rest.split_once('-')
        {
            return (Some(game.to_owned()), kind, Some(loader.to_owned()));
        }
    }
    if let Some((game, loader)) = id.split_once("-forge-") {
        return (
            Some(game.to_owned()),
            LoaderKind::Forge,
            Some(loader.to_owned()),
        );
    }
    if let Some((game, loader)) = id.split_once("-neoforge-") {
        return (
            Some(game.to_owned()),
            LoaderKind::Neoforge,
            Some(loader.to_owned()),
        );
    }
    if let Some(v) = id.strip_prefix("neoforge-") {
        return (
            crate::loaders::forge::neoforge_game_version(v),
            LoaderKind::Neoforge,
            Some(v.to_owned()),
        );
    }
    match id {
        "" | "latest-release" | "latest-snapshot" => (None, LoaderKind::Vanilla, None),
        _ => (Some(id.to_owned()), LoaderKind::Vanilla, None),
    }
}

/// Game version and loader from the start of `logs/latest.log`, for launchers that keep their
/// metadata where we can't read it.
pub fn detect_from_log(game_dir: &Path) -> Option<(String, LoaderKind, Option<String>)> {
    let text = std::fs::read(game_dir.join("logs").join("latest.log")).ok()?;
    let text = String::from_utf8_lossy(&text[..text.len().min(256 * 1024)]);
    let word_after = |hay: &str, needle: &str| -> Option<String> {
        let i = hay.find(needle)? + needle.len();
        let w: String = hay[i..]
            .chars()
            .take_while(|c| !c.is_whitespace() && *c != ',' && *c != ']')
            .collect();
        (!w.is_empty()).then_some(w)
    };
    for (marker, kind) in [
        ("with Fabric Loader ", LoaderKind::Fabric),
        ("with Quilt Loader ", LoaderKind::Quilt),
    ] {
        for line in text.lines().filter(|l| l.contains(marker)) {
            if let (Some(game), Some(loader)) = (
                word_after(line, "Loading Minecraft "),
                word_after(line, marker),
            ) {
                return Some((game, kind, Some(loader)));
            }
        }
    }
    if let Some(game) = word_after(&text, "--fml.mcVersion, ") {
        if let Some(v) = word_after(&text, "--fml.neoForgeVersion, ") {
            return Some((game, LoaderKind::Neoforge, Some(v)));
        }
        let v = word_after(&text, "--fml.forgeVersion, ");
        return Some((game, LoaderKind::Forge, v));
    }
    None
}

fn count(dir: &Path, f: impl Fn(&std::fs::DirEntry) -> bool) -> u32 {
    std::fs::read_dir(dir)
        .map(|rd| rd.flatten().filter(|e| f(e)).count() as u32)
        .unwrap_or(0)
}

fn found(
    source: ExternalSource,
    name: String,
    game_dir: PathBuf,
    meta: (Option<String>, LoaderKind, Option<String>),
) -> Option<ExternalInstance> {
    if !game_dir.is_dir() {
        return None;
    }
    let (mut game_version, mut loader, mut loader_version) = meta;
    if game_version.is_none()
        && let Some((g, l, lv)) = detect_from_log(&game_dir)
    {
        (game_version, loader, loader_version) = (Some(g), l, lv);
    }
    Some(ExternalInstance {
        source,
        name,
        mods: count(&game_dir.join("mods"), |e| {
            e.file_name().to_string_lossy().ends_with(".jar")
        }),
        worlds: count(&game_dir.join("saves"), |e| {
            e.path().join("level.dat").is_file()
        }),
        game_dir,
        game_version,
        loader,
        loader_version,
    })
}

fn read_json(path: &Path) -> Option<serde_json::Value> {
    serde_json::from_slice(&std::fs::read(path).ok()?).ok()
}

fn subdirs(dir: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir())
        .collect();
    v.sort();
    v
}

/// Well-known data folders of each launcher on this OS.
struct Roots {
    official: Vec<PathBuf>,
    prism: Vec<PathBuf>,
    modrinth: Vec<PathBuf>,
    curseforge: Vec<PathBuf>,
    sklauncher: Vec<PathBuf>,
}

fn roots() -> Roots {
    let home = directories::BaseDirs::new();
    let (home_dir, data) = match &home {
        Some(b) => (b.home_dir().to_path_buf(), b.data_dir().to_path_buf()),
        None => Default::default(),
    };
    let flatpak = home_dir.join(".var/app");
    let (official, sk) = if cfg!(windows) {
        (data.join(".minecraft"), data.join(".sklauncher"))
    } else if cfg!(target_os = "macos") {
        (data.join("minecraft"), home_dir.join(".sklauncher"))
    } else {
        (home_dir.join(".minecraft"), home_dir.join(".sklauncher"))
    };
    Roots {
        official: vec![official],
        prism: vec![
            data.join("PrismLauncher/instances"),
            flatpak.join("org.prismlauncher.PrismLauncher/data/PrismLauncher/instances"),
            data.join("multimc/instances"),
        ],
        modrinth: vec![
            data.join("ModrinthApp/profiles"),
            data.join("com.modrinth.theseus/profiles"),
            flatpak.join("com.modrinth.ModrinthApp/data/ModrinthApp/profiles"),
        ],
        curseforge: vec![home_dir.join("curseforge/minecraft/Instances")],
        sklauncher: vec![sk],
    }
}

fn official(root: &Path) -> Vec<ExternalInstance> {
    let mut out = Vec::new();
    if let Some(profiles) = read_json(&root.join("launcher_profiles.json"))
        .and_then(|j| j.get("profiles").cloned())
        .and_then(|p| p.as_object().cloned())
    {
        for p in profiles.values() {
            let id = p
                .get("lastVersionId")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let name = p
                .get("name")
                .and_then(|v| v.as_str())
                .filter(|n| !n.is_empty())
                .unwrap_or(id)
                .to_owned();
            let dir = p
                .get("gameDir")
                .and_then(|v| v.as_str())
                .map(PathBuf::from)
                .unwrap_or_else(|| root.to_path_buf());
            out.extend(found(
                ExternalSource::Official,
                if name.is_empty() {
                    "Minecraft".into()
                } else {
                    name
                },
                dir,
                parse_version_id(id),
            ));
        }
    }
    if out.is_empty() {
        out.extend(found(
            ExternalSource::Official,
            ".minecraft".into(),
            root.to_path_buf(),
            (None, LoaderKind::Vanilla, None),
        ));
    }
    out
}

fn prism(dir: &Path) -> Option<ExternalInstance> {
    let cfg = std::fs::read_to_string(dir.join("instance.cfg")).ok()?;
    let name = cfg
        .lines()
        .find_map(|l| l.strip_prefix("name="))
        .map(str::to_owned)
        .unwrap_or_else(|| dir.file_name().unwrap_or_default().to_string_lossy().into());
    let mut meta = (None, LoaderKind::Vanilla, None);
    if let Some(components) = read_json(&dir.join("mmc-pack.json"))
        .and_then(|j| j.get("components").and_then(|c| c.as_array()).cloned())
    {
        for c in components {
            let v = c.get("version").and_then(|v| v.as_str()).map(str::to_owned);
            match c.get("uid").and_then(|u| u.as_str()) {
                Some("net.minecraft") => meta.0 = v,
                Some("net.fabricmc.fabric-loader") => (meta.1, meta.2) = (LoaderKind::Fabric, v),
                Some("org.quiltmc.quilt-loader") => (meta.1, meta.2) = (LoaderKind::Quilt, v),
                Some("net.minecraftforge") => (meta.1, meta.2) = (LoaderKind::Forge, v),
                Some("net.neoforged") => (meta.1, meta.2) = (LoaderKind::Neoforge, v),
                _ => {}
            }
        }
    }
    let game = [".minecraft", "minecraft"]
        .iter()
        .map(|g| dir.join(g))
        .find(|p| p.is_dir())?;
    found(ExternalSource::Prism, name, game, meta)
}

fn curseforge(dir: &Path) -> Option<ExternalInstance> {
    let j = read_json(&dir.join("minecraftinstance.json"))?;
    let name = j.get("name").and_then(|v| v.as_str())?.to_owned();
    let game = j
        .get("gameVersion")
        .and_then(|v| v.as_str())
        .map(str::to_owned);
    let base = j
        .pointer("/baseModLoader/name")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let (loader, version) = match base.split_once('-') {
        Some(("forge", v)) => (LoaderKind::Forge, Some(v.to_owned())),
        Some(("neoforge", v)) => (LoaderKind::Neoforge, Some(v.to_owned())),
        // `fabric-0.15.11-1.20.1`
        Some(("fabric", v)) => (LoaderKind::Fabric, v.split('-').next().map(str::to_owned)),
        Some(("quilt", v)) => (LoaderKind::Quilt, v.split('-').next().map(str::to_owned)),
        _ => (LoaderKind::Vanilla, None),
    };
    found(
        ExternalSource::Curseforge,
        name,
        dir.to_path_buf(),
        (game, loader, version),
    )
}

fn sklauncher(root: &Path) -> Vec<ExternalInstance> {
    let Some(list) = read_json(&root.join("instances.json"))
        .and_then(|j| j.get("instances").and_then(|i| i.as_array()).cloned())
    else {
        return vec![];
    };
    list.iter()
        .filter_map(|i| {
            let s = |k: &str| i.get(k).and_then(|v| v.as_str()).map(str::to_owned);
            let dir = PathBuf::from(s("directory")?);
            let (mut game, mut loader, mut version) =
                parse_version_id(&s("versionId").unwrap_or_default());
            if let Some(g) = s("minecraftVersion").filter(|g| !g.starts_with("latest-")) {
                game = Some(g);
            }
            match s("gameType").as_deref() {
                Some("fabric") => loader = LoaderKind::Fabric,
                Some("quilt") => loader = LoaderKind::Quilt,
                Some("forge") => loader = LoaderKind::Forge,
                Some("neoforge") => loader = LoaderKind::Neoforge,
                _ => {}
            }
            if let Some(v) = s("loaderVersion") {
                version = Some(v);
            }
            found(
                ExternalSource::Sklauncher,
                s("name")?,
                dir,
                (game, loader, version),
            )
        })
        .collect()
}

/// Everything found on this computer. Blocking (reads folders): use `spawn_blocking`.
pub fn detect() -> Vec<ExternalInstance> {
    let r = roots();
    let mut out = Vec::new();
    for root in r.official.iter().filter(|p| p.is_dir()) {
        out.extend(official(root));
    }
    for root in &r.prism {
        out.extend(subdirs(root).iter().filter_map(|d| prism(d)));
    }
    for root in &r.modrinth {
        out.extend(subdirs(root).into_iter().filter_map(|d| {
            let name = d.file_name()?.to_string_lossy().into_owned();
            found(
                ExternalSource::Modrinth,
                name,
                d,
                (None, LoaderKind::Vanilla, None),
            )
        }));
    }
    for root in &r.curseforge {
        out.extend(subdirs(root).iter().filter_map(|d| curseforge(d)));
    }
    for root in &r.sklauncher {
        out.extend(sklauncher(root));
    }
    out
}

/// Any folder the user points at (a game folder with `mods`/`saves`, or one containing
/// `.minecraft`/`minecraft`).
pub fn inspect_folder(dir: &Path) -> Result<ExternalInstance> {
    if !dir.is_dir() {
        return Err(Error::Invalid(format!("{} is not a folder", dir.display())));
    }
    let game = [".minecraft", "minecraft"]
        .iter()
        .map(|g| dir.join(g))
        .find(|p| p.is_dir())
        .unwrap_or_else(|| dir.to_path_buf());
    let name = dir
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| "Minecraft".into());
    found(
        ExternalSource::Folder,
        name,
        game,
        (None, LoaderKind::Vanilla, None),
    )
    .ok_or_else(|| Error::Invalid("not a folder".into()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_ids() {
        assert_eq!(
            parse_version_id("fabric-loader-0.16.5-1.21.1"),
            (
                Some("1.21.1".into()),
                LoaderKind::Fabric,
                Some("0.16.5".into())
            )
        );
        assert_eq!(
            parse_version_id("1.20.1-forge-47.2.0"),
            (
                Some("1.20.1".into()),
                LoaderKind::Forge,
                Some("47.2.0".into())
            )
        );
        assert_eq!(
            parse_version_id("1.21.1-neoforge-21.1.248"),
            (
                Some("1.21.1".into()),
                LoaderKind::Neoforge,
                Some("21.1.248".into())
            )
        );
        assert_eq!(
            parse_version_id("neoforge-21.1.77").0.as_deref(),
            Some("1.21.1")
        );
        assert_eq!(
            parse_version_id("neoforge-20.4.1").0.as_deref(),
            Some("1.20.4")
        );
        assert_eq!(
            parse_version_id("neoforge-26.3.0.100").0.as_deref(),
            Some("26.3")
        );
        assert_eq!(
            parse_version_id("26.4-snapshot-4").0.as_deref(),
            Some("26.4-snapshot-4")
        );
        assert_eq!(parse_version_id("latest-release").0, None);
    }

    #[test]
    fn logs_and_folders() {
        let dir = tempfile::tempdir().unwrap();
        let g = dir.path().join("Pack");
        std::fs::create_dir_all(g.join("logs")).unwrap();
        std::fs::create_dir_all(g.join("mods")).unwrap();
        std::fs::write(g.join("mods/sodium.jar"), b"x").unwrap();
        std::fs::write(
            g.join("logs/latest.log"),
            "[12:00:00] [main/INFO]: Loading Minecraft 1.21.1 with Fabric Loader 0.16.5\n",
        )
        .unwrap();
        let i = inspect_folder(&g).unwrap();
        assert_eq!(
            (
                i.game_version.as_deref(),
                i.loader,
                i.loader_version.as_deref(),
                i.mods
            ),
            (Some("1.21.1"), LoaderKind::Fabric, Some("0.16.5"), 1)
        );
        std::fs::write(
            g.join("logs/latest.log"),
            "args [--fml.neoForgeVersion, 21.1.248, --fml.mcVersion, 1.21.1, --fml.neoFormVersion]",
        )
        .unwrap();
        let (game, loader, v) = detect_from_log(&g).unwrap();
        assert_eq!(
            (game.as_str(), loader, v.as_deref()),
            ("1.21.1", LoaderKind::Neoforge, Some("21.1.248"))
        );

        // Prism layout.
        let p = dir.path().join("prism/Inst");
        std::fs::create_dir_all(p.join(".minecraft")).unwrap();
        std::fs::write(
            p.join("instance.cfg"),
            "InstanceType=OneSix\nname=Mój pack\n",
        )
        .unwrap();
        std::fs::write(
            p.join("mmc-pack.json"),
            r#"{"components":[{"uid":"net.minecraft","version":"1.20.1"},{"uid":"net.minecraftforge","version":"47.2.0"}]}"#,
        )
        .unwrap();
        let i = prism(&p).unwrap();
        assert_eq!((i.name.as_str(), i.loader), ("Mój pack", LoaderKind::Forge));

        assert!(skip_on_copy("launcher_accounts_microsoft_store.json"));
        assert!(skip_on_copy("versions"));
        assert!(!skip_on_copy("saves"));
    }
}
