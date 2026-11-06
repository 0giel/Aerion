//! Checks a mods folder before launch, from each jar's own metadata: mods for another loader,
//! the same mod twice, missing or too old dependencies, declared conflicts and mods made for
//! another game version. Only certain problems are reported (see [`super::ranges`]).

use std::collections::HashMap;
use std::path::Path;

use serde::Serialize;

use super::meta::{self, Family, ModMeta, Requirement};
use super::ranges;
use crate::instances::LoaderKind;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub enum IssueKind {
    /// Made for another loader (e.g. a Forge mod in a Fabric instance).
    WrongLoader,
    /// Another enabled jar provides the same mod.
    Duplicate,
    /// A required mod is not installed.
    MissingDependency,
    /// A required mod is installed in a version the mod doesn't accept.
    DependencyVersion,
    /// The mod declares it doesn't work with another installed mod.
    Conflict,
    /// Made for other Minecraft versions.
    WrongGameVersion,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ModIssue {
    pub kind: IssueKind,
    /// The jar with the problem (in `mods/`).
    pub file: String,
    pub name: String,
    /// The other mod involved: the missing dependency's id, the conflicting or duplicate mod.
    pub other: Option<String>,
    /// The other mod's jar, when it is installed.
    pub other_file: Option<String>,
    /// What the mod asks for (a version range), as written by its author.
    pub required: Option<String>,
}

/// Provided by the loader or the game, never by a jar in `mods/`.
fn builtin(id: &str) -> bool {
    matches!(
        id,
        "minecraft"
            | "java"
            | "fabricloader"
            | "fabric-loader"
            | "quilt_loader"
            | "forge"
            | "neoforge"
            | "javafml"
            | "lowcodefml"
            | "mixinextras"
    )
}

fn loads(loader: LoaderKind, family: Family) -> bool {
    match loader {
        LoaderKind::Fabric => family == Family::Fabric,
        LoaderKind::Quilt => matches!(family, Family::Fabric | Family::Quilt),
        LoaderKind::Forge => family == Family::Forge,
        // NeoForge still reads `mods.toml` on its first versions.
        LoaderKind::Neoforge => matches!(family, Family::NeoForge | Family::Forge),
        // No Java at all: neither Java mods nor a mod loader.
        LoaderKind::Vanilla | LoaderKind::Horizon => false,
    }
}

fn display(m: &ModMeta) -> String {
    m.name.clone().unwrap_or_else(|| m.ids[0].clone())
}

/// Check the enabled jars of `mods_dir` for an instance of `game_version` with `loader`.
pub fn check(mods_dir: &Path, game_version: &str, loader: LoaderKind) -> Vec<ModIssue> {
    let Ok(dir) = std::fs::read_dir(mods_dir) else {
        return Vec::new();
    };
    let mut jars: Vec<(String, ModMeta)> = dir
        .flatten()
        .filter_map(|e| {
            let file = e.file_name().to_string_lossy().into_owned();
            file.to_ascii_lowercase()
                .ends_with(".jar")
                .then(|| meta::read(&e.path()).map(|m| (file, m)))
                .flatten()
        })
        .collect();
    jars.sort_by(|a, b| a.0.cmp(&b.0));

    let mut issues = Vec::new();
    let issue = |kind, file: &str, m: &ModMeta| ModIssue {
        kind,
        file: file.to_owned(),
        name: display(m),
        other: None,
        other_file: None,
        required: None,
    };

    // Wrong loader first: such jars are skipped by the game, so they satisfy nothing.
    let (usable, foreign): (Vec<_>, Vec<_>) = jars
        .into_iter()
        .partition(|(_, m)| loader == LoaderKind::Vanilla || loads(loader, m.family));
    for (file, m) in &foreign {
        issues.push(issue(IssueKind::WrongLoader, file, m));
    }

    // Who provides which id: the jars' own ids first (duplicates), bundled ones only satisfy.
    let mut own: HashMap<String, Vec<usize>> = HashMap::new();
    let mut provided: HashMap<String, usize> = HashMap::new();
    for (i, (_, m)) in usable.iter().enumerate() {
        for id in &m.ids {
            own.entry(id.clone()).or_default().push(i);
            provided.entry(id.clone()).or_insert(i);
        }
        for id in &m.nested_ids {
            provided.entry(id.clone()).or_insert(i);
        }
    }
    let mut dup_reported = std::collections::HashSet::new();
    for owners in own.values() {
        if owners.len() < 2 || !dup_reported.insert(owners[0]) {
            continue;
        }
        let (first, fm) = &usable[owners[0]];
        for &o in &owners[1..] {
            let (file, m) = &usable[o];
            issues.push(ModIssue {
                other: Some(display(fm)),
                other_file: Some(first.clone()),
                ..issue(IssueKind::Duplicate, file, m)
            });
        }
    }

    let game = ranges::release(game_version);
    let req_text = |r: &Requirement| (!r.versions.is_empty()).then(|| r.versions.join(" || "));
    for (file, m) in &usable {
        for dep in &m.depends {
            if dep.id == "minecraft" {
                if let Some(g) = &game
                    && ranges::satisfies(g, &dep.versions) == Some(false)
                {
                    issues.push(ModIssue {
                        required: req_text(dep),
                        ..issue(IssueKind::WrongGameVersion, file, m)
                    });
                }
                continue;
            }
            if builtin(&dep.id) {
                continue;
            }
            match provided.get(&dep.id) {
                None => issues.push(ModIssue {
                    other: Some(dep.id.clone()),
                    required: req_text(dep),
                    ..issue(IssueKind::MissingDependency, file, m)
                }),
                Some(&i) => {
                    let (other_file, other) = &usable[i];
                    // Versions are only compared for a jar's own ids (bundled ones vary).
                    let have = other
                        .ids
                        .contains(&dep.id)
                        .then(|| other.version.as_deref().and_then(ranges::numeric))
                        .flatten();
                    if let Some(v) = have
                        && ranges::satisfies(&v, &dep.versions) == Some(false)
                    {
                        issues.push(ModIssue {
                            other: Some(display(other)),
                            other_file: Some(other_file.clone()),
                            required: req_text(dep),
                            ..issue(IssueKind::DependencyVersion, file, m)
                        });
                    }
                }
            }
        }
        for br in &m.breaks {
            let Some(&i) = own.get(&br.id).and_then(|o| o.first()) else {
                continue;
            };
            let (other_file, other) = &usable[i];
            if other_file == file {
                continue;
            }
            let matches = if br.versions.is_empty() {
                Some(true)
            } else {
                other
                    .version
                    .as_deref()
                    .and_then(ranges::numeric)
                    .and_then(|v| ranges::satisfies(&v, &br.versions))
            };
            if matches == Some(true) {
                issues.push(ModIssue {
                    other: Some(display(other)),
                    other_file: Some(other_file.clone()),
                    required: req_text(br),
                    ..issue(IssueKind::Conflict, file, m)
                });
            }
        }
    }
    issues.sort_by(|a, b| {
        a.file
            .cmp(&b.file)
            .then((a.kind as u8).cmp(&(b.kind as u8)))
    });
    issues
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mods::meta::tests::jar;

    #[test]
    fn finds_the_usual_problems() {
        let dir = tempfile::tempdir().unwrap();
        let d = dir.path();
        jar(
            d,
            "fabric-api.jar",
            &[(
                "fabric.mod.json",
                r#"{"id":"fabric-api","version":"0.165.0+26.3","depends":{"minecraft":"~26.3"}}"#,
            )],
        );
        jar(
            d,
            "sodium-0.6.jar",
            &[(
                "fabric.mod.json",
                r#"{"id":"sodium","name":"Sodium","version":"0.6.1+mc26.3","depends":{"minecraft":"26.3","fabric-api":"*"}}"#,
            )],
        );
        jar(
            d,
            "sodium-old.jar",
            &[(
                "fabric.mod.json",
                r#"{"id":"sodium","name":"Sodium","version":"0.5.0","depends":{"minecraft":"1.21.1"}}"#,
            )],
        );
        jar(
            d,
            "iris.jar",
            &[(
                "fabric.mod.json",
                r#"{"id":"iris","name":"Iris","version":"1.8","depends":{"sodium":">=0.7","cloth-config":"*"},"breaks":{"optifabric":"*"}}"#,
            )],
        );
        jar(
            d,
            "optifabric.jar",
            &[("fabric.mod.json", r#"{"id":"optifabric","version":"1.0"}"#)],
        );
        jar(
            d,
            "create.jar",
            &[(
                "META-INF/neoforge.mods.toml",
                "[[mods]]\nmodId=\"create\"\ndisplayName=\"Create\"\n",
            )],
        );
        jar(d, "library.jar", &[("readme.txt", "not a mod")]);
        std::fs::write(d.join("disabled.jar.disabled"), b"x").unwrap();

        let issues = check(d, "26.3", LoaderKind::Fabric);
        let has = |kind, file: &str| issues.iter().any(|i| i.kind == kind && i.file == file);
        assert!(has(IssueKind::WrongLoader, "create.jar"));
        assert!(has(IssueKind::Duplicate, "sodium-old.jar"));
        assert!(has(IssueKind::WrongGameVersion, "sodium-old.jar"));
        assert!(has(IssueKind::MissingDependency, "iris.jar"));
        assert!(has(IssueKind::DependencyVersion, "iris.jar"));
        assert!(has(IssueKind::Conflict, "iris.jar"));
        assert!(!has(IssueKind::WrongGameVersion, "sodium-0.6.jar"));
        let missing = issues
            .iter()
            .find(|i| i.kind == IssueKind::MissingDependency)
            .unwrap();
        assert_eq!(missing.other.as_deref(), Some("cloth-config"));
        // Nothing else: builtins and bundled ids are satisfied, unknown jars ignored.
        assert_eq!(issues.len(), 6, "{issues:#?}");
    }

    #[test]
    fn snapshots_are_not_judged() {
        let dir = tempfile::tempdir().unwrap();
        jar(
            dir.path(),
            "a.jar",
            &[(
                "fabric.mod.json",
                r#"{"id":"a","depends":{"minecraft":"~26.3"}}"#,
            )],
        );
        assert!(check(dir.path(), "26.4-snapshot-6", LoaderKind::Fabric).is_empty());
    }
}
