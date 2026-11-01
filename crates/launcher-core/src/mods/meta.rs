//! Identity and requirements of a mod jar read from its own metadata (Fabric, Quilt, Forge,
//! NeoForge): which mod ids it provides, what it depends on and what it breaks. Used to trace a
//! mod named in a crash report back to its file and to check a mods folder before launch.

use std::io::{Cursor, Read, Seek};
use std::path::Path;
use std::sync::LazyLock;

use regex::Regex;

/// Which loader a jar is written for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Family {
    Fabric,
    Quilt,
    /// `META-INF/mods.toml` (Forge; older NeoForge reads it too).
    Forge,
    /// `META-INF/neoforge.mods.toml`.
    NeoForge,
}

/// A dependency or a conflict: mod id and version requirements (any one may match; empty = any).
#[derive(Debug, Clone, PartialEq)]
pub struct Requirement {
    pub id: String,
    pub versions: Vec<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ModMeta {
    pub family: Family,
    /// The jar's own mod ids and the ids it declares to provide.
    pub ids: Vec<String>,
    pub name: Option<String>,
    pub version: Option<String>,
    /// Mixin config file names (`sodium.mixins.json`), which crash logs often name instead.
    pub mixin_configs: Vec<String>,
    pub depends: Vec<Requirement>,
    pub breaks: Vec<Requirement>,
    /// Ids provided by jars bundled inside this one (Fabric `jars`, Forge Jar-in-Jar).
    pub nested_ids: Vec<String>,
}

static TOML_MOD_ID: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?m)^\s*modId\s*=\s*"([^"]+)""#).expect("static regex"));
static TOML_NAME: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?m)^\s*displayName\s*=\s*"([^"]+)""#).expect("static regex"));
static TOML_VERSION: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?m)^\s*version\s*=\s*"([^"]+)""#).expect("static regex"));
static TOML_MIXIN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?m)^\s*config\s*=\s*"([^"]+)""#).expect("static regex"));
static TOML_SECTION: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?m)^\s*\[\[?([^\]]+)\]\]?\s*(?:#.*)?$").expect("static regex"));
static TOML_KEY: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?m)^\s*(modId|mandatory|type|versionRange)\s*=\s*"?([^"\r\n#]*?)"?\s*(?:#.*)?$"#)
        .expect("static regex")
});

/// Metadata files are small; anything larger is not one.
const MAX_META: u64 = 512 * 1024;
/// Bundled jars are read into memory to look inside; skip unusually large ones.
const MAX_NESTED: u64 = 32 * 1024 * 1024;

fn read_entry<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, name: &str) -> Option<String> {
    let f = zip.by_name(name).ok()?;
    let mut s = String::new();
    f.take(MAX_META).read_to_string(&mut s).ok()?;
    Some(s)
}

fn strings(v: Option<&serde_json::Value>) -> Vec<String> {
    match v {
        Some(serde_json::Value::String(s)) => vec![s.clone()],
        Some(serde_json::Value::Array(a)) => a
            .iter()
            .filter_map(|x| x.as_str().map(str::to_owned))
            .collect(),
        _ => Vec::new(),
    }
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

/// Fabric: `{"id": "*" | "range" | ["a", "b"]}`.
fn fabric_requirements(v: Option<&serde_json::Value>) -> Vec<Requirement> {
    let Some(serde_json::Value::Object(map)) = v else {
        return Vec::new();
    };
    map.iter()
        .map(|(id, r)| Requirement {
            id: id.clone(),
            versions: strings(Some(r)).into_iter().filter(|s| s != "*").collect(),
        })
        .collect()
}

/// Quilt: `["id" | {"id": "x", "versions": "range" | [...], "optional": bool}]`.
fn quilt_requirements(v: Option<&serde_json::Value>, skip_optional: bool) -> Vec<Requirement> {
    let Some(serde_json::Value::Array(list)) = v else {
        return Vec::new();
    };
    list.iter()
        .filter_map(|d| match d {
            serde_json::Value::String(id) => Some(Requirement {
                id: id.clone(),
                versions: Vec::new(),
            }),
            serde_json::Value::Object(o) => {
                if skip_optional && o.get("optional").and_then(|x| x.as_bool()) == Some(true) {
                    return None;
                }
                Some(Requirement {
                    id: o.get("id")?.as_str()?.to_owned(),
                    versions: strings(o.get("versions"))
                        .into_iter()
                        .filter(|s| s != "*")
                        .collect(),
                })
            }
            _ => None,
        })
        .collect()
}

/// The bodies of every `[[name]]` table, joined.
fn toml_tables(toml: &str, name: &str) -> String {
    let sections: Vec<_> = TOML_SECTION.captures_iter(toml).collect();
    let mut out = String::new();
    for (i, c) in sections.iter().enumerate() {
        if c[1].trim() != name {
            continue;
        }
        let start = c.get(0).map_or(0, |m| m.end());
        let end = sections
            .get(i + 1)
            .and_then(|n| n.get(0))
            .map_or(toml.len(), |m| m.start());
        out.push_str(&toml[start..end]);
        out.push('\n');
    }
    out
}

/// `[[dependencies.<mod>]]` blocks: required (`mandatory = true` / `type = "required"`) and
/// incompatible ones.
fn toml_requirements(toml: &str) -> (Vec<Requirement>, Vec<Requirement>) {
    let mut depends = Vec::new();
    let mut breaks = Vec::new();
    let sections: Vec<_> = TOML_SECTION.captures_iter(toml).collect();
    for (i, c) in sections.iter().enumerate() {
        if !c[1].trim().starts_with("dependencies") {
            continue;
        }
        let start = c.get(0).map_or(0, |m| m.end());
        let end = sections
            .get(i + 1)
            .and_then(|n| n.get(0))
            .map_or(toml.len(), |m| m.start());
        let (mut id, mut mandatory, mut kind, mut range) = (None, false, None, None);
        for k in TOML_KEY.captures_iter(&toml[start..end]) {
            let v = k[2].trim().to_owned();
            match &k[1] {
                "modId" => id = Some(v),
                "mandatory" => mandatory = v == "true",
                "type" => kind = Some(v.to_ascii_lowercase()),
                _ => range = Some(v),
            }
        }
        let Some(id) = id else { continue };
        let req = Requirement {
            id,
            // A bare version is only a preference in Forge's maven ranges: keep real ranges.
            versions: range
                .filter(|r| r.starts_with('[') || r.starts_with('('))
                .into_iter()
                .collect(),
        };
        match kind.as_deref() {
            Some("incompatible") => breaks.push(req),
            Some("required") => depends.push(req),
            Some(_) => {}
            None if mandatory => depends.push(req),
            None => {}
        }
    }
    (depends, breaks)
}

fn read_archive<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, depth: u8) -> Option<ModMeta> {
    let mut meta = if let Some(json) = read_entry(zip, "fabric.mod.json") {
        let v: serde_json::Value = serde_json::from_str(&json).ok()?;
        let mut ids: Vec<String> = v["id"].as_str().map(str::to_owned).into_iter().collect();
        ids.extend(strings(v.get("provides")));
        ModMeta {
            family: Family::Fabric,
            ids,
            name: v["name"].as_str().map(str::to_owned),
            version: v["version"].as_str().map(str::to_owned),
            mixin_configs: mixin_names(v.get("mixins")),
            depends: fabric_requirements(v.get("depends")),
            breaks: fabric_requirements(v.get("breaks")),
            nested_ids: Vec::new(),
        }
    } else if let Some(json) = read_entry(zip, "quilt.mod.json") {
        let v: serde_json::Value = serde_json::from_str(&json).ok()?;
        let q = &v["quilt_loader"];
        let mut ids: Vec<String> = q["id"].as_str().map(str::to_owned).into_iter().collect();
        if let Some(serde_json::Value::Array(p)) = q.get("provides") {
            ids.extend(p.iter().filter_map(|x| {
                x.as_str()
                    .or_else(|| x.get("id").and_then(|i| i.as_str()))
                    .map(str::to_owned)
            }));
        }
        ModMeta {
            family: Family::Quilt,
            ids,
            name: q["metadata"]["name"].as_str().map(str::to_owned),
            version: q["version"].as_str().map(str::to_owned),
            mixin_configs: mixin_names(v.get("mixin")),
            depends: quilt_requirements(q.get("depends"), true),
            breaks: quilt_requirements(q.get("breaks"), false),
            nested_ids: Vec::new(),
        }
    } else {
        let (toml, family) = match read_entry(zip, "META-INF/neoforge.mods.toml") {
            Some(t) => (t, Family::NeoForge),
            None => (read_entry(zip, "META-INF/mods.toml")?, Family::Forge),
        };
        let (depends, breaks) = toml_requirements(&toml);
        // Identity comes from the `[[mods]]` tables only; dependency tables name other mods.
        let mods = toml_tables(&toml, "mods");
        ModMeta {
            family,
            ids: TOML_MOD_ID
                .captures_iter(&mods)
                .map(|c| c[1].to_owned())
                .collect(),
            name: TOML_NAME.captures(&mods).map(|c| c[1].to_owned()),
            version: TOML_VERSION
                .captures(&mods)
                .map(|c| c[1].to_owned())
                .filter(|v| !v.contains("${")),
            mixin_configs: TOML_MIXIN
                .captures_iter(&toml)
                .map(|c| c[1].to_owned())
                .filter(|c| c.ends_with(".json"))
                .collect(),
            depends,
            breaks,
            nested_ids: Vec::new(),
        }
    };
    if meta.ids.is_empty() {
        return None;
    }
    if depth > 0 {
        meta.nested_ids = nested_ids(zip, depth - 1);
    }
    Some(meta)
}

/// Ids of the jars bundled in `META-INF/jars` (Fabric, Quilt) or `META-INF/jarjar` (Forge).
fn nested_ids<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, depth: u8) -> Vec<String> {
    let names: Vec<String> = zip
        .file_names()
        .filter_map(|n| n.ok().map(|n| n.into_owned()))
        .filter(|n| {
            (n.starts_with("META-INF/jars/") || n.starts_with("META-INF/jarjar/"))
                && n.ends_with(".jar")
        })
        .collect();
    let mut ids = Vec::new();
    for n in names {
        let Ok(f) = zip.by_name(&n) else { continue };
        if f.size() > MAX_NESTED {
            continue;
        }
        let mut bytes = Vec::with_capacity(f.size() as usize);
        if f.take(MAX_NESTED).read_to_end(&mut bytes).is_err() {
            continue;
        }
        let Ok(mut inner) = zip::ZipArchive::new(Cursor::new(bytes)) else {
            continue;
        };
        if let Some(m) = read_archive(&mut inner, depth) {
            ids.extend(m.ids);
            ids.extend(m.nested_ids);
        }
    }
    ids
}

/// Read a jar's mod metadata. `None` for files that are not readable mod jars.
pub fn read(jar: &Path) -> Option<ModMeta> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(jar).ok()?).ok()?;
    read_archive(&mut zip, 2)
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
pub(crate) mod tests {
    use super::*;
    use std::io::Write;

    pub(crate) fn zip_bytes(files: &[(&str, &[u8])]) -> Vec<u8> {
        let mut z = zip::ZipWriter::new(Cursor::new(Vec::new()));
        for (n, body) in files {
            z.start_file(*n, zip::write::SimpleFileOptions::default())
                .unwrap();
            z.write_all(body).unwrap();
        }
        z.finish().unwrap().into_inner()
    }

    pub(crate) fn jar(dir: &Path, name: &str, files: &[(&str, &str)]) {
        let files: Vec<(&str, &[u8])> = files.iter().map(|(n, b)| (*n, b.as_bytes())).collect();
        std::fs::write(dir.join(name), zip_bytes(&files)).unwrap();
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

    #[test]
    fn reads_requirements_and_bundled_jars() {
        let dir = tempfile::tempdir().unwrap();
        let nested = zip_bytes(&[(
            "fabric.mod.json",
            br#"{"id":"fabric-api-base","version":"1.0.0"}"#,
        )]);
        let outer = zip_bytes(&[
            (
                "fabric.mod.json",
                br#"{"id":"fabric-api","provides":["fabric"],"version":"0.165.0+26.3",
                    "depends":{"fabricloader":">=0.20","minecraft":["~26.3","26.4"]},
                    "breaks":{"optifabric":"*"}}"#,
            ),
            ("META-INF/jars/fabric-api-base.jar", &nested),
        ]);
        std::fs::write(dir.path().join("fabric-api.jar"), outer).unwrap();
        let m = read(&dir.path().join("fabric-api.jar")).unwrap();
        assert_eq!(m.ids, ["fabric-api", "fabric"]);
        assert_eq!(m.nested_ids, ["fabric-api-base"]);
        let mc = m.depends.iter().find(|d| d.id == "minecraft").unwrap();
        assert_eq!(mc.versions, ["~26.3", "26.4"]);
        assert_eq!(m.breaks[0].id, "optifabric");
        assert!(m.breaks[0].versions.is_empty());

        let (deps, breaks) = toml_requirements(
            "[[mods]]\nmodId=\"a\"\n[[dependencies.a]]\n modId=\"minecraft\"\n mandatory=true\n versionRange=\"[1.21.1,1.22)\"\n[[dependencies.a]]\n modId=\"jei\"\n type=\"optional\"\n[[dependencies.a]]\n modId=\"rubidium\"\n type=\"incompatible\"\n",
        );
        assert_eq!(deps.len(), 1);
        let dir2 = tempfile::tempdir().unwrap();
        jar(
            dir2.path(),
            "create.jar",
            &[(
                "META-INF/neoforge.mods.toml",
                "modLoader=\"javafml\"\n[[mods]] #mandatory\nmodId=\"create\" #mandatory\nversion=\"6.0.10\"\n[[dependencies.create]]\nmodId=\"neoforge\"\ntype=\"required\"\n[[dependencies.create]]\nmodId=\"flywheel\"\ntype=\"required\"\n",
            )],
        );
        let c = read(&dir2.path().join("create.jar")).unwrap();
        assert_eq!(
            (c.ids.clone(), c.version.as_deref()),
            (vec!["create".to_owned()], Some("6.0.10"))
        );
        assert_eq!(c.depends.len(), 2);
        assert_eq!(deps[0].versions, ["[1.21.1,1.22)"]);
        assert_eq!(breaks[0].id, "rubidium");
    }
}
