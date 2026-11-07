//! Parallel, resumable, verified downloads.
//!
//! * bounded concurrency (setting),
//! * `.part` files resumed with `Range` requests,
//! * SHA-1/256/512 + size verification before the atomic rename into place,
//! * retries with exponential backoff, rotating through mirror URLs,
//! * cooperative cancellation through the task's [`CancellationToken`](tokio_util::sync::CancellationToken).

use std::collections::HashSet;
use std::path::{Path, PathBuf};

use futures_util::{StreamExt, stream};
use reqwest::StatusCode;
use reqwest::header::RANGE;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::error::{Error, IoContext, Result};
use crate::net::client::{Http, backoff, check, redact_url};
use crate::progress::TaskCtx;
use crate::util::hash::{Hash, verify_file};

const MAX_ATTEMPTS: u32 = 5;

#[derive(Debug, Clone)]
pub struct DownloadItem {
    /// Primary URL first, then mirrors.
    pub urls: Vec<String>,
    pub dest: PathBuf,
    pub hash: Option<Hash>,
    pub size: Option<u64>,
    pub executable: bool,
}

impl DownloadItem {
    pub fn new(url: impl Into<String>, dest: impl Into<PathBuf>) -> Self {
        Self {
            urls: vec![url.into()],
            dest: dest.into(),
            hash: None,
            size: None,
            executable: false,
        }
    }

    pub fn sha1(mut self, sha1: Option<&str>) -> Self {
        if let Some(h) = sha1.filter(|h| !h.is_empty()) {
            self.hash = Some(Hash::Sha1(h.to_owned()));
        }
        self
    }

    pub fn hash(mut self, hash: Option<Hash>) -> Self {
        if hash.is_some() {
            self.hash = hash;
        }
        self
    }

    pub fn size(mut self, size: Option<u64>) -> Self {
        self.size = size.filter(|s| *s > 0);
        self
    }

    pub fn mirror(mut self, url: impl Into<String>) -> Self {
        self.urls.push(url.into());
        self
    }

    pub fn executable(mut self, yes: bool) -> Self {
        self.executable = yes;
        self
    }
}

/// How hard to look at files that already exist.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VerifyMode {
    /// Exists and has the expected size: the fast path before every launch.
    Fast,
    /// Recompute hashes of existing files ("Verify files" / after a failure).
    Full,
}

#[derive(Clone, Debug)]
pub struct Downloader {
    http: Http,
    concurrency: usize,
}

impl Downloader {
    pub fn new(http: Http, concurrency: usize) -> Self {
        Self {
            http,
            concurrency: concurrency.clamp(1, 64),
        }
    }

    pub fn http(&self) -> &Http {
        &self.http
    }

    /// Whether `item` must be (re)downloaded.
    async fn is_needed(item: &DownloadItem, mode: VerifyMode) -> bool {
        let Ok(meta) = tokio::fs::metadata(&item.dest).await else {
            return true;
        };
        if !meta.is_file() {
            return true;
        }
        if let Some(size) = item.size
            && meta.len() != size
        {
            return true;
        }
        match (mode, &item.hash) {
            (VerifyMode::Full, Some(hash)) => !verify_file(&item.dest, hash).await.unwrap_or(false),
            _ => false,
        }
    }

    /// Download everything that is missing or invalid. Duplicate destinations are merged
    /// (asset indexes reference the same object from several paths).
    pub async fn run(
        &self,
        items: Vec<DownloadItem>,
        mode: VerifyMode,
        ctx: &TaskCtx,
    ) -> Result<()> {
        let mut seen = HashSet::new();
        let items: Vec<_> = items
            .into_iter()
            .filter(|i| seen.insert(i.dest.clone()))
            .collect();

        let checks = if mode == VerifyMode::Full { 8 } else { 64 };
        let needed: Vec<DownloadItem> = stream::iter(items)
            .map(|item| async move { Self::is_needed(&item, mode).await.then_some(item) })
            .buffer_unordered(checks)
            .filter_map(|x| async move { x })
            .collect()
            .await;
        ctx.check()?;
        if needed.is_empty() {
            return Ok(());
        }
        let bytes: u64 = needed.iter().filter_map(|i| i.size).sum();
        ctx.progress.add_total(needed.len() as u64, bytes);

        let inner = ctx.child();
        let results: Vec<Result<()>> = stream::iter(needed)
            .map(|item| {
                let inner = inner.clone();
                async move {
                    let r = self.fetch(&item, &inner).await;
                    match &r {
                        Ok(()) => inner.progress.item_done(),
                        // Stop the rest of the batch on the first real failure.
                        Err(e) if !matches!(e, Error::Cancelled) => inner.cancel.cancel(),
                        Err(_) => {}
                    }
                    r
                }
            })
            .buffer_unordered(self.concurrency)
            .collect()
            .await;

        let mut cancelled = false;
        for r in results {
            match r {
                Err(Error::Cancelled) => cancelled = true,
                Err(e) => return Err(e),
                Ok(()) => {}
            }
        }
        if cancelled || ctx.cancel.is_cancelled() {
            return Err(Error::Cancelled);
        }
        Ok(())
    }

    /// Download a single item (with retries).
    pub async fn fetch(&self, item: &DownloadItem, ctx: &TaskCtx) -> Result<()> {
        if item.urls.is_empty() {
            return Err(Error::Invalid(format!(
                "no URL for {}",
                item.dest.display()
            )));
        }
        let mut reported = 0u64;
        let mut attempt = 0u32;
        loop {
            ctx.check()?;
            let url = &item.urls[attempt as usize % item.urls.len()];
            match self.try_fetch(url, item, ctx, &mut reported).await {
                Ok(()) => return Ok(()),
                Err(Error::Cancelled) => return Err(Error::Cancelled),
                Err(e) if attempt + 1 < MAX_ATTEMPTS && retryable(&e, item.urls.len()) => {
                    attempt += 1;
                    tracing::debug!(url = %redact_url(url), error = %e, attempt, "download retry");
                    tokio::select! {
                        _ = ctx.cancel.cancelled() => return Err(Error::Cancelled),
                        _ = tokio::time::sleep(backoff(attempt)) => {}
                    }
                }
                Err(e) => {
                    ctx.progress.sub_bytes(reported);
                    return Err(e);
                }
            }
        }
    }

    async fn try_fetch(
        &self,
        url: &str,
        item: &DownloadItem,
        ctx: &TaskCtx,
        reported: &mut u64,
    ) -> Result<()> {
        let dest = &item.dest;
        let parent = dest
            .parent()
            .ok_or_else(|| Error::Invalid(format!("bad path {}", dest.display())))?;
        tokio::fs::create_dir_all(parent).await.at(parent)?;
        let part = part_path(dest);

        let mut existing = tokio::fs::metadata(&part)
            .await
            .map(|m| m.len())
            .unwrap_or(0);
        if item.size.is_some_and(|s| existing >= s) {
            let _ = tokio::fs::remove_file(&part).await;
            existing = 0;
        }

        let mut req = self.http.client().get(url);
        if existing > 0 {
            req = req.header(RANGE, format!("bytes={existing}-"));
        }
        let resp = req.send().await?;
        let status = resp.status();
        if status == StatusCode::RANGE_NOT_SATISFIABLE {
            let _ = tokio::fs::remove_file(&part).await;
            return Err(Error::Http {
                status: 416,
                url: redact_url(url),
                body: None,
            });
        }
        let resp = check(resp).await?;
        let resumed = status == StatusCode::PARTIAL_CONTENT && existing > 0;

        let mut hasher = item.hash.as_ref().map(Hash::hasher);
        let mut file;
        let mut written;
        if resumed {
            if let Some(h) = hasher.as_mut() {
                hash_prefix(&part, h).await?;
            }
            file = tokio::fs::OpenOptions::new()
                .append(true)
                .open(&part)
                .await
                .at(&part)?;
            written = existing;
        } else {
            file = tokio::fs::File::create(&part).await.at(&part)?;
            written = 0;
        }
        // Keep the progress counter in sync with what is actually on disk for this item.
        if written >= *reported {
            ctx.progress.add_bytes(written - *reported);
        } else {
            ctx.progress.sub_bytes(*reported - written);
        }
        *reported = written;

        let mut writer = tokio::io::BufWriter::with_capacity(256 * 1024, &mut file);
        let mut body = resp.bytes_stream();
        loop {
            let chunk = tokio::select! {
                _ = ctx.cancel.cancelled() => return Err(Error::Cancelled),
                c = body.next() => c,
            };
            let Some(chunk) = chunk else { break };
            let chunk = chunk?;
            writer.write_all(&chunk).await.at(&part)?;
            if let Some(h) = hasher.as_mut() {
                h.update(&chunk);
            }
            written += chunk.len() as u64;
            *reported += chunk.len() as u64;
            ctx.progress.add_bytes(chunk.len() as u64);
        }
        writer.flush().await.at(&part)?;
        drop(writer);
        file.sync_all().await.at(&part)?;
        drop(file);

        if let Some(size) = item.size
            && written != size
        {
            let _ = tokio::fs::remove_file(&part).await;
            return Err(Error::HashMismatch {
                path: dest.clone(),
                expected: format!("{size} bytes"),
                actual: format!("{written} bytes"),
            });
        }
        if let (Some(h), Some(expected)) = (hasher, &item.hash) {
            let actual = h.finish_hex();
            if !actual.eq_ignore_ascii_case(expected.expected()) {
                let _ = tokio::fs::remove_file(&part).await;
                return Err(Error::HashMismatch {
                    path: dest.clone(),
                    expected: expected.expected().to_owned(),
                    actual,
                });
            }
        }
        tokio::fs::rename(&part, dest).await.at(dest)?;
        #[cfg(unix)]
        if item.executable {
            use std::os::unix::fs::PermissionsExt;
            let _ = tokio::fs::set_permissions(dest, std::fs::Permissions::from_mode(0o755)).await;
        }
        Ok(())
    }
}

fn part_path(dest: &Path) -> PathBuf {
    let mut name = dest
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_default();
    name.push(".part");
    dest.with_file_name(name)
}

async fn hash_prefix(path: &Path, hasher: &mut crate::util::hash::Hasher) -> Result<()> {
    let mut f = tokio::fs::File::open(path).await.at(path)?;
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        let n = f.read(&mut buf).await.at(path)?;
        if n == 0 {
            return Ok(());
        }
        hasher.update(&buf[..n]);
    }
}

fn retryable(e: &Error, mirrors: usize) -> bool {
    match e {
        Error::Network(_) | Error::HashMismatch { .. } => true,
        Error::Http { status, .. } => {
            *status == 408 || *status == 416 || *status == 429 || *status >= 500 || mirrors > 1
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::net::client::NetConfig;
    use crate::util::hash::sha1_hex;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn downloader() -> Downloader {
        Downloader::new(Http::new(&NetConfig::default()).unwrap(), 4)
    }

    #[tokio::test]
    async fn downloads_verifies_and_skips_existing() {
        let server = MockServer::start().await;
        let body = b"hello minecraft".to_vec();
        Mock::given(method("GET"))
            .and(path("/f.jar"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(body.clone()))
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().unwrap();
        let dest = dir.path().join("C Users Łukasz").join("f.jar");
        let item = DownloadItem::new(format!("{}/f.jar", server.uri()), &dest)
            .sha1(Some(&sha1_hex(&body)))
            .size(Some(body.len() as u64));
        let ctx = TaskCtx::new();
        let d = downloader();
        d.run(vec![item.clone(), item.clone()], VerifyMode::Fast, &ctx)
            .await
            .unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), body);
        let snap = ctx.progress.snapshot();
        assert_eq!((snap.done_items, snap.done_bytes), (1, body.len() as u64));
        d.run(vec![item], VerifyMode::Full, &ctx).await.unwrap();
        assert_eq!(
            server.received_requests().await.unwrap().len(),
            1,
            "valid file is not fetched again"
        );
    }

    #[tokio::test]
    async fn rejects_bad_hash() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(b"tampered".to_vec()))
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().unwrap();
        let dest = dir.path().join("x.jar");
        let item = DownloadItem::new(format!("{}/x.jar", server.uri()), &dest)
            .sha1(Some(&sha1_hex(b"original")));
        let err = downloader()
            .run(vec![item], VerifyMode::Fast, &TaskCtx::new())
            .await
            .unwrap_err();
        assert!(matches!(err, Error::HashMismatch { .. }), "{err:?}");
        assert!(!dest.exists());
    }

    #[tokio::test]
    async fn resumes_partial_download() {
        let server = MockServer::start().await;
        let body: Vec<u8> = (0..100u8).collect();
        Mock::given(method("GET"))
            .and(wiremock::matchers::header("range", "bytes=40-"))
            .respond_with(ResponseTemplate::new(206).set_body_bytes(body[40..].to_vec()))
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().unwrap();
        let dest = dir.path().join("big.bin");
        std::fs::write(part_path(&dest), &body[..40]).unwrap();
        let item = DownloadItem::new(format!("{}/big.bin", server.uri()), &dest)
            .sha1(Some(&sha1_hex(&body)))
            .size(Some(100));
        downloader()
            .run(vec![item], VerifyMode::Fast, &TaskCtx::new())
            .await
            .unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), body);
    }

    #[tokio::test]
    async fn falls_back_to_mirror() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/a"))
            .respond_with(ResponseTemplate::new(404))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/b"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(b"ok".to_vec()))
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().unwrap();
        let dest = dir.path().join("m.jar");
        let item = DownloadItem::new(format!("{}/a", server.uri()), &dest)
            .mirror(format!("{}/b", server.uri()));
        downloader()
            .run(vec![item], VerifyMode::Fast, &TaskCtx::new())
            .await
            .unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), b"ok");
    }

    #[tokio::test]
    async fn cancellation_stops_downloads() {
        let ctx = TaskCtx::new();
        ctx.cancel.cancel();
        let dir = tempfile::tempdir().unwrap();
        let item = DownloadItem::new("http://127.0.0.1:9/never", dir.path().join("n"));
        let err = downloader()
            .run(vec![item], VerifyMode::Fast, &ctx)
            .await
            .unwrap_err();
        assert!(matches!(err, Error::Cancelled));
    }
}
