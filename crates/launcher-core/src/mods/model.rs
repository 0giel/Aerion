//! Provider-independent model shown by the UI.

use std::collections::HashMap;
use std::future::Future;

use serde::{Deserialize, Serialize};

use crate::error::Result;
use crate::loaders::LoaderKind;
use crate::util::hash::Hash;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "lowercase")]
pub enum Provider {
    Modrinth,
    Curseforge,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub enum ContentKind {
    Mod,
    Modpack,
    ResourcePack,
    Shader,
    DataPack,
}

impl ContentKind {
    /// Folder inside the game directory.
    pub fn folder(self) -> &'static str {
        match self {
            ContentKind::Mod | ContentKind::Modpack => "mods",
            ContentKind::ResourcePack => "resourcepacks",
            ContentKind::Shader => "shaderpacks",
            ContentKind::DataPack => "datapacks",
        }
    }

    pub const INSTALLABLE: [ContentKind; 4] = [
        ContentKind::Mod,
        ContentKind::ResourcePack,
        ContentKind::Shader,
        ContentKind::DataPack,
    ];
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub enum SortOrder {
    #[default]
    Relevance,
    Downloads,
    Updated,
    Newest,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct SearchQuery {
    pub query: String,
    pub kind: ContentKind,
    pub game_version: Option<String>,
    pub loader: Option<LoaderKind>,
    pub categories: Vec<String>,
    pub sort: SortOrder,
    pub offset: u32,
    pub limit: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ProjectRef {
    pub provider: Provider,
    pub id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ProjectSummary {
    pub provider: Provider,
    pub id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub author: String,
    pub icon_url: Option<String>,
    pub downloads: u64,
    pub follows: Option<u64>,
    pub updated: Option<String>,
    pub categories: Vec<String>,
    pub kind: ContentKind,
    /// Same project on the other platform (filled when searching both).
    pub also_on: Option<ProjectRef>,
    pub page_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct SearchPage {
    pub hits: Vec<ProjectSummary>,
    pub total: u64,
    pub offset: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "lowercase")]
pub enum BodyFormat {
    Markdown,
    Html,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct GalleryImage {
    pub url: String,
    pub thumbnail_url: Option<String>,
    pub title: Option<String>,
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct License {
    pub id: String,
    pub name: String,
    pub url: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ProjectLink {
    /// `source`, `issues`, `wiki`, `discord`, `website`, `donation`.
    pub kind: String,
    pub url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ProjectDetails {
    pub summary: ProjectSummary,
    /// Untrusted: the UI sanitises it (DOMPurify) before rendering.
    pub body: String,
    pub body_format: BodyFormat,
    pub gallery: Vec<GalleryImage>,
    pub license: Option<License>,
    pub links: Vec<ProjectLink>,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub client_side: Option<String>,
    pub server_side: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "lowercase")]
pub enum ReleaseKind {
    Release,
    Beta,
    Alpha,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct VersionFile {
    /// `None` when the author disabled third-party downloads (CurseForge).
    pub url: Option<String>,
    pub filename: String,
    pub size: u64,
    pub sha1: Option<String>,
    pub sha512: Option<String>,
    pub primary: bool,
}

impl VersionFile {
    /// The strongest hash the platform gave.
    pub fn hash(&self) -> Option<Hash> {
        self.sha512
            .clone()
            .map(Hash::Sha512)
            .or_else(|| self.sha1.clone().map(Hash::Sha1))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "lowercase")]
pub enum DependencyKind {
    Required,
    Optional,
    Incompatible,
    Embedded,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct Dependency {
    pub project_id: Option<String>,
    pub version_id: Option<String>,
    pub kind: DependencyKind,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ProjectVersion {
    pub provider: Provider,
    pub id: String,
    pub project_id: String,
    pub name: String,
    pub version_number: String,
    pub kind: ReleaseKind,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub published: String,
    pub downloads: u64,
    pub files: Vec<VersionFile>,
    pub dependencies: Vec<Dependency>,
    /// Page where a manual download is possible (CurseForge files without `downloadUrl`).
    pub page_url: Option<String>,
}

impl ProjectVersion {
    pub fn primary_file(&self) -> Option<&VersionFile> {
        self.files.iter().find(|f| f.primary).or(self.files.first())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct Category {
    pub id: String,
    pub name: String,
    /// Inline SVG (Modrinth) or icon URL (CurseForge).
    pub icon: Option<String>,
}

/// A local file identified on a platform.
#[derive(Debug, Clone)]
pub struct Identified {
    pub project: ProjectSummary,
    pub version: ProjectVersion,
}

/// One mod platform. Implemented by [`super::modrinth::Modrinth`] and
/// [`super::curseforge::CurseForge`]; the UI only sees the unified types above.
pub trait ModProvider: Send + Sync {
    fn provider(&self) -> Provider;
    fn search(&self, q: &SearchQuery) -> impl Future<Output = Result<SearchPage>> + Send;
    fn project(&self, id: &str) -> impl Future<Output = Result<ProjectDetails>> + Send;
    fn projects(&self, ids: &[String]) -> impl Future<Output = Result<Vec<ProjectSummary>>> + Send;
    fn versions(
        &self,
        project_id: &str,
        game_version: Option<&str>,
        loaders: &[&str],
    ) -> impl Future<Output = Result<Vec<ProjectVersion>>> + Send;
    fn versions_by_id(
        &self,
        ids: &[String],
    ) -> impl Future<Output = Result<Vec<ProjectVersion>>> + Send;
    fn categories(&self, kind: ContentKind) -> impl Future<Output = Result<Vec<Category>>> + Send;
    /// Identify local files: `sha1 → match` (Modrinth) or `fingerprint → match` (CurseForge),
    /// keyed by the caller-supplied file key.
    fn identify(
        &self,
        files: &[LocalFile],
    ) -> impl Future<Output = Result<HashMap<String, Identified>>> + Send;
}

#[derive(Debug, Clone)]
pub struct LocalFile {
    pub key: String,
    pub sha1: String,
    pub fingerprint: u32,
}
