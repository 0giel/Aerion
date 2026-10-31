//! Mod platforms (Modrinth, CurseForge) behind one model, plus installed-content management.

pub mod content;
pub mod curseforge;
pub mod model;
pub mod modrinth;
pub mod resolve;
pub mod select;

use model::{ProjectRef, SearchPage};

fn normalize(s: &str) -> String {
    s.chars()
        .filter(|c| c.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect()
}

/// Combine a Modrinth and a CurseForge page: results are interleaved by rank and projects
/// present on both platforms are shown once (Modrinth entry, with a link to the other one).
pub fn merge_pages(modrinth: SearchPage, curseforge: SearchPage) -> SearchPage {
    let mut cf = curseforge.hits;
    let mut mr = modrinth.hits;
    for m in &mut mr {
        let key = normalize(&m.title);
        if let Some(pos) = cf
            .iter()
            .position(|c| c.slug == m.slug || normalize(&c.title) == key)
        {
            let c = cf.remove(pos);
            m.also_on = Some(ProjectRef {
                provider: c.provider,
                id: c.id,
            });
        }
    }
    let mut merged = Vec::with_capacity(mr.len() + cf.len());
    let (mut a, mut b) = (mr.into_iter(), cf.into_iter());
    loop {
        match (a.next(), b.next()) {
            (None, None) => break,
            (x, y) => {
                merged.extend(x);
                merged.extend(y);
            }
        }
    }
    SearchPage {
        hits: merged,
        total: modrinth.total + curseforge.total,
        offset: modrinth.offset,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use model::*;

    fn hit(provider: Provider, id: &str, title: &str, slug: &str) -> ProjectSummary {
        ProjectSummary {
            provider,
            id: id.into(),
            slug: slug.into(),
            title: title.into(),
            description: String::new(),
            author: String::new(),
            icon_url: None,
            downloads: 0,
            follows: None,
            updated: None,
            categories: vec![],
            kind: ContentKind::Mod,
            also_on: None,
            page_url: String::new(),
        }
    }

    #[test]
    fn interleaves_and_dedupes() {
        let mr = SearchPage {
            hits: vec![
                hit(Provider::Modrinth, "m1", "Sodium", "sodium"),
                hit(Provider::Modrinth, "m2", "Lithium", "lithium"),
            ],
            total: 2,
            offset: 0,
        };
        let cf = SearchPage {
            hits: vec![
                hit(Provider::Curseforge, "c1", "JEI", "jei"),
                hit(Provider::Curseforge, "c2", "Sodium", "sodium-cf"),
                hit(Provider::Curseforge, "c3", "Create", "create"),
            ],
            total: 3,
            offset: 0,
        };
        let merged = merge_pages(mr, cf);
        let ids: Vec<&str> = merged.hits.iter().map(|h| h.id.as_str()).collect();
        assert_eq!(ids, ["m1", "c1", "m2", "c3"]);
        assert_eq!(
            merged.hits[0].also_on,
            Some(ProjectRef {
                provider: Provider::Curseforge,
                id: "c2".into()
            })
        );
    }
}
