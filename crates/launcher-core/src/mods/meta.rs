//! Identity of a mod jar read from its own metadata (Fabric, Quilt, Forge, NeoForge), so a mod
//! named in a crash report can be traced back to the file that provides it.

use std::io::Read;
use std::path::Path;
use std::sync::LazyLock;

use regex::Regex;

#[derive(Debug, Clone, Default, PartialEq)]
pub struct ModMeta {
    pub ids: Vec<String>,
    pub name: Option<String>,
    /// Mixin config file names (`sodium.mixins.json`), which crash logs often name instead.
    pub mixin_configs: Vec<String>,
}

static TOML_MOD_ID: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?m)^\s*modId\s*=\s*"([^"]+)""#).expect("static regex"));
static TOML_NAME: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?m)^\s*displayName\s*=\s*"([^"]+)""#).expect("static regex"));
static TOML_MIXIN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?m)^\s*config\s*=\s*"([^"]+)""#).expect("static regex"));

/// Metadata files are small; anything larger is not one.
const MAX_META: u64 = 512 * 1024;

fn read_entry(zip: &mut zip::ZipArchive<std::fs::File>, name: &str) -> Option<String> {
    let f = zip.by_name(name).ok()?;
    let mut s = String::new();
    f.take(MAX_META).read_to_string(&mut s).ok()?;
    Some(s)
}

fn mixin_names(v: Option<&serde_json::Value>) -> Vec<String> {
    let Some(v) = v else { return Vec::new() };
    let list = match v {
        serde_json::Value::Array(a) => a.iter().collect(),
        other => vec![other],
    };
    list.into_iter()
        .filter_map(|m| {
            m.as_str()
                .or_else(|| m.get("config").and_then(|c| c.as_str()))
                .map(str::to_owned)
        })
        .collect()
}

/// Read a jar's mod metadata. `None` for files that are not readable mod jars.
pub fn read(jar: &Path) -> Option<ModMeta> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(jar).ok()?).ok()?;
    let mut meta = ModMeta::default();
    if let Some(json) = read_entry(&mut zip, "fabric.mod.json") {
        let v: serde_json::Value = serde_json::from_str(&json).ok()?;
        meta.ids.extend(v["id"].as_str().map(str::to_owned));
        meta.name = v["name"].as_str().map(str::to_owned);
        meta.mixin_configs = mixin_names(v.get("mixins"));
    } else if let Some(json) = read_entry(&mut zip, "quilt.mod.json") {
        let v: serde_json::Value = serde_json::from_str(&json).ok()?;
        let q = &v["quilt_loader"];
        meta.ids.extend(q["id"].as_str().map(str::to_owned));
        meta.name = q["metadata"]["name"].as_str().map(str::to_owned);
        meta.mixin_configs = mixin_names(v.get("mixin"));
    } else {
        let toml = read_entry(&mut zip, "META-INF/neoforge.mods.toml")
            .or_else(|| read_entry(&mut zip, "META-INF/mods.toml"))?;
        meta.ids = TOML_MOD_ID
            .captures_iter(&toml)
            .map(|c| c[1].to_owned())
            .collect();
        meta.name = TOML_NAME.captures(&toml).map(|c| c[1].to_owned());
        meta.mixin_configs = TOML_MIXIN
            .captures_iter(&toml)
            .map(|c| c[1].to_owned())
            .filter(|c| c.ends_with(".json"))
            .collect();
    }
    (!meta.ids.is_empty()).then_some(meta)
}

/// The enabled jar in `mods_dir` that provides `id` (a mod id or a mixin config's base name),
/// with the mod's display name.
pub fn find(mods_dir: &Path, id: &str) -> Option<(String, String)> {
    let id = id.to_ascii_lowercase();
    let mut entries: Vec<_> = std::fs::read_dir(mods_dir).ok()?.flatten().collect();
    entries.sort_by_key(|e| e.file_name());
    entries.into_iter().find_map(|e| {
        let file = e.file_name().to_string_lossy().into_owned();
        if !file.to_ascii_lowercase().ends_with(".jar") {
            return None;
        }
        let meta = read(&e.path())?;
        let hit = meta.ids.iter().any(|i| i.eq_ignore_ascii_case(&id))
            || meta.mixin_configs.iter().any(|c| {
                let base = c.to_ascii_lowercase();
                let base = base.trim_end_matches(".json");
                base == format!("{id}.mixins") || base == format!("{id}.mixin")
            });
        hit.then(|| (file, meta.name.unwrap_or_else(|| meta.ids[0].clone())))
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn jar(dir: &Path, name: &str, files: &[(&str, &str)]) {
        let mut z = zip::ZipWriter::new(std::fs::File::create(dir.join(name)).unwrap());
        for (n, body) in files {
            z.start_file(*n, zip::write::SimpleFileOptions::default())
                .unwrap();
            z.write_all(body.as_bytes()).unwrap();
        }
        z.finish().unwrap();
    }

    #[test]
    fn finds_mods_by_id_and_mixin_config() {
        let dir = tempfile::tempdir().unwrap();
        jar(
            dir.path(),
            "sodium-fabric-0.6.jar",
            &[(
                "fabric.mod.json",
                r#"{"id":"sodium","name":"Sodium","mixins":["sodium.mixins.json",{"config":"sodium-extra.mixin.json"}]}"#,
            )],
        );
        jar(
            dir.path(),
            "create-1.21.1.jar",
            &[(
                "META-INF/neoforge.mods.toml",
                "[[mods]]\nmodId = \"create\"\ndisplayName = \"Create\"\n[[mixins]]\nconfig = \"create.mixins.json\"\n",
            )],
        );
        jar(dir.path(), "not-a-mod.jar", &[("readme.txt", "hi")]);
        std::fs::write(dir.path().join("broken.jar"), b"nope").unwrap();

        assert_eq!(
            find(dir.path(), "sodium"),
            Some(("sodium-fabric-0.6.jar".into(), "Sodium".into()))
        );
        assert_eq!(
            find(dir.path(), "sodium-extra").map(|f| f.0),
            Some("sodium-fabric-0.6.jar".into())
        );
        assert_eq!(
            find(dir.path(), "Create"),
            Some(("create-1.21.1.jar".into(), "Create".into()))
        );
        assert_eq!(find(dir.path(), "iris"), None);
    }
}
