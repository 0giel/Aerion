//! CurseForge Core API v1 with the user's own API key.
//!
//! The CurseForge 3rd-Party API Terms (§3.1) forbid saving or caching API data, so this client
//! never uses the disk cache; only identifiers of files the user installs are kept (in the
//! instance's `content.json`) so updates can be checked. Files whose author disabled third-party
//! distribution (`downloadUrl == null`) are never fetched from elsewhere — the UI links to the
//! file page instead.

use std::collections::HashMap;

use serde::Deserialize;
use serde::de::DeserializeOwned;
use serde_json::json;

use crate::error::{Error, Result};
use crate::mods::model::*;
use crate::net::client::{Http, json};

pub const API: &str = "https://api.curseforge.com/v1";
pub const MINECRAFT: u32 = 432;

#[derive(Clone)]
pub struct CurseForge {
    pub base: String,
    http: Http,
    key: Option<String>,
}

#[derive(Deserialize)]
struct Envelope<T> {
    data: T,
    #[serde(default)]
    pagination: Option<Pagination>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Pagination {
    #[serde(default)]
    total_count: u64,
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct CfMod {
    id: u64,
    name: String,
    #[serde(default)]
    slug: String,
    #[serde(default)]
    summary: String,
    #[serde(default)]
    download_count: f64,
    #[serde(default)]
    links: CfLinks,
    #[serde(default)]
    logo: Option<CfAsset>,
    #[serde(default)]
    screenshots: Vec<CfAsset>,
    #[serde(default)]
    authors: Vec<CfAuthor>,
    #[serde(default)]
    categories: Vec<CfCategory>,
    #[serde(default)]
    class_id: Option<u32>,
    #[serde(default)]
    date_modified: Option<String>,
    #[serde(default)]
    thumbs_up_count: Option<u64>,
}

#[derive(Deserialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
struct CfLinks {
    #[serde(default)]
    website_url: Option<String>,
    #[serde(default)]
    wiki_url: Option<String>,
    #[serde(default)]
    issues_url: Option<String>,
    #[serde(default)]
    source_url: Option<String>,
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct CfAsset {
    #[serde(default)]
    url: String,
    #[serde(default)]
    thumbnail_url: Option<String>,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    description: Option<String>,
}

#[derive(Deserialize, Clone)]
struct CfAuthor {
    name: String,
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct CfCategory {
    id: u64,
    name: String,
    #[serde(default)]
    slug: String,
    #[serde(default)]
    icon_url: Option<String>,
    #[serde(default)]
    class_id: Option<u32>,
    #[serde(default)]
    is_class: Option<bool>,
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CfFile {
    id: u64,
    mod_id: u64,
    #[serde(default)]
    display_name: String,
    file_name: String,
    #[serde(default)]
    release_type: u8,
    #[serde(default)]
    file_date: String,
    #[serde(default)]
    file_length: u64,
    #[serde(default)]
    download_count: u64,
    #[serde(default)]
    download_url: Option<String>,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    hashes: Vec<CfHash>,
    #[serde(default)]
    dependencies: Vec<CfDependency>,
}

#[derive(Deserialize, Clone)]
struct CfHash {
    value: String,
    algo: u8,
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct CfDependency {
    mod_id: u64,
    relation_type: u8,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct FingerprintResult {
    #[serde(default)]
    exact_matches: Vec<FingerprintMatch>,
}

#[derive(Deserialize)]
struct FingerprintMatch {
    file: CfFile,
}

pub fn class_id(kind: ContentKind) -> u32 {
    match kind {
        ContentKind::Mod => 6,
        ContentKind::Modpack => 4471,
        ContentKind::ResourcePack => 12,
        ContentKind::Shader => 6552,
        ContentKind::DataPack => 6945,
    }
}

fn kind_of(class: Option<u32>) -> ContentKind {
    match class {
        Some(4471) => ContentKind::Modpack,
        Some(12) => ContentKind::ResourcePack,
        Some(6552) => ContentKind::Shader,
        Some(6945) => ContentKind::DataPack,
        _ => ContentKind::Mod,
    }
}

pub fn loader_type(loader: &str) -> Option<u8> {
    match loader {
        "forge" => Some(1),
        "fabric" => Some(4),
        "quilt" => Some(5),
        "neoforge" => Some(6),
        _ => None,
    }
}

const LOADER_NAMES: &[(&str, &str)] = &[
    ("forge", "forge"),
    ("fabric", "fabric"),
    ("quilt", "quilt"),
    ("neoforge", "neoforge"),
];

fn page_of(m: &CfMod) -> String {
    m.links
        .website_url
        .clone()
        .unwrap_or_else(|| format!("https://www.curseforge.com/minecraft/mc-mods/{}", m.slug))
}

fn summary(m: &CfMod) -> ProjectSummary {
    ProjectSummary {
        provider: Provider::Curseforge,
        id: m.id.to_string(),
        slug: m.slug.clone(),
        title: m.name.clone(),
        description: m.summary.clone(),
        author: m
            .authors
            .first()
            .map(|a| a.name.clone())
            .unwrap_or_default(),
        icon_url: m
            .logo
            .as_ref()
            .map(|l| l.thumbnail_url.clone().unwrap_or_else(|| l.url.clone()))
            .filter(|u| !u.is_empty()),
        downloads: m.download_count.max(0.0) as u64,
        follows: m.thumbs_up_count,
        updated: m.date_modified.clone(),
        categories: m
            .categories
            .iter()
            .map(|c| c.slug.clone())
            .filter(|s| !s.is_empty())
            .collect(),
        kind: kind_of(m.class_id),
        also_on: None,
        page_url: page_of(m),
    }
}

fn to_version(f: CfFile, page: Option<&str>) -> ProjectVersion {
    let mut game_versions = Vec::new();
    let mut loaders = Vec::new();
    for gv in &f.game_versions {
        let lower = gv.to_ascii_lowercase();
        if let Some((_, l)) = LOADER_NAMES.iter().find(|(name, _)| *name == lower) {
            loaders.push((*l).to_owned());
        } else if gv.starts_with(|c: char| c.is_ascii_digit()) {
            game_versions.push(gv.clone());
        }
    }
    let sha1 = f
        .hashes
        .iter()
        .find(|h| h.algo == 1)
        .map(|h| h.value.clone());
    ProjectVersion {
        provider: Provider::Curseforge,
        id: f.id.to_string(),
        project_id: f.mod_id.to_string(),
        name: if f.display_name.is_empty() {
            f.file_name.clone()
        } else {
            f.display_name.clone()
        },
        version_number: f.display_name.clone(),
        kind: match f.release_type {
            2 => ReleaseKind::Beta,
            3 => ReleaseKind::Alpha,
            _ => ReleaseKind::Release,
        },
        game_versions,
        loaders,
        published: f.file_date.clone(),
        downloads: f.download_count,
        files: vec![VersionFile {
            url: f.download_url.clone().filter(|u| !u.is_empty()),
            filename: f.file_name.clone(),
            size: f.file_length,
            sha1,
            sha512: None,
            primary: true,
        }],
        dependencies: f
            .dependencies
            .iter()
            .filter_map(|d| {
                let kind = match d.relation_type {
                    3 => DependencyKind::Required,
                    2 => DependencyKind::Optional,
                    5 => DependencyKind::Incompatible,
                    1 | 6 => DependencyKind::Embedded,
                    _ => return None,
                };
                Some(Dependency {
                    project_id: Some(d.mod_id.to_string()),
                    version_id: None,
                    kind,
                })
            })
            .collect(),
        page_url: page.map(|p| format!("{p}/files/{}", f.id)),
    }
}

impl CurseForge {
    pub fn new(http: Http, key: Option<String>) -> Self {
        Self {
            base: API.into(),
            http,
            key: key.filter(|k| !k.trim().is_empty()),
        }
    }

    pub fn has_key(&self) -> bool {
        self.key.is_some()
    }

    fn key(&self) -> Result<&str> {
        self.key.as_deref().ok_or(Error::CurseForgeKeyMissing)
    }

    async fn get<T: DeserializeOwned>(&self, path_and_query: &str) -> Result<Envelope<T>> {
        let key = self.key()?;
        let url = format!("{}{}", self.base, path_and_query);
        let resp = self
            .http
            .send(|c| {
                c.get(&url)
                    .header("x-api-key", key)
                    .header("Accept", "application/json")
            })
            .await?;
        json(resp).await
    }

    async fn post<T: DeserializeOwned>(
        &self,
        path: &str,
        body: &serde_json::Value,
    ) -> Result<Envelope<T>> {
        let key = self.key()?;
        let url = format!("{}{}", self.base, path);
        let resp = self
            .http
            .send(|c| c.post(&url).header("x-api-key", key).json(body))
            .await?;
        json(resp).await
    }

    async fn mods_by_id(&self, ids: &[u64]) -> Result<Vec<CfMod>> {
        if ids.is_empty() {
            return Ok(vec![]);
        }
        Ok(self
            .post::<Vec<CfMod>>("/mods", &json!({ "modIds": ids, "filterPcOnly": true }))
            .await?
            .data)
    }

    /// Bulk file lookup (modpack manifests list `projectID` + `fileID`).
    pub async fn files_by_id(&self, ids: &[u64]) -> Result<Vec<ProjectVersion>> {
        if ids.is_empty() {
            return Ok(vec![]);
        }
        let files = self
            .post::<Vec<CfFile>>("/mods/files", &json!({ "fileIds": ids }))
            .await?
            .data;
        let mod_ids: Vec<u64> = files.iter().map(|f| f.mod_id).collect();
        let pages: HashMap<u64, String> = self
            .mods_by_id(&mod_ids)
            .await?
            .iter()
            .map(|m| (m.id, page_of(m)))
            .collect();
        Ok(files
            .into_iter()
            .map(|f| {
                let page = pages.get(&f.mod_id).cloned();
                to_version(f, page.as_deref())
            })
            .collect())
    }

    /// Validate a key with a cheap request.
    pub async fn test_key(&self) -> Result<()> {
        self.get::<serde_json::Value>(&format!("/games/{MINECRAFT}"))
            .await
            .map(|_| ())
    }
}

fn parse_id(id: &str) -> Result<u64> {
    id.parse()
        .map_err(|_| Error::Invalid(format!("invalid CurseForge id {id}")))
}

impl ModProvider for CurseForge {
    fn provider(&self) -> Provider {
        Provider::Curseforge
    }

    async fn search(&self, q: &SearchQuery) -> Result<SearchPage> {
        let mut url = reqwest::Url::parse(&format!("{}/mods/search", self.base))
            .map_err(|e| Error::Invalid(e.to_string()))?;
        {
            let mut p = url.query_pairs_mut();
            p.append_pair("gameId", &MINECRAFT.to_string())
                .append_pair("classId", &class_id(q.kind).to_string())
                .append_pair("searchFilter", q.query.trim())
                .append_pair("sortOrder", "desc")
                .append_pair("index", &q.offset.min(9_950).to_string())
                .append_pair("pageSize", &q.limit.clamp(1, 50).to_string())
                .append_pair(
                    "sortField",
                    match q.sort {
                        SortOrder::Relevance => "2",
                        SortOrder::Downloads => "6",
                        SortOrder::Updated => "3",
                        SortOrder::Newest => "11",
                    },
                );
            if let Some(v) = q.game_version.as_deref().filter(|v| !v.is_empty()) {
                p.append_pair("gameVersion", v);
                if let Some(l) = q.loader
                    && matches!(q.kind, ContentKind::Mod | ContentKind::Modpack)
                {
                    // Quilt runs Fabric mods, which are rarely tagged Quilt on CurseForge.
                    let name = if l == crate::instances::LoaderKind::Quilt {
                        "fabric"
                    } else {
                        l.as_str()
                    };
                    if let Some(t) = loader_type(name) {
                        p.append_pair("modLoaderType", &t.to_string());
                    }
                }
            }
            if let Some(c) = q.categories.first() {
                p.append_pair("categoryId", c);
            }
        }
        let path = url.as_str().trim_start_matches(&self.base).to_owned();
        let env = self.get::<Vec<CfMod>>(&path).await?;
        Ok(SearchPage {
            total: env
                .pagination
                .map(|p| p.total_count)
                .unwrap_or(0)
                .min(10_000),
            offset: q.offset,
            hits: env.data.iter().map(summary).collect(),
        })
    }

    async fn project(&self, id: &str) -> Result<ProjectDetails> {
        let mid = parse_id(id)?;
        let m = self.get::<CfMod>(&format!("/mods/{mid}")).await?.data;
        let body = self
            .get::<String>(&format!("/mods/{mid}/description"))
            .await
            .map(|e| e.data)
            .unwrap_or_default();
        let mut links = Vec::new();
        for (kind, url) in [
            ("website", &m.links.website_url),
            ("source", &m.links.source_url),
            ("issues", &m.links.issues_url),
            ("wiki", &m.links.wiki_url),
        ] {
            if let Some(u) = url.as_ref().filter(|u| !u.is_empty()) {
                links.push(ProjectLink {
                    kind: kind.into(),
                    url: u.clone(),
                });
            }
        }
        Ok(ProjectDetails {
            summary: summary(&m),
            body,
            body_format: BodyFormat::Html,
            gallery: m
                .screenshots
                .iter()
                .map(|s| GalleryImage {
                    url: s.url.clone(),
                    thumbnail_url: s.thumbnail_url.clone(),
                    title: s.title.clone(),
                    description: s.description.clone(),
                })
                .collect(),
            license: None,
            links,
            game_versions: vec![],
            loaders: vec![],
            client_side: None,
            server_side: None,
        })
    }

    async fn projects(&self, ids: &[String]) -> Result<Vec<ProjectSummary>> {
        let ids: Vec<u64> = ids.iter().filter_map(|i| i.parse().ok()).collect();
        Ok(self.mods_by_id(&ids).await?.iter().map(summary).collect())
    }

    async fn versions(
        &self,
        project_id: &str,
        game_version: Option<&str>,
        loaders: &[&str],
    ) -> Result<Vec<ProjectVersion>> {
        let mid = parse_id(project_id)?;
        let page = self
            .get::<CfMod>(&format!("/mods/{mid}"))
            .await
            .ok()
            .map(|e| page_of(&e.data));
        let mut path = format!("/mods/{mid}/files?pageSize=50");
        if let Some(v) = game_version.filter(|v| !v.is_empty()) {
            path.push_str(&format!(
                "&gameVersion={}",
                crate::mods::modrinth::urlencode(v)
            ));
        }
        let files = self.get::<Vec<CfFile>>(&path).await?.data;
        Ok(files
            .into_iter()
            .map(|f| to_version(f, page.as_deref()))
            .filter(|v| {
                loaders.is_empty()
                    || v.loaders.is_empty()
                    || v.loaders.iter().any(|l| loaders.contains(&l.as_str()))
            })
            .collect())
    }

    async fn versions_by_id(&self, ids: &[String]) -> Result<Vec<ProjectVersion>> {
        let ids: Vec<u64> = ids.iter().filter_map(|i| i.parse().ok()).collect();
        self.files_by_id(&ids).await
    }

    async fn categories(&self, kind: ContentKind) -> Result<Vec<Category>> {
        let list = self
            .get::<Vec<CfCategory>>(&format!(
                "/categories?gameId={MINECRAFT}&classId={}",
                class_id(kind)
            ))
            .await?
            .data;
        Ok(list
            .into_iter()
            .filter(|c| c.is_class != Some(true) && c.class_id == Some(class_id(kind)))
            .map(|c| Category {
                id: c.id.to_string(),
                name: c.name,
                icon: c.icon_url,
            })
            .collect())
    }

    async fn identify(&self, files: &[LocalFile]) -> Result<HashMap<String, Identified>> {
        if files.is_empty() {
            return Ok(HashMap::new());
        }
        let prints: Vec<u32> = files.iter().map(|f| f.fingerprint).collect();
        let result = self
            .post::<FingerprintResult>(
                &format!("/fingerprints/{MINECRAFT}"),
                &json!({ "fingerprints": prints }),
            )
            .await?
            .data;
        let mod_ids: Vec<u64> = result.exact_matches.iter().map(|m| m.file.mod_id).collect();
        let mods: HashMap<u64, CfMod> = self
            .mods_by_id(&mod_ids)
            .await?
            .into_iter()
            .map(|m| (m.id, m))
            .collect();
        let mut out = HashMap::new();
        for m in result.exact_matches {
            let file_name = m.file.file_name.clone();
            let Some(cfmod) = mods.get(&m.file.mod_id) else {
                continue;
            };
            let page = page_of(cfmod);
            let version = to_version(m.file, Some(&page));
            // Match back by file name (the fingerprint response doesn't echo our keys).
            if let Some(local) = files.iter().find(|f| {
                f.key.ends_with(&file_name)
                    || f.key.trim_end_matches(".disabled").ends_with(&file_name)
            }) {
                out.insert(
                    local.key.clone(),
                    Identified {
                        project: summary(cfmod),
                        version,
                    },
                );
            }
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::net::client::NetConfig;
    use wiremock::matchers::{header, method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn file_json(download: Option<&str>) -> serde_json::Value {
        json!({
            "id": 5000, "modId": 238222, "displayName": "jei-1.20.1-forge-15.2.0.27.jar", "fileName": "jei-1.20.1-forge-15.2.0.27.jar",
            "releaseType": 1, "fileDate": "2024-01-01T00:00:00Z", "fileLength": 1234, "downloadCount": 10,
            "downloadUrl": download, "gameVersions": ["1.20.1", "Forge", "NeoForge", "Client", "Java 17"],
            "hashes": [{"value": "abc", "algo": 1}, {"value": "def", "algo": 2}],
            "dependencies": [{"modId": 1, "relationType": 3}, {"modId": 2, "relationType": 2}, {"modId": 3, "relationType": 6}]
        })
    }

    fn mod_json() -> serde_json::Value {
        json!({"id": 238222, "name": "Just Enough Items (JEI)", "slug": "jei", "summary": "View items", "downloadCount": 3.0e8,
               "links": {"websiteUrl": "https://www.curseforge.com/minecraft/mc-mods/jei"}, "logo": {"url": "https://media.forgecdn.net/x.png", "thumbnailUrl": "https://media.forgecdn.net/t.png"},
               "authors": [{"name": "mezz"}], "categories": [{"id": 1, "name": "API", "slug": "api"}], "classId": 6})
    }

    #[tokio::test]
    async fn requires_key() {
        let cf = CurseForge::new(Http::new(&NetConfig::default()).unwrap(), None);
        let err = cf.project("1").await.unwrap_err();
        assert_eq!(err.info().code, "curseforge.noKey");
    }

    #[tokio::test]
    async fn search_and_files_map_correctly() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/mods/search"))
            .and(header("x-api-key", "k"))
            .and(query_param("gameId", "432"))
            .and(query_param("classId", "6"))
            .and(query_param("modLoaderType", "4"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"data": [mod_json()], "pagination": {"index": 0, "pageSize": 20, "resultCount": 1, "totalCount": 1}})))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/mods/238222"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"data": mod_json()})))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/mods/238222/files"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"data": [file_json(None), file_json(Some("https://edge.forgecdn.net/files/5/0/jei.jar"))]})))
            .mount(&server)
            .await;
        let mut cf = CurseForge::new(Http::new(&NetConfig::default()).unwrap(), Some("k".into()));
        cf.base = server.uri();
        let page = cf
            .search(&SearchQuery {
                query: "jei".into(),
                kind: ContentKind::Mod,
                game_version: Some("1.21.11".into()),
                loader: Some(crate::instances::LoaderKind::Quilt),
                categories: vec![],
                sort: SortOrder::Relevance,
                offset: 0,
                limit: 20,
            })
            .await
            .unwrap();
        assert_eq!(page.hits[0].title, "Just Enough Items (JEI)");
        assert_eq!(page.hits[0].downloads, 300_000_000);
        assert_eq!(
            page.hits[0].icon_url.as_deref(),
            Some("https://media.forgecdn.net/t.png")
        );

        let versions = cf
            .versions("238222", Some("1.20.1"), &["neoforge"])
            .await
            .unwrap();
        assert_eq!(versions.len(), 2);
        let v = &versions[0];
        assert_eq!(v.game_versions, ["1.20.1"]);
        assert_eq!(v.loaders, ["forge", "neoforge"]);
        assert!(v.files[0].url.is_none(), "distribution disabled");
        assert_eq!(
            v.page_url.as_deref(),
            Some("https://www.curseforge.com/minecraft/mc-mods/jei/files/5000")
        );
        let kinds: Vec<_> = v.dependencies.iter().map(|d| d.kind).collect();
        assert_eq!(
            kinds,
            [
                DependencyKind::Required,
                DependencyKind::Optional,
                DependencyKind::Embedded
            ]
        );
        assert_eq!(versions[1].files[0].sha1.as_deref(), Some("abc"));
    }
}
