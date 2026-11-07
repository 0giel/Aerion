//! Dependency resolution for one-click installs.

use std::collections::{HashMap, HashSet, VecDeque};

use serde::Serialize;

use crate::error::{Error, Result};
use crate::mods::model::*;
use crate::mods::select::best_version;

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct PlannedFile {
    pub project_id: String,
    pub title: String,
    pub icon_url: Option<String>,
    pub page_url: Option<String>,
    pub version: ProjectVersion,
    /// Title of the project that required this one (`None` = requested by the user).
    pub required_by: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ProjectNote {
    pub project_id: String,
    pub title: String,
    /// Project that declared the relation.
    pub by: String,
    /// Manual download page (distribution disabled) or project page.
    pub url: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct InstallPlan {
    pub provider: Option<Provider>,
    pub install: Vec<PlannedFile>,
    /// Optional dependencies to offer.
    pub optional: Vec<ProjectNote>,
    /// Installed (or planned) projects declared incompatible.
    pub incompatible: Vec<ProjectNote>,
    /// Required files that cannot be downloaded automatically.
    pub unavailable: Vec<ProjectNote>,
}

pub struct Target<'a> {
    pub game_version: &'a str,
    pub loaders: &'a [&'a str],
}

pub async fn plan<P: ModProvider>(
    provider: &P,
    project_id: &str,
    version_id: Option<&str>,
    target: &Target<'_>,
    installed: &HashSet<(Provider, String)>,
) -> Result<InstallPlan> {
    let p = provider.provider();
    let mut plan = InstallPlan {
        provider: Some(p),
        ..Default::default()
    };
    let mut queue: VecDeque<(String, Option<String>, Option<String>)> = VecDeque::new();
    queue.push_back((project_id.to_owned(), version_id.map(str::to_owned), None));
    let mut seen: HashSet<String> = HashSet::new();
    let mut optional: Vec<(String, String)> = Vec::new();
    let mut incompatible: Vec<(String, String)> = Vec::new();

    while let Some((pid, vid, required_by)) = queue.pop_front() {
        if !seen.insert(pid.clone()) {
            continue;
        }
        if required_by.is_some() && installed.contains(&(p, pid.clone())) {
            continue;
        }
        let version = match &vid {
            Some(v) => provider
                .versions_by_id(std::slice::from_ref(v))
                .await?
                .into_iter()
                .next(),
            None => {
                let list = provider
                    .versions(&pid, Some(target.game_version), target.loaders)
                    .await?;
                best_version(&list, target.game_version, target.loaders).cloned()
            }
        };
        let Some(version) = version else {
            if required_by.is_none() {
                return Err(Error::NoCompatibleFile {
                    project: pid,
                    game_version: target.game_version.into(),
                    loader: target.loaders.first().copied().unwrap_or("vanilla").into(),
                });
            }
            plan.unavailable.push(ProjectNote {
                project_id: pid,
                title: String::new(),
                by: required_by.unwrap_or_default(),
                url: None,
            });
            continue;
        };
        if version
            .primary_file()
            .and_then(|f| f.url.as_ref())
            .is_none()
        {
            plan.unavailable.push(ProjectNote {
                project_id: pid.clone(),
                title: String::new(),
                by: required_by.clone().unwrap_or_default(),
                url: version.page_url.clone(),
            });
            continue;
        }
        for dep in &version.dependencies {
            let dep_pid = match (&dep.project_id, &dep.version_id) {
                (Some(id), _) => id.clone(),
                (None, Some(vid)) => match provider
                    .versions_by_id(std::slice::from_ref(vid))
                    .await?
                    .into_iter()
                    .next()
                {
                    Some(v) => v.project_id,
                    None => continue,
                },
                (None, None) => continue,
            };
            match dep.kind {
                DependencyKind::Required => {
                    queue.push_back((dep_pid, dep.version_id.clone(), Some(pid.clone())))
                }
                DependencyKind::Optional => {
                    if !installed.contains(&(p, dep_pid.clone())) {
                        optional.push((dep_pid, pid.clone()));
                    }
                }
                DependencyKind::Incompatible => incompatible.push((dep_pid, pid.clone())),
                DependencyKind::Embedded => {}
            }
        }
        plan.install.push(PlannedFile {
            project_id: pid,
            title: String::new(),
            icon_url: None,
            page_url: None,
            version,
            required_by,
        });
    }

    let planned: HashSet<String> = plan.install.iter().map(|f| f.project_id.clone()).collect();
    plan.optional = optional
        .into_iter()
        .filter(|(id, _)| !planned.contains(id))
        .map(|(id, by)| ProjectNote {
            project_id: id,
            title: String::new(),
            by,
            url: None,
        })
        .collect();
    plan.incompatible = incompatible
        .into_iter()
        .filter(|(id, _)| planned.contains(id) || installed.contains(&(p, id.clone())))
        .map(|(id, by)| ProjectNote {
            project_id: id,
            title: String::new(),
            by,
            url: None,
        })
        .collect();
    plan.optional.dedup_by(|a, b| a.project_id == b.project_id);

    // One bulk request for titles and icons.
    let mut ids: Vec<String> = planned.iter().cloned().collect();
    for n in plan
        .optional
        .iter()
        .chain(&plan.incompatible)
        .chain(&plan.unavailable)
    {
        ids.push(n.project_id.clone());
        ids.push(n.by.clone());
    }
    ids.retain(|i| !i.is_empty());
    ids.sort();
    ids.dedup();
    let info: HashMap<String, ProjectSummary> = provider
        .projects(&ids)
        .await
        .unwrap_or_default()
        .into_iter()
        .map(|s| (s.id.clone(), s))
        .collect();
    let title = |id: &str| {
        info.get(id)
            .map(|s| s.title.clone())
            .unwrap_or_else(|| id.to_owned())
    };
    for f in &mut plan.install {
        if let Some(s) = info.get(&f.project_id) {
            f.title = s.title.clone();
            f.icon_url = s.icon_url.clone();
            f.page_url = Some(s.page_url.clone());
        } else {
            f.title = f.project_id.clone();
        }
        if let Some(by) = &f.required_by {
            f.required_by = Some(title(by));
        }
    }
    for n in plan
        .optional
        .iter_mut()
        .chain(plan.incompatible.iter_mut())
        .chain(plan.unavailable.iter_mut())
    {
        n.title = title(&n.project_id);
        n.by = title(&n.by);
        if n.url.is_none() {
            n.url = info.get(&n.project_id).map(|s| s.page_url.clone());
        }
    }
    Ok(plan)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    /// In-memory provider: project id → versions.
    struct Fake {
        versions: HashMap<String, Vec<ProjectVersion>>,
        calls: Mutex<u32>,
    }

    fn version(project: &str, deps: Vec<(&str, DependencyKind)>, url: bool) -> ProjectVersion {
        ProjectVersion {
            provider: Provider::Modrinth,
            id: format!("{project}-v1"),
            project_id: project.into(),
            name: "1.0".into(),
            version_number: "1.0".into(),
            kind: ReleaseKind::Release,
            game_versions: vec!["26.3".into()],
            loaders: vec!["fabric".into()],
            published: "2026-10-01".into(),
            downloads: 0,
            files: vec![VersionFile {
                url: url.then(|| "https://cdn/x.jar".into()),
                filename: format!("{project}.jar"),
                size: 1,
                sha1: Some("aa".into()),
                sha512: None,
                primary: true,
            }],
            dependencies: deps
                .into_iter()
                .map(|(p, k)| Dependency {
                    project_id: Some(p.into()),
                    version_id: None,
                    kind: k,
                })
                .collect(),
            page_url: Some(format!("https://example/{project}")),
        }
    }

    impl ModProvider for Fake {
        fn provider(&self) -> Provider {
            Provider::Modrinth
        }
        async fn search(&self, _q: &SearchQuery) -> Result<SearchPage> {
            unimplemented!()
        }
        async fn project(&self, _id: &str) -> Result<ProjectDetails> {
            unimplemented!()
        }
        async fn projects(&self, ids: &[String]) -> Result<Vec<ProjectSummary>> {
            *self.calls.lock().unwrap() += 1;
            Ok(ids
                .iter()
                .map(|id| ProjectSummary {
                    provider: Provider::Modrinth,
                    id: id.clone(),
                    slug: id.clone(),
                    title: format!("Title {id}"),
                    description: String::new(),
                    author: String::new(),
                    icon_url: None,
                    downloads: 0,
                    follows: None,
                    updated: None,
                    categories: vec![],
                    kind: ContentKind::Mod,
                    also_on: None,
                    page_url: format!("https://example/{id}"),
                })
                .collect())
        }
        async fn versions(
            &self,
            project_id: &str,
            _gv: Option<&str>,
            _l: &[&str],
        ) -> Result<Vec<ProjectVersion>> {
            Ok(self.versions.get(project_id).cloned().unwrap_or_default())
        }
        async fn versions_by_id(&self, ids: &[String]) -> Result<Vec<ProjectVersion>> {
            Ok(self
                .versions
                .values()
                .flatten()
                .filter(|v| ids.contains(&v.id))
                .cloned()
                .collect())
        }
        async fn categories(&self, _k: ContentKind) -> Result<Vec<Category>> {
            Ok(vec![])
        }
        async fn identify(&self, _f: &[LocalFile]) -> Result<HashMap<String, Identified>> {
            Ok(HashMap::new())
        }
    }

    #[tokio::test]
    async fn resolves_required_offers_optional_flags_conflicts() {
        let mut versions = HashMap::new();
        versions.insert(
            "sodium".into(),
            vec![version(
                "sodium",
                vec![
                    ("fabric-api", DependencyKind::Required),
                    ("extras", DependencyKind::Optional),
                    ("optifine", DependencyKind::Incompatible),
                ],
                true,
            )],
        );
        versions.insert(
            "fabric-api".into(),
            vec![version(
                "fabric-api",
                vec![("sodium", DependencyKind::Optional)],
                true,
            )],
        );
        versions.insert("extras".into(), vec![version("extras", vec![], true)]);
        let fake = Fake {
            versions,
            calls: Mutex::new(0),
        };
        let mut installed: HashSet<(Provider, String)> = HashSet::new();
        installed.insert((Provider::Modrinth, "optifine".into()));
        let plan = plan(
            &fake,
            "sodium",
            None,
            &Target {
                game_version: "26.3",
                loaders: &["fabric"],
            },
            &installed,
        )
        .await
        .unwrap();
        let ids: Vec<_> = plan.install.iter().map(|f| f.project_id.as_str()).collect();
        assert_eq!(ids, ["sodium", "fabric-api"]);
        assert_eq!(plan.install[1].required_by.as_deref(), Some("Title sodium"));
        assert_eq!(plan.optional.len(), 1);
        assert_eq!(plan.optional[0].title, "Title extras");
        assert_eq!(plan.incompatible.len(), 1);
        assert_eq!(plan.incompatible[0].project_id, "optifine");
        assert_eq!(
            *fake.calls.lock().unwrap(),
            1,
            "titles fetched in one bulk call"
        );
    }

    #[tokio::test]
    async fn skips_installed_dependencies_and_reports_unavailable() {
        let mut versions = HashMap::new();
        versions.insert(
            "a".into(),
            vec![version(
                "a",
                vec![
                    ("b", DependencyKind::Required),
                    ("c", DependencyKind::Required),
                ],
                true,
            )],
        );
        versions.insert("c".into(), vec![version("c", vec![], false)]);
        let fake = Fake {
            versions,
            calls: Mutex::new(0),
        };
        let mut installed: HashSet<(Provider, String)> = HashSet::new();
        installed.insert((Provider::Modrinth, "b".into()));
        let plan = plan(
            &fake,
            "a",
            None,
            &Target {
                game_version: "26.3",
                loaders: &["fabric"],
            },
            &installed,
        )
        .await
        .unwrap();
        assert_eq!(plan.install.len(), 1);
        assert_eq!(plan.unavailable.len(), 1);
        assert_eq!(
            plan.unavailable[0].url.as_deref(),
            Some("https://example/c")
        );
        let err = super::plan(
            &fake,
            "missing",
            None,
            &Target {
                game_version: "26.3",
                loaders: &["fabric"],
            },
            &installed,
        )
        .await
        .unwrap_err();
        assert_eq!(err.info().code, "mods.noCompatibleFile");
    }
}
