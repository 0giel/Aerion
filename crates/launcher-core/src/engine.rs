//! The engine: shared services built from paths + settings.

use crate::error::Result;
use crate::java::runtime::RuntimeManager;
use crate::mojang::Meta;
use crate::mojang::rules::Environment;
use crate::net::cache::HttpCache;
use crate::net::client::{Http, NetConfig};
use crate::net::download::Downloader;
use crate::paths::Paths;
use crate::settings::Settings;
use crate::util::sys::{current_arch, current_os};

/// Cheap to rebuild when network settings change.
#[derive(Clone)]
pub struct Engine {
    pub paths: Paths,
    pub http: Http,
    pub cache: HttpCache,
    pub downloader: Downloader,
    pub meta: Meta,
    pub env: Environment,
}

impl Engine {
    pub fn new(paths: Paths, settings: &Settings) -> Result<Self> {
        let http = Http::new(&NetConfig {
            proxy: settings.proxy.clone(),
        })?;
        let cache = HttpCache::new(paths.http_cache_dir(), http.clone());
        let downloader = Downloader::new(http.clone(), settings.download_concurrency as usize);
        let meta = Meta {
            paths: paths.clone(),
            cache: cache.clone(),
            downloader: downloader.clone(),
        };
        Ok(Self {
            paths,
            http,
            cache,
            downloader,
            meta,
            env: Environment::current(),
        })
    }

    pub fn runtimes(&self) -> RuntimeManager<'_> {
        RuntimeManager {
            paths: &self.paths,
            cache: &self.cache,
            downloader: &self.downloader,
            os: current_os(),
            arch: current_arch(),
        }
    }
}
