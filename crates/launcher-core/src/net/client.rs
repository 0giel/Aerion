//! Shared HTTP client: rustls (ring) + OS certificate store, HTTP/2, compression,
//! a unique User-Agent and retries with backoff for transient failures.

use std::sync::Once;
use std::time::Duration;

use reqwest::header::{HeaderMap, RETRY_AFTER};
use reqwest::{Client, RequestBuilder, Response, StatusCode};
use serde::de::DeserializeOwned;

use crate::error::{Error, Result};

static TLS_INIT: Once = Once::new();

/// `<author>/aerion/<version> (<contact>)`, as required by Modrinth. Author and contact are
/// baked in at build time from `AERION_UA_AUTHOR` / `AERION_UA_CONTACT`.
pub fn user_agent() -> String {
    let author = option_env!("AERION_UA_AUTHOR")
        .filter(|s| !s.is_empty())
        .unwrap_or("aerion-dev");
    let contact = option_env!("AERION_UA_CONTACT")
        .filter(|s| !s.is_empty())
        .unwrap_or("contact not configured");
    format!("{author}/aerion/{} ({contact})", env!("CARGO_PKG_VERSION"))
}

#[derive(Debug, Clone, Default)]
pub struct NetConfig {
    /// Explicit proxy URL (`http://`, `https://` or `socks5://`); `None` = system settings.
    pub proxy: Option<String>,
}

#[derive(Clone, Debug)]
pub struct Http {
    client: Client,
}

const MAX_ATTEMPTS: u32 = 4;

impl Http {
    pub fn new(cfg: &NetConfig) -> Result<Self> {
        TLS_INIT.call_once(|| {
            let _ = rustls::crypto::ring::default_provider().install_default();
        });
        let mut builder = Client::builder()
            .user_agent(user_agent())
            .connect_timeout(Duration::from_secs(15))
            .read_timeout(Duration::from_secs(60))
            .pool_idle_timeout(Duration::from_secs(60))
            .tcp_keepalive(Duration::from_secs(30))
            .http2_adaptive_window(true);
        if let Some(proxy) = cfg.proxy.as_deref().filter(|p| !p.trim().is_empty()) {
            let p = reqwest::Proxy::all(proxy.trim())
                .map_err(|e| Error::Invalid(format!("invalid proxy URL: {e}")))?;
            builder = builder.proxy(p);
        }
        Ok(Self {
            client: builder.build()?,
        })
    }

    pub fn client(&self) -> &Client {
        &self.client
    }

    /// Send a request built by `build`, retrying connection errors, timeouts, 429 and 5xx.
    pub async fn send(&self, build: impl Fn(&Client) -> RequestBuilder) -> Result<Response> {
        let mut attempt = 0;
        loop {
            attempt += 1;
            let last = attempt >= MAX_ATTEMPTS;
            match build(&self.client).send().await {
                Ok(resp) => {
                    let status = resp.status();
                    let retry = status == StatusCode::TOO_MANY_REQUESTS || status.is_server_error();
                    if retry && !last {
                        let wait = retry_after(resp.headers()).unwrap_or_else(|| backoff(attempt));
                        tracing::debug!(%status, url = %resp.url(), ?wait, "retrying request");
                        tokio::time::sleep(wait).await;
                        continue;
                    }
                    return Ok(resp);
                }
                Err(e) if !last && (e.is_connect() || e.is_timeout() || e.is_request()) => {
                    tracing::debug!(error = %e, "retrying request after network error");
                    tokio::time::sleep(backoff(attempt)).await;
                }
                Err(e) => return Err(e.into()),
            }
        }
    }

    pub async fn get_json<T: DeserializeOwned>(&self, url: &str) -> Result<T> {
        let resp = self.send(|c| c.get(url)).await?;
        json(resp).await
    }

    pub async fn get_bytes(&self, url: &str) -> Result<Vec<u8>> {
        let resp = check(self.send(|c| c.get(url)).await?).await?;
        Ok(resp.bytes().await?.to_vec())
    }
}

/// Turn non-2xx responses into [`Error::Http`] (with a short body excerpt for diagnostics).
pub async fn check(resp: Response) -> Result<Response> {
    if resp.status().is_success() {
        return Ok(resp);
    }
    let status = resp.status().as_u16();
    let url = redact_url(resp.url().as_str());
    let body = resp
        .text()
        .await
        .ok()
        .map(|b| b.chars().take(2000).collect());
    Err(Error::Http { status, url, body })
}

pub async fn json<T: DeserializeOwned>(resp: Response) -> Result<T> {
    let resp = check(resp).await?;
    let url = redact_url(resp.url().as_str());
    let bytes = resp.bytes().await?;
    serde_json::from_slice(&bytes).map_err(|e| Error::parse(url, e))
}

/// Drop query strings from URLs before they reach logs or the UI.
pub fn redact_url(url: &str) -> String {
    url.split('?').next().unwrap_or(url).to_owned()
}

fn retry_after(headers: &HeaderMap) -> Option<Duration> {
    let secs: u64 = headers
        .get(RETRY_AFTER)?
        .to_str()
        .ok()?
        .trim()
        .parse()
        .ok()?;
    Some(Duration::from_secs(secs.min(60)))
}

/// Exponential backoff with jitter: ~0.5 s, 1 s, 2 s, 4 s (+ up to 250 ms).
pub fn backoff(attempt: u32) -> Duration {
    let base = 500u64 * 2u64.pow(attempt.saturating_sub(1).min(6));
    let mut b = [0u8; 2];
    let jitter = getrandom::fill(&mut b)
        .map(|_| u64::from(u16::from_le_bytes(b)) % 250)
        .unwrap_or(0);
    Duration::from_millis(base + jitter)
}
