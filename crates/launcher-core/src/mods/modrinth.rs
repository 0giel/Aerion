//! Modrinth API v2 (`https://docs.modrinth.com`). Responses are cached on disk; bulk endpoints
//! are used wherever possible to stay well below the 300 requests/minute limit.

use std::collections::HashMap;

use serde::Deserialize;
use serde_json::json;

use crate::error::{Error, Result};
use crate::mods::model::*;
use crate::net::cache::{DAY, HOUR, HttpCache, MINUTE};
use crate::net::client::{Http, json};

pub const API: &str = "https://api.modrinth.com/v2";

#[derive(Clone)]
pub struct Modrinth {
    pub base: String,
    http: Http,
    cache: HttpCache,
}

#[derive(Deserialize)]
struct SearchResponse {
    hits: Vec<Hit>,
    total_hits: u64,
    offset: u32,
}

#[derive(Deserialize)]
struct Hit {
    project_id: String,
    slug: Option<String>,
    title: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    author: String,
    #[serde(default)]
    icon_url: Option<String>,
    #[serde(default)]
    downloads: u64,
    #[serde(default)]
    follows: Option<u64>,
    #[serde(default)]
    date_modified: Option<String>,
    #[serde(default)]
    display_categories: Vec<String>,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    project_type: String,
}

#[derive(Deserialize)]
struct MrProject {
    id: String,
    slug: Option<String>,
    title: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    body: String,
    #[serde(default)]
    icon_url: Option<String>,
    #[serde(default)]
    downloads: u64,
    #[serde(default)]
    followers: Option<u64>,
    #[serde(default)]
    updated: Option<String>,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    loaders: Vec<String>,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    project_type: String,
    #[serde(default)]
    license: Option<MrLicense>,
    #[serde(default)]
    gallery: Vec<MrGallery>,
    #[serde(default)]
    source_url: Option<String>,
    #[serde(default)]
    issues_url: Option<String>,
    #[serde(default)]
    wiki_url: Option<String>,
    #[serde(default)]
    discord_url: Option<String>,
    #[serde(default)]
    donation_urls: Vec<MrDonation>,
    #[serde(default)]
    client_side: Option<String>,
    #[serde(default)]
    server_side: Option<String>,
}

#[derive(Deserialize)]
struct MrLicense {
    id: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    url: Option<String>,
}

#[derive(Deserialize)]
struct MrGallery {
    url: String,
    #[serde(default)]
    raw_url: Option<String>,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    description: Option<String>,
    #[serde(default)]
    featured: bool,
}

#[derive(Deserialize)]
struct MrDonation {
    url: String,
}

#[derive(Deserialize)]
struct MrMember {
    user: MrUser,
    #[serde(default)]
    role: String,
}

#[derive(Deserialize)]
struct MrUser {
    username: String,
}

#[derive(Deserialize, Clone)]
pub(crate) struct MrVersion {
    id: String,
    project_id: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    version_number: String,
    #[serde(default)]
    version_type: String,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    loaders: Vec<String>,
    #[serde(default)]
    date_published: String,
    #[serde(default)]
    downloads: u64,
    #[serde(default)]
    files: Vec<MrFile>,
    #[serde(default)]
    dependencies: Vec<MrDependency>,
}

#[derive(Deserialize, Clone)]
struct MrFile {
    #[serde(default)]
    hashes: HashMap<String, String>,
    url: String,
    filename: String,
    #[serde(default)]
    primary: bool,
    #[serde(default)]
    size: u64,
}

#[derive(Deserialize, Clone)]
struct MrDependency {
    #[serde(default)]
    version_id: Option<String>,
    #[serde(default)]
    project_id: Option<String>,
    #[serde(default)]
    dependency_type: String,
}

#[derive(Deserialize)]
struct MrCategory {
    name: String,
    #[serde(default)]
    icon: Option<String>,
    project_type: String,
}

fn kind_of(project_type: &str) -> ContentKind {
    match project_type {
        "modpack" => ContentKind::Modpack,
        "resourcepack" => ContentKind::ResourcePack,
        "shader" => ContentKind::Shader,
        "datapack" => ContentKind::DataPack,
        _ => ContentKind::Mod,
    }
}

fn type_facet(kind: ContentKind) -> &'static str {
    match kind {
        ContentKind::Mod => "mod",
        ContentKind::Modpack => "modpack",
        ContentKind::ResourcePack => "resourcepack",
        ContentKind::Shader => "shader",
        ContentKind::DataPack => "datapack",
    }
}

fn page_url(project_type: &str, slug: &str) -> String {
    let t = if project_type.is_empty() {
        "mod"
    } else {
        project_type
    };
    format!("https://modrinth.com/{t}/{slug}")
}

const LOADER_TAGS: &[&str] = &[
    "fabric",
    "quilt",
    "forge",
    "neoforge",
    "liteloader",
    "modloader",
    "rift",
    "minecraft",
    "iris",
    "optifine",
    "canvas",
    "vanilla",
    "datapack",
];

impl From<MrVersion> for ProjectVersion {
    fn from(v: MrVersion) -> Self {
        ProjectVersion {
            provider: Provider::Modrinth,
            kind: match v.version_type.as_str() {
                "beta" => ReleaseKind::Beta,
                "alpha" => ReleaseKind::Alpha,
                _ => ReleaseKind::Release,
            },
            files: v
                .files
                .into_iter()
                .map(|f| VersionFile {
                    sha1: f.hashes.get("sha1").cloned(),
                    sha512: f.hashes.get("sha512").cloned(),
                    url: Some(f.url),
                    filename: f.filename,
                    size: f.size,
                    primary: f.primary,
                })
                .collect(),
            dependencies: v
                .dependencies
                .into_iter()
                .map(|d| Dependency {
                    project_id: d.project_id,
                    version_id: d.version_id,
                    kind: match d.dependency_type.as_str() {
                        "required" => DependencyKind::Required,
                        "incompatible" => DependencyKind::Incompatible,
                        "embedded" => DependencyKind::Embedded,
                        _ => DependencyKind::Optional,
                    },
                })
                .collect(),
            id: v.id,
            project_id: v.project_id,
            name: v.name,
            version_number: v.version_number,
            game_versions: v.game_versions,
            loaders: v.loaders,
            published: v.date_published,
            downloads: v.downloads,
            page_url: None,
        }
    }
}

fn summary_from_project(p: &MrProject, author: String) -> ProjectSummary {
    let slug = p.slug.clone().unwrap_or_else(|| p.id.clone());
    ProjectSummary {
        provider: Provider::Modrinth,
        id: p.id.clone(),
        page_url: page_url(&p.project_type, &slug),
        slug,
        title: p.title.clone(),
        description: p.description.clone(),
        author,
        icon_url: p.icon_url.clone().filter(|u| !u.is_empty()),
        downloads: p.downloads,
        follows: p.followers,
        updated: p.updated.clone(),
        categories: p
            .categories
            .iter()
            .filter(|c| !LOADER_TAGS.contains(&c.as_str()))
            .cloned()
            .collect(),
        kind: kind_of(&p.project_type),
        also_on: None,
    }
}

fn encode_list(items: &[&str]) -> String {
    serde_json::to_string(items).unwrap_or_else(|_| "[]".into())
}

impl Modrinth {
    pub fn new(http: Http, cache: HttpCache) -> Self {
        Self {
            base: API.into(),
            http,
            cache,
        }
    }

    async fn get<T: serde::de::DeserializeOwned>(
        &self,
        path_and_query: &str,
        ttl: i64,
    ) -> Result<T> {
        let url = format!("{}{}", self.base, path_and_query);
        self.cache.get_json(&url, ttl).await
    }

    async fn post<T: serde::de::DeserializeOwned>(
        &self,
        path: &str,
        body: &serde_json::Value,
    ) -> Result<T> {
        let url = format!("{}{}", self.base, path);
        let resp = self.http.send(|c| c.post(&url).json(body)).await?;
        json(resp).await
    }

    /// `hash → newest compatible version` for update checks (one request for all files).
    pub async fn latest_for_hashes(
        &self,
        sha1s: &[String],
        loaders: &[&str],
        game_version: &str,
    ) -> Result<HashMap<String, ProjectVersion>> {
        if sha1s.is_empty() {
            return Ok(HashMap::new());
        }
        let body = json!({ "hashes": sha1s, "algorithm": "sha1", "loaders": loaders, "game_versions": [game_version] });
        let map: HashMap<String, MrVersion> = self.post("/version_files/update", &body).await?;
        Ok(map.into_iter().map(|(k, v)| (k, v.into())).collect())
    }

    /// `sha1 → version` for files with known hashes (modpack indexes, local files).
    async fn versions_by_hash(&self, sha1s: &[String]) -> Result<HashMap<String, ProjectVersion>> {
        if sha1s.is_empty() {
            return Ok(HashMap::new());
        }
        let body = json!({ "hashes": sha1s, "algorithm": "sha1" });
        let map: HashMap<String, MrVersion> = self.post("/version_files", &body).await?;
        Ok(map.into_iter().map(|(k, v)| (k, v.into())).collect())
    }
}

impl ModProvider for Modrinth {
    fn provider(&self) -> Provider {
        Provider::Modrinth
    }

    async fn search(&self, q: &SearchQuery) -> Result<SearchPage> {
        let mut facets: Vec<Vec<String>> =
            vec![vec![format!("project_type:{}", type_facet(q.kind))]];
        if let Some(v) = q.game_version.as_deref().filter(|v| !v.is_empty()) {
            facets.push(vec![format!("versions:{v}")]);
        }
        if let Some(loader) = q.loader
            && matches!(q.kind, ContentKind::Mod | ContentKind::Modpack)
        {
            let loaders = crate::mods::select::compatible_loaders(
                loader,
                q.game_version.as_deref().unwrap_or(""),
            );
            if !loaders.is_empty() {
                facets.push(loaders.iter().map(|l| format!("categories:{l}")).collect());
            }
        }
        for c in &q.categories {
            facets.push(vec![format!("categories:{c}")]);
        }
        let index = match q.sort {
            SortOrder::Relevance => "relevance",
            SortOrder::Downloads => "downloads",
            SortOrder::Updated => "updated",
            SortOrder::Newest => "newest",
        };
        let facets = serde_json::to_string(&facets).unwrap_or_default();
        let mut url = reqwest::Url::parse(&format!("{}/search", self.base))
            .map_err(|e| Error::Invalid(e.to_string()))?;
        url.query_pairs_mut()
            .append_pair("query", q.query.trim())
            .append_pair("facets", &facets)
            .append_pair("index", index)
            .append_pair("offset", &q.offset.to_string())
            .append_pair("limit", &q.limit.clamp(1, 100).to_string());
        let r: SearchResponse = self.cache.get_json(url.as_str(), 5 * MINUTE).await?;
        Ok(SearchPage {
            total: r.total_hits,
            offset: r.offset,
            hits: r
                .hits
                .into_iter()
                .map(|h| {
                    let slug = h.slug.clone().unwrap_or_else(|| h.project_id.clone());
                    let cats = if h.display_categories.is_empty() {
                        h.categories
                    } else {
                        h.display_categories
                    };
                    ProjectSummary {
                        provider: Provider::Modrinth,
                        page_url: page_url(&h.project_type, &slug),
                        id: h.project_id,
                        slug,
                        title: h.title,
                        description: h.description,
                        author: h.author,
                        icon_url: h.icon_url.filter(|u| !u.is_empty()),
                        downloads: h.downloads,
                        follows: h.follows,
                        updated: h.date_modified,
                        categories: cats
                            .into_iter()
                            .filter(|c| !LOADER_TAGS.contains(&c.as_str()))
                            .collect(),
                        kind: kind_of(&h.project_type),
                        also_on: None,
                    }
                })
                .collect(),
        })
    }

    async fn project(&self, id: &str) -> Result<ProjectDetails> {
        let p: MrProject = self.get(&format!("/project/{id}"), 10 * MINUTE).await?;
        let members: Vec<MrMember> = self
            .get(&format!("/project/{}/members", p.id), HOUR)
            .await
            .unwrap_or_default();
        let author = members
            .iter()
            .find(|m| m.role.eq_ignore_ascii_case("owner"))
            .or(members.first())
            .map(|m| m.user.username.clone())
            .unwrap_or_default();
        let mut links = Vec::new();
        for (kind, url) in [
            ("source", &p.source_url),
            ("issues", &p.issues_url),
            ("wiki", &p.wiki_url),
            ("discord", &p.discord_url),
        ] {
            if let Some(u) = url.as_ref().filter(|u| !u.is_empty()) {
                links.push(ProjectLink {
                    kind: kind.into(),
                    url: u.clone(),
                });
            }
        }
        for d in &p.donation_urls {
            links.push(ProjectLink {
                kind: "donation".into(),
                url: d.url.clone(),
            });
        }
        let mut gallery: Vec<&MrGallery> = p.gallery.iter().collect();
        gallery.sort_by_key(|g| !g.featured);
        Ok(ProjectDetails {
            summary: summary_from_project(&p, author),
            body: p.body.clone(),
            body_format: BodyFormat::Markdown,
            gallery: gallery
                .into_iter()
                .map(|g| GalleryImage {
                    url: g.raw_url.clone().unwrap_or_else(|| g.url.clone()),
                    thumbnail_url: Some(g.url.clone()),
                    title: g.title.clone(),
                    description: g.description.clone(),
                })
                .collect(),
            license: p.license.as_ref().map(|l| License {
                id: l.id.clone(),
                name: if l.name.is_empty() {
                    l.id.clone()
                } else {
                    l.name.clone()
                },
                url: l.url.clone(),
            }),
            links,
            game_versions: p.game_versions.clone(),
            loaders: p.loaders.clone(),
            client_side: p.client_side.clone(),
            server_side: p.server_side.clone(),
        })
    }

    async fn projects(&self, ids: &[String]) -> Result<Vec<ProjectSummary>> {
        let mut out = Vec::new();
        for chunk in ids.chunks(100) {
            let refs: Vec<&str> = chunk.iter().map(String::as_str).collect();
            let list: Vec<MrProject> = self
                .get(
                    &format!("/projects?ids={}", urlencode(&encode_list(&refs))),
                    10 * MINUTE,
                )
                .await?;
            out.extend(list.iter().map(|p| summary_from_project(p, String::new())));
        }
        Ok(out)
    }

    async fn versions(
        &self,
        project_id: &str,
        game_version: Option<&str>,
        loaders: &[&str],
    ) -> Result<Vec<ProjectVersion>> {
        let mut query = Vec::new();
        if !loaders.is_empty() {
            query.push(format!("loaders={}", urlencode(&encode_list(loaders))));
        }
        if let Some(v) = game_version.filter(|v| !v.is_empty()) {
            query.push(format!("game_versions={}", urlencode(&encode_list(&[v]))));
        }
        let qs = if query.is_empty() {
            String::new()
        } else {
            format!("?{}", query.join("&"))
        };
        let list: Vec<MrVersion> = self
            .get(&format!("/project/{project_id}/version{qs}"), 5 * MINUTE)
            .await?;
        Ok(list.into_iter().map(Into::into).collect())
    }

    async fn versions_by_id(&self, ids: &[String]) -> Result<Vec<ProjectVersion>> {
        let mut out = Vec::new();
        for chunk in ids.chunks(100) {
            let refs: Vec<&str> = chunk.iter().map(String::as_str).collect();
            let list: Vec<MrVersion> = self
                .get(
                    &format!("/versions?ids={}", urlencode(&encode_list(&refs))),
                    HOUR,
                )
                .await?;
            out.extend(list.into_iter().map(ProjectVersion::from));
        }
        Ok(out)
    }

    async fn categories(&self, kind: ContentKind) -> Result<Vec<Category>> {
        let all: Vec<MrCategory> = self.get("/tag/category", DAY).await?;
        let t = type_facet(kind);
        let t = if t == "datapack" { "mod" } else { t };
        Ok(all
            .into_iter()
            .filter(|c| c.project_type == t && !LOADER_TAGS.contains(&c.name.as_str()))
            .map(|c| Category {
                id: c.name.clone(),
                name: c.name,
                icon: c.icon,
            })
            .collect())
    }

    async fn identify(&self, files: &[LocalFile]) -> Result<HashMap<String, Identified>> {
        let hashes: Vec<String> = files.iter().map(|f| f.sha1.clone()).collect();
        let by_hash = self.versions_by_hash(&hashes).await?;
        let project_ids: Vec<String> = {
            let mut v: Vec<String> = by_hash.values().map(|v| v.project_id.clone()).collect();
            v.sort();
            v.dedup();
            v
        };
        let projects: HashMap<String, ProjectSummary> = self
            .projects(&project_ids)
            .await?
            .into_iter()
            .map(|p| (p.id.clone(), p))
            .collect();
        let mut out = HashMap::new();
        for f in files {
            if let Some(v) = by_hash.get(&f.sha1)
                && let Some(p) = projects.get(&v.project_id)
            {
                out.insert(
                    f.key.clone(),
                    Identified {
                        project: p.clone(),
                        version: v.clone(),
                    },
                );
            }
        }
        Ok(out)
    }
}

/// Minimal percent-encoding for query values.
pub fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::loaders::LoaderKind;
    use crate::net::client::NetConfig;
    use wiremock::matchers::{method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn fixture(name: &str) -> String {
        std::fs::read_to_string(format!(
            "{}/tests/fixtures/modrinth/{name}",
            env!("CARGO_MANIFEST_DIR")
        ))
        .unwrap()
    }

    async fn client(server: &MockServer) -> (Modrinth, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        let http = Http::new(&NetConfig::default()).unwrap();
        let mut m = Modrinth::new(http.clone(), HttpCache::new(dir.path().to_path_buf(), http));
        m.base = server.uri();
        (m, dir)
    }

    #[tokio::test]
    async fn search_builds_facets_and_maps_hits() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .and(query_param("facets", r#"[["project_type:mod"],["versions:26.3"],["categories:quilt","categories:fabric"]]"#))
            .and(query_param("index", "downloads"))
            .respond_with(ResponseTemplate::new(200).set_body_string(fixture("search-sodium.json")))
            .mount(&server)
            .await;
        let (m, _d) = client(&server).await;
        let page = m
            .search(&SearchQuery {
                query: "sodium".into(),
                kind: ContentKind::Mod,
                game_version: Some("26.3".into()),
                loader: Some(LoaderKind::Quilt),
                categories: vec![],
                sort: SortOrder::Downloads,
                offset: 0,
                limit: 20,
            })
            .await
            .unwrap();
        assert_eq!(page.total, 45);
        let first = &page.hits[0];
        assert_eq!(
            (first.id.as_str(), first.title.as_str()),
            ("AANobbMI", "Sodium")
        );
        assert_eq!(first.page_url, "https://modrinth.com/mod/sodium");
        assert!(
            !first.categories.iter().any(|c| c == "fabric"),
            "loader tags are not categories"
        );
    }

    #[tokio::test]
    async fn project_and_versions() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/project/sodium"))
            .respond_with(
                ResponseTemplate::new(200).set_body_string(fixture("project-sodium.json")),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/project/AANobbMI/members"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(json!([{"user": {"username": "jellysquid3"}, "role": "Owner"}])),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/project/AANobbMI/version"))
            .and(query_param("loaders", r#"["fabric"]"#))
            .and(query_param("game_versions", r#"["26.3"]"#))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_string(fixture("versions-sodium-26.3-fabric.json")),
            )
            .mount(&server)
            .await;
        let (m, _d) = client(&server).await;
        let p = m.project("sodium").await.unwrap();
        assert_eq!(p.summary.author, "jellysquid3");
        assert_eq!(p.body_format, BodyFormat::Markdown);
        assert!(p.body.len() > 100);
        assert_eq!(p.gallery.len(), 6);
        let versions = m
            .versions("AANobbMI", Some("26.3"), &["fabric"])
            .await
            .unwrap();
        assert_eq!(versions.len(), 3);
        let file = versions[0].primary_file().unwrap();
        assert!(file.sha1.is_some() && file.sha512.is_some() && file.url.is_some());
    }

    #[test]
    fn encoding() {
        assert_eq!(urlencode(r#"["a b"]"#), "%5B%22a%20b%22%5D");
    }
}
