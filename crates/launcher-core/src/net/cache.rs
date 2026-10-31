//! On-disk HTTP cache for JSON metadata (Mojang, loaders, Modrinth).
//!
//! Fresh entries are served without a request; stale ones are revalidated with
//! `If-None-Match` / `If-Modified-Since`; if the network is down a stale copy is returned,
//! which keeps the launcher usable offline. CurseForge responses never go through here
//! (their API terms forbid caching).

use std::path::PathBuf;

use reqwest::StatusCode;
use reqwest::header::{ETAG, IF_MODIFIED_SINCE, IF_NONE_MATCH, LAST_MODIFIED};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};
use crate::net::client::{Http, redact_url};
use crate::util::fs::{atomic_write, read_json, write_json};
use crate::util::hash::sha1_hex;
use crate::util::now_secs;

#[derive(Clone, Debug)]
pub struct HttpCache {
    dir: PathBuf,
    http: Http,
}

#[derive(Serialize, Deserialize)]
struct Meta {
    url: String,
    etag: Option<String>,
    last_modified: Option<String>,
    fetched: i64,
}

pub const MINUTE: i64 = 60;
pub const HOUR: i64 = 3600;
pub const DAY: i64 = 86_400;

impl HttpCache {
    pub fn new(dir: PathBuf, http: Http) -> Self {
        Self { dir, http }
    }

    pub fn http(&self) -> &Http {
        &self.http
    }

    fn paths(&self, url: &str) -> (PathBuf, PathBuf) {
        let key = sha1_hex(url.as_bytes());
        let sub = self.dir.join(&key[..2]);
        (
            sub.join(format!("{key}.body")),
            sub.join(format!("{key}.meta.json")),
        )
    }

    /// Fetch `url`, using the cache if the entry is younger than `ttl` seconds.
    pub async fn get(&self, url: &str, ttl: i64) -> Result<Vec<u8>> {
        let (body_path, meta_path) = self.paths(url);
        let meta: Option<Meta> = read_json(&meta_path).await.unwrap_or(None);
        let cached = match &meta {
            Some(_) => tokio::fs::read(&body_path).await.ok(),
            None => None,
        };
        if let (Some(m), Some(body)) = (&meta, &cached)
            && now_secs() - m.fetched < ttl
        {
            return Ok(body.clone());
        }

        let etag = meta
            .as_ref()
            .and_then(|m| m.etag.clone())
            .filter(|_| cached.is_some());
        let modified = meta
            .as_ref()
            .and_then(|m| m.last_modified.clone())
            .filter(|_| cached.is_some());
        let result = self
            .http
            .send(|c| {
                let mut req = c.get(url);
                if let Some(e) = &etag {
                    req = req.header(IF_NONE_MATCH, e);
                }
                if let Some(m) = &modified {
                    req = req.header(IF_MODIFIED_SINCE, m);
                }
                req
            })
            .await;

        match result {
            Ok(resp) if resp.status() == StatusCode::NOT_MODIFIED && cached.is_some() => {
                if let Some(mut m) = meta {
                    m.fetched = now_secs();
                    let _ = write_json(&meta_path, &m).await;
                }
                Ok(cached.unwrap_or_default())
            }
            Ok(resp) if resp.status().is_success() => {
                let header = |name| {
                    resp.headers()
                        .get(name)
                        .and_then(|v| v.to_str().ok())
                        .map(str::to_owned)
                };
                let new_meta = Meta {
                    url: redact_url(url),
                    etag: header(ETAG),
                    last_modified: header(LAST_MODIFIED),
                    fetched: now_secs(),
                };
                let body = resp.bytes().await?.to_vec();
                atomic_write(&body_path, &body).await?;
                write_json(&meta_path, &new_meta).await?;
                Ok(body)
            }
            Ok(resp) => match cached {
                Some(body) => {
                    tracing::warn!(status = %resp.status(), url = %redact_url(url), "serving stale cache");
                    Ok(body)
                }
                None => Err(crate::net::client::check(resp)
                    .await
                    .err()
                    .unwrap_or(Error::Http {
                        status: 0,
                        url: redact_url(url),
                        body: None,
                    })),
            },
            Err(e) => match cached {
                Some(body) => {
                    tracing::warn!(error = %e, url = %redact_url(url), "offline: serving stale cache");
                    Ok(body)
                }
                None => Err(e),
            },
        }
    }

    pub async fn get_json<T: DeserializeOwned>(&self, url: &str, ttl: i64) -> Result<T> {
        let bytes = self.get(url, ttl).await?;
        serde_json::from_slice(&bytes).map_err(|e| Error::parse(redact_url(url), e))
    }

    /// Drop a cached entry (e.g. after a parse error, to force a refetch).
    pub async fn invalidate(&self, url: &str) {
        let (b, m) = self.paths(url);
        let _ = tokio::fs::remove_file(b).await;
        let _ = tokio::fs::remove_file(m).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::net::client::NetConfig;
    use wiremock::matchers::{header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[tokio::test]
    async fn caches_revalidates_and_serves_stale_offline() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/m.json"))
            .and(header("if-none-match", "\"v1\""))
            .respond_with(ResponseTemplate::new(304))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/m.json"))
            .respond_with(
                ResponseTemplate::new(200)
                    .insert_header("etag", "\"v1\"")
                    .set_body_string("{\"a\":1}"),
            )
            .mount(&server)
            .await;

        let dir = tempfile::tempdir().unwrap();
        let cache = HttpCache::new(
            dir.path().to_path_buf(),
            Http::new(&NetConfig::default()).unwrap(),
        );
        let url = format!("{}/m.json", server.uri());

        let v: serde_json::Value = cache.get_json(&url, HOUR).await.unwrap();
        assert_eq!(v["a"], 1);
        // TTL 0 forces revalidation, answered with 304.
        let v: serde_json::Value = cache.get_json(&url, 0).await.unwrap();
        assert_eq!(v["a"], 1);
        assert_eq!(server.received_requests().await.unwrap().len(), 2);

        drop(server);
        let v: serde_json::Value = cache.get_json(&url, 0).await.unwrap();
        assert_eq!(v["a"], 1, "stale copy is served when offline");
    }
}
