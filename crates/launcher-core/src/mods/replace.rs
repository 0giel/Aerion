//! Suggestions for a mod that has no version for an instance (or conflicts with it): a short list
//! of well-known successors first, then similar projects found on Modrinth. Only projects with a
//! version for the instance's game version and loader are offered; the UI labels them as
//! suggestions, not equivalents.

use serde::Serialize;

use super::model::{ContentKind, ModProvider, ProjectSummary, SearchQuery, SortOrder};
use super::modrinth::Modrinth;
use super::select::loaders_for;
use crate::error::Result;
use crate::loaders::LoaderKind;

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct Replacement {
    pub project: ProjectSummary,
    /// From the curated list of known successors (rather than a similarity search).
    pub known: bool,
}

/// Mods whose usual successors are well established (matched on slug or title words).
const KNOWN: &[(&str, &[&str])] = &[
    ("optifine", &["sodium", "iris"]),
    ("optifabric", &["sodium", "iris"]),
    ("rubidium", &["embeddium", "sodium"]),
    ("magnesium", &["embeddium", "sodium"]),
    ("oculus", &["iris"]),
    ("roughly-enough-items", &["emi", "jei"]),
    ("canvas-renderer", &["sodium", "iris"]),
];

fn normalise(s: &str) -> String {
    s.to_ascii_lowercase()
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect::<String>()
        .split('-')
        .filter(|w| !w.is_empty())
        .collect::<Vec<_>>()
        .join("-")
}

/// Curated successors for a mod, by slug or title.
fn known_successors(slug: Option<&str>, title: &str) -> &'static [&'static str] {
    let keys = [slug.map(normalise), Some(normalise(title))];
    KNOWN
        .iter()
        .find(|(k, _)| {
            keys.iter()
                .flatten()
                .any(|s| s == k || s.split('-').any(|w| w == *k) || s.starts_with(&format!("{k}-")))
        })
        .map_or(&[], |(_, v)| v)
}

/// Words that say nothing about what a mod does.
const NOISE: &[&str] = &[
    "fabric", "forge", "neoforge", "quilt", "mod", "mc", "api", "lib", "the", "for", "and",
];

/// A search query from a mod's title: version numbers and loader names removed.
fn query(title: &str) -> String {
    title
        .split(|c: char| !c.is_alphanumeric())
        .filter(|w| {
            !w.is_empty()
                && !w.chars().all(|c| c.is_ascii_digit())
                && !NOISE.contains(&w.to_ascii_lowercase().as_str())
        })
        .take(4)
        .collect::<Vec<_>>()
        .join(" ")
}

#[allow(clippy::too_many_arguments)]
pub async fn find(
    modrinth: &Modrinth,
    kind: ContentKind,
    title: &str,
    slug: Option<&str>,
    project_id: Option<&str>,
    categories: Vec<String>,
    game_version: &str,
    loader: LoaderKind,
) -> Result<Vec<Replacement>> {
    let loaders = loaders_for(kind, loader, game_version);
    let mut out: Vec<Replacement> = Vec::new();
    let known = known_successors(slug, title);
    if !known.is_empty() {
        let ids: Vec<String> = known.iter().map(|s| (*s).to_owned()).collect();
        for p in modrinth.projects(&ids).await? {
            if !modrinth
                .versions(&p.id, Some(game_version), &loaders)
                .await?
                .is_empty()
            {
                out.push(Replacement {
                    project: p,
                    known: true,
                });
            }
        }
    }
    let q = query(title);
    if !q.is_empty() {
        let page = modrinth
            .search(&SearchQuery {
                query: q,
                kind,
                game_version: Some(game_version.to_owned()),
                loader: Some(loader),
                categories,
                sort: SortOrder::Relevance,
                offset: 0,
                limit: 8,
            })
            .await?;
        for p in page.hits {
            let same = Some(p.id.as_str()) == project_id || Some(p.slug.as_str()) == slug;
            if !same && !out.iter().any(|r| r.project.id == p.id) {
                out.push(Replacement {
                    project: p,
                    known: false,
                });
            }
        }
    }
    out.truncate(8);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_and_queries() {
        assert_eq!(
            known_successors(None, "OptiFine_1.21.1_HD_U_J1"),
            ["sodium", "iris"]
        );
        assert_eq!(
            known_successors(Some("rubidium"), "Rubidium"),
            ["embeddium", "sodium"]
        );
        assert!(known_successors(Some("create"), "Create").is_empty());
        assert_eq!(
            query("Xaero's Minimap 25.2.0 for Fabric"),
            "Xaero s Minimap"
        );
        assert_eq!(query("journeymap-fabric-1.21.1-6.0.0"), "journeymap");
    }
}
