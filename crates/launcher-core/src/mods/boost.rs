//! The performance pack: a few well-known client-side optimisation mods that make the game run
//! faster without changing how it plays, installed together from Modrinth. Aerion's own list;
//! each mod is resolved for the instance's game version and loader when it is installed, so the
//! list never has to follow anyone's release schedule.

use std::collections::HashSet;
use std::path::Path;

use serde::Serialize;

use crate::loaders::LoaderKind;
use crate::mods::meta;

/// One mod of the pack.
#[derive(Debug, Clone, Copy)]
pub struct BoostMod {
    /// Modrinth project id (stable; the slug can change).
    pub project_id: &'static str,
    pub title: &'static str,
    /// Mod ids that count as "already there": the mod itself and its known forks.
    pub mod_ids: &'static [&'static str],
}

/// The pack, most important first. Only mods that keep vanilla gameplay, have stable releases
/// and were checked in game together with the Aerion mod (its themed menus and inventories) on
/// 26.3 and 1.21.11. Candidates waiting for that check: ModernFix (`nmDcB62a`), EntityCulling
/// (`NNAgCjsB`).
pub const PACK: [BoostMod; 4] = [
    BoostMod {
        project_id: "AANobbMI",
        title: "Sodium",
        mod_ids: &["sodium", "embeddium", "rubidium"],
    },
    BoostMod {
        project_id: "gvQqBUqZ",
        title: "Lithium",
        mod_ids: &["lithium", "canary", "radium"],
    },
    BoostMod {
        project_id: "uXXizFIs",
        title: "FerriteCore",
        mod_ids: &["ferritecore"],
    },
    BoostMod {
        project_id: "5ZwdcRci",
        title: "ImmediatelyFast",
        mod_ids: &["immediatelyfast"],
    },
];

/// The pack is offered for these loaders (the ones every mod of the pack is built for).
pub fn supports(loader: LoaderKind) -> bool {
    matches!(loader, LoaderKind::Fabric | LoaderKind::Quilt)
}

/// One mod of the pack and whether the instance already has it.
#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct BoostItem {
    pub project_id: String,
    pub title: String,
    pub present: bool,
}

/// What the install did, for the message shown afterwards.
#[derive(Debug, Clone, Default, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct BoostResult {
    /// Pack mods installed now (titles).
    pub added: Vec<String>,
    /// Pack mods that were already in the instance.
    pub present: Vec<String>,
    /// Pack mods with no release for this game version and loader yet.
    pub unavailable: Vec<String>,
}

/// Mod ids found in the jars of `mods_dir`, switched off ones included: a mod the player turned
/// off is their choice, not something to install again. Bundled (nested) ids count too.
pub fn present_ids(mods_dir: &Path) -> HashSet<String> {
    let Ok(dir) = std::fs::read_dir(mods_dir) else {
        return HashSet::new();
    };
    dir.flatten()
        .filter(|e| {
            let name = e.file_name().to_string_lossy().to_ascii_lowercase();
            name.ends_with(".jar") || name.ends_with(".jar.disabled")
        })
        .filter_map(|e| meta::read(&e.path()))
        .flat_map(|m| m.ids.into_iter().chain(m.nested_ids))
        .collect()
}

/// The pack against the instance: which mods are there (by jar contents or by the launcher's
/// own record of installed projects).
pub fn status(mods_dir: &Path, installed_projects: &HashSet<String>) -> Vec<BoostItem> {
    let ids = present_ids(mods_dir);
    PACK.iter()
        .map(|m| BoostItem {
            project_id: m.project_id.into(),
            title: m.title.into(),
            present: installed_projects.contains(m.project_id)
                || m.mod_ids.iter().any(|id| ids.contains(*id)),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn jar(dir: &Path, file: &str, fabric_json: &str) {
        let f = std::fs::File::create(dir.join(file)).unwrap();
        let mut zip = zip::ZipWriter::new(f);
        zip.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(fabric_json.as_bytes()).unwrap();
        zip.finish().unwrap();
    }

    #[test]
    fn finds_pack_mods_by_jar_contents_forks_and_records() {
        let d = tempfile::tempdir().unwrap();
        // Installed by hand under an odd file name: still recognised by its mod id.
        jar(
            d.path(),
            "renderer.jar",
            r#"{"id":"sodium","version":"0.6"}"#,
        );
        // Switched off by the player: left alone.
        jar(
            d.path(),
            "lith.jar.disabled",
            r#"{"id":"lithium","version":"1"}"#,
        );
        // A fork counts as the mod.
        jar(d.path(), "x.jar", r#"{"id":"embeddium","version":"1"}"#);
        let recorded: HashSet<String> = ["5ZwdcRci".to_string()].into();
        let s = status(d.path(), &recorded);
        let present: Vec<&str> = s
            .iter()
            .filter(|i| i.present)
            .map(|i| i.title.as_str())
            .collect();
        assert_eq!(present, ["Sodium", "Lithium", "ImmediatelyFast"]);
        assert_eq!(s.len(), PACK.len());
    }

    #[test]
    fn nothing_found_in_a_missing_folder() {
        let s = status(Path::new("/nonexistent/mods"), &HashSet::new());
        assert!(s.iter().all(|i| !i.present));
    }

    #[test]
    fn offered_for_fabric_and_quilt_only() {
        assert!(supports(LoaderKind::Fabric));
        assert!(supports(LoaderKind::Quilt));
        assert!(!supports(LoaderKind::Forge));
        assert!(!supports(LoaderKind::Vanilla));
        assert!(!supports(LoaderKind::Horizon));
    }
}
