//! Choosing the best file for an instance.

use crate::loaders::{LoaderKind, NEOFORGE_FORGE_FORK};
use crate::mods::model::{ContentKind, ProjectVersion, ReleaseKind};

/// Loader tags a mod may carry to run on `loader`, in order of preference.
pub fn compatible_loaders(loader: LoaderKind, game_version: &str) -> Vec<&'static str> {
    match loader {
        // Horizon runs no Java mods (it has its own `.hmod` format).
        LoaderKind::Vanilla | LoaderKind::Horizon => vec![],
        LoaderKind::Fabric => vec!["fabric"],
        // Quilt loads Fabric mods.
        LoaderKind::Quilt => vec!["quilt", "fabric"],
        LoaderKind::Forge => vec!["forge"],
        LoaderKind::Neoforge if game_version == NEOFORGE_FORGE_FORK => vec!["neoforge", "forge"],
        LoaderKind::Neoforge => vec!["neoforge"],
    }
}

/// Loader tags to filter versions by, for a kind of content.
pub fn loaders_for(kind: ContentKind, loader: LoaderKind, game_version: &str) -> Vec<&'static str> {
    match kind {
        ContentKind::Mod | ContentKind::Modpack => compatible_loaders(loader, game_version),
        ContentKind::DataPack => vec!["datapack"],
        // Resource packs ("minecraft") and shaders (iris/optifine/canvas) are not filtered by loader.
        ContentKind::ResourcePack | ContentKind::Shader => vec![],
    }
}

/// Best version: compatible game version and loader, then release > beta > alpha,
/// then preferred loader, then newest.
pub fn best_version<'a>(
    versions: &'a [ProjectVersion],
    game_version: &str,
    loaders: &[&str],
) -> Option<&'a ProjectVersion> {
    let loader_rank = |v: &ProjectVersion| -> usize {
        if loaders.is_empty() {
            return 0;
        }
        loaders
            .iter()
            .position(|l| v.loaders.iter().any(|x| x == l))
            .unwrap_or(usize::MAX)
    };
    versions
        .iter()
        .filter(|v| game_version.is_empty() || v.game_versions.iter().any(|g| g == game_version))
        .filter(|v| loader_rank(v) != usize::MAX)
        .filter(|v| v.primary_file().is_some())
        .min_by(|a, b| {
            let rank = |k: ReleaseKind| k as u8;
            rank(a.kind)
                .cmp(&rank(b.kind))
                .then(loader_rank(a).cmp(&loader_rank(b)))
                .then(b.published.cmp(&a.published))
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mods::model::{Provider, VersionFile};

    fn v(id: &str, kind: ReleaseKind, gv: &str, loaders: &[&str], date: &str) -> ProjectVersion {
        ProjectVersion {
            provider: Provider::Modrinth,
            id: id.into(),
            project_id: "p".into(),
            name: id.into(),
            version_number: id.into(),
            kind,
            game_versions: vec![gv.into()],
            loaders: loaders.iter().map(|s| (*s).to_owned()).collect(),
            published: date.into(),
            downloads: 0,
            files: vec![VersionFile {
                url: Some("u".into()),
                filename: format!("{id}.jar"),
                size: 1,
                sha1: None,
                sha512: None,
                primary: true,
            }],
            dependencies: vec![],
            page_url: None,
        }
    }

    #[test]
    fn prefers_release_then_newest() {
        let list = vec![
            v(
                "beta-new",
                ReleaseKind::Beta,
                "26.3",
                &["fabric"],
                "2026-10-02",
            ),
            v(
                "rel-old",
                ReleaseKind::Release,
                "26.3",
                &["fabric"],
                "2026-09-01",
            ),
            v(
                "rel-new",
                ReleaseKind::Release,
                "26.3",
                &["fabric"],
                "2026-09-20",
            ),
            v(
                "other-mc",
                ReleaseKind::Release,
                "1.21.11",
                &["fabric"],
                "2026-10-10",
            ),
            v(
                "forge",
                ReleaseKind::Release,
                "26.3",
                &["forge"],
                "2026-10-10",
            ),
        ];
        assert_eq!(
            best_version(&list, "26.3", &["fabric"]).unwrap().id,
            "rel-new"
        );
        let only_beta = vec![list[0].clone(), list[3].clone()];
        assert_eq!(
            best_version(&only_beta, "26.3", &["fabric"]).unwrap().id,
            "beta-new"
        );
        assert!(best_version(&list, "26.3", &["neoforge"]).is_none());
    }

    #[test]
    fn quilt_prefers_native_then_fabric() {
        let list = vec![
            v(
                "fabric",
                ReleaseKind::Release,
                "1.21.11",
                &["fabric"],
                "2026-10-02",
            ),
            v(
                "quilt",
                ReleaseKind::Release,
                "1.21.11",
                &["quilt"],
                "2026-09-01",
            ),
        ];
        let loaders = compatible_loaders(LoaderKind::Quilt, "1.21.11");
        assert_eq!(
            best_version(&list, "1.21.11", &loaders).unwrap().id,
            "quilt"
        );
        assert_eq!(
            best_version(&list[..1], "1.21.11", &loaders).unwrap().id,
            "fabric"
        );
        assert_eq!(
            compatible_loaders(LoaderKind::Neoforge, "1.20.1"),
            ["neoforge", "forge"]
        );
        assert_eq!(
            compatible_loaders(LoaderKind::Neoforge, "26.3"),
            ["neoforge"]
        );
    }
}
