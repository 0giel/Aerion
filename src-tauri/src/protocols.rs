//! Custom URI schemes.
//!
//! * `img://localhost/<base64url(url)>[?nc=1]` — image proxy with a disk cache. The CSP allows
//!   no remote origins, so thumbnails, galleries and images inside mod descriptions all come
//!   through here. `nc=1` disables caching (CurseForge content, per their API terms).
//! * `themeasset://localhost/<name>` — files from `<root>/themes/assets` (backgrounds), with
//!   `Range` support for video.

use std::net::IpAddr;

use base64::Engine as _;
use launcher_core::util::hash::sha1_hex;
use tauri::http::{Request, Response, StatusCode, header};
use tauri::{AppHandle, Manager, Runtime, UriSchemeContext, UriSchemeResponder};

use crate::state::AppState;
use crate::themes::{assets_dir, content_type, valid_asset};

const MAX_IMAGE: usize = 15 * 1024 * 1024;

fn empty(status: StatusCode) -> Response<Vec<u8>> {
    Response::builder().status(status).body(Vec::new()).unwrap_or_default()
}

pub fn img<R: Runtime>(ctx: UriSchemeContext<'_, R>, request: Request<Vec<u8>>, responder: UriSchemeResponder) {
    let app = ctx.app_handle().clone();
    tauri::async_runtime::spawn(async move {
        let resp = match proxy(&app, &request).await {
            Ok(r) => r,
            Err(status) => empty(status),
        };
        responder.respond(resp);
    });
}

fn is_private(host: &str) -> bool {
    if host.eq_ignore_ascii_case("localhost") || host.ends_with(".localhost") || host.ends_with(".local") {
        return true;
    }
    match host.trim_matches(['[', ']']).parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => ip.is_private() || ip.is_loopback() || ip.is_link_local() || ip.is_unspecified(),
        Ok(IpAddr::V6(ip)) => ip.is_loopback() || ip.is_unspecified() || (ip.segments()[0] & 0xfe00) == 0xfc00,
        Err(_) => false,
    }
}

async fn proxy<R: Runtime>(app: &AppHandle<R>, request: &Request<Vec<u8>>) -> Result<Response<Vec<u8>>, StatusCode> {
    let uri = request.uri();
    let encoded = uri.path().trim_start_matches('/');
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(encoded).map_err(|_| StatusCode::BAD_REQUEST)?;
    let url = String::from_utf8(bytes).map_err(|_| StatusCode::BAD_REQUEST)?;
    let parsed = reqwest::Url::parse(&url).map_err(|_| StatusCode::BAD_REQUEST)?;
    if !matches!(parsed.scheme(), "https" | "http") || parsed.host_str().is_none_or(is_private) {
        return Err(StatusCode::FORBIDDEN);
    }
    let no_cache = uri.query().is_some_and(|q| q.split('&').any(|p| p == "nc=1"));
    let state = app.state::<AppState>();
    let cache_dir = state.paths.image_cache_dir();
    let key = sha1_hex(url.as_bytes());
    let (body_path, type_path) = (cache_dir.join(&key), cache_dir.join(format!("{key}.type")));
    if !no_cache
        && let (Ok(body), Ok(ct)) = (tokio::fs::read(&body_path).await, tokio::fs::read_to_string(&type_path).await)
    {
        return Ok(image_response(body, &ct));
    }
    let engine = state.engine().await;
    let resp = engine.http.client().get(parsed).send().await.map_err(|_| StatusCode::BAD_GATEWAY)?;
    if !resp.status().is_success() {
        return Err(StatusCode::NOT_FOUND);
    }
    let ct = resp.headers().get(header::CONTENT_TYPE).and_then(|v| v.to_str().ok()).unwrap_or("").to_ascii_lowercase();
    if !ct.starts_with("image/") || resp.content_length().is_some_and(|l| l as usize > MAX_IMAGE) {
        return Err(StatusCode::UNSUPPORTED_MEDIA_TYPE);
    }
    let body = resp.bytes().await.map_err(|_| StatusCode::BAD_GATEWAY)?;
    if body.len() > MAX_IMAGE {
        return Err(StatusCode::PAYLOAD_TOO_LARGE);
    }
    if !no_cache {
        let _ = tokio::fs::create_dir_all(&cache_dir).await;
        let _ = launcher_core::util::fs::atomic_write(&body_path, &body).await;
        let _ = tokio::fs::write(&type_path, &ct).await;
    }
    Ok(image_response(body.to_vec(), &ct))
}

fn image_response(body: Vec<u8>, content_type: &str) -> Response<Vec<u8>> {
    let mut b = Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CACHE_CONTROL, "max-age=604800")
        .header("X-Content-Type-Options", "nosniff");
    if content_type.contains("svg") {
        b = b.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
    }
    b.body(body).unwrap_or_default()
}

pub fn theme_asset<R: Runtime>(ctx: UriSchemeContext<'_, R>, request: Request<Vec<u8>>, responder: UriSchemeResponder) {
    let app = ctx.app_handle().clone();
    tauri::async_runtime::spawn(async move {
        let name = request.uri().path().trim_start_matches('/').to_owned();
        let name = percent_decode(&name);
        if !valid_asset(&name) {
            return responder.respond(empty(StatusCode::BAD_REQUEST));
        }
        let path = assets_dir(&app.state::<AppState>().paths.themes_dir()).join(&name);
        let Ok(data) = tokio::fs::read(&path).await else {
            return responder.respond(empty(StatusCode::NOT_FOUND));
        };
        let ct = content_type(&name);
        let total = data.len();
        let range = request
            .headers()
            .get(header::RANGE)
            .and_then(|v| v.to_str().ok())
            .and_then(|r| r.strip_prefix("bytes="))
            .and_then(|r| r.split_once('-'))
            .and_then(|(s, e)| {
                let start: usize = s.parse().ok()?;
                let end: usize = if e.is_empty() { total.saturating_sub(1) } else { e.parse().ok()? };
                (start <= end && end < total).then_some((start, end))
            });
        let resp = match range {
            Some((start, end)) => Response::builder()
                .status(StatusCode::PARTIAL_CONTENT)
                .header(header::CONTENT_TYPE, ct)
                .header(header::ACCEPT_RANGES, "bytes")
                .header(header::CONTENT_RANGE, format!("bytes {start}-{end}/{total}"))
                .body(data[start..=end].to_vec()),
            None => Response::builder().status(StatusCode::OK).header(header::CONTENT_TYPE, ct).header(header::ACCEPT_RANGES, "bytes").body(data),
        };
        responder.respond(resp.unwrap_or_default());
    });
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let Ok(v) = u8::from_str_radix(&s[i + 1..i + 3], 16)
        {
            out.push(v);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Keep the thumbnail cache under ~300 MB (oldest files go first).
pub async fn trim_image_cache(dir: std::path::PathBuf) {
    let Ok(mut rd) = tokio::fs::read_dir(&dir).await else { return };
    let mut files = Vec::new();
    let mut total = 0u64;
    while let Ok(Some(e)) = rd.next_entry().await {
        if let Ok(m) = e.metadata().await {
            total += m.len();
            files.push((m.modified().ok(), m.len(), e.path()));
        }
    }
    if total < 300 * 1024 * 1024 {
        return;
    }
    files.sort_by_key(|f| f.0);
    for (_, len, path) in files {
        if total < 200 * 1024 * 1024 {
            break;
        }
        if tokio::fs::remove_file(&path).await.is_ok() {
            total -= len;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blocks_private_hosts() {
        for h in ["localhost", "127.0.0.1", "10.0.0.5", "192.168.1.1", "[::1]", "printer.local", "169.254.1.1"] {
            assert!(is_private(h), "{h}");
        }
        for h in ["cdn.modrinth.com", "media.forgecdn.net", "8.8.8.8"] {
            assert!(!is_private(h), "{h}");
        }
    }

    #[test]
    fn decodes_percent() {
        assert_eq!(percent_decode("bg-a%2Eb.png"), "bg-a.b.png");
    }
}
