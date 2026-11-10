//! Custom URI schemes.
//!
//! * `img://localhost/<base64url(url)>[?nc=1]` — image proxy with a disk cache. The CSP allows
//!   no remote origins, so thumbnails, galleries and images inside mod descriptions all come
//!   through here. `nc=1` disables caching (CurseForge content, per their API terms).
//! * `themeasset://localhost/<name>` — files from `<root>/themes/assets` (backgrounds), with
//!   `Range` support for video.
//! * `localimg://localhost/instance/<id>/<icon file>` and `localimg://localhost/skin/<id>` —
//!   custom instance icons and skins from the library. File names are unique per change, so
//!   responses are cached forever.
//! * `localimg://localhost/mc/<item|block>/<name>` — a Minecraft texture, read from a client jar
//!   downloaded from Mojang (see `game::textures`).

use std::net::IpAddr;

use base64::Engine as _;
use launcher_core::util::hash::sha1_hex;
use tauri::http::{Request, Response, StatusCode, header};
use tauri::{AppHandle, Manager, Runtime, UriSchemeContext, UriSchemeResponder};

use crate::state::AppState;
use crate::themes::{content_type, valid_asset};

const MAX_IMAGE: usize = 15 * 1024 * 1024;

fn empty(status: StatusCode) -> Response<Vec<u8>> {
    Response::builder()
        .status(status)
        .body(Vec::new())
        .unwrap_or_default()
}

pub fn img<R: Runtime>(
    ctx: UriSchemeContext<'_, R>,
    request: Request<Vec<u8>>,
    responder: UriSchemeResponder,
) {
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
    if host.eq_ignore_ascii_case("localhost")
        || host.ends_with(".localhost")
        || host.ends_with(".local")
    {
        return true;
    }
    match host.trim_matches(['[', ']']).parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => {
            ip.is_private() || ip.is_loopback() || ip.is_link_local() || ip.is_unspecified()
        }
        Ok(IpAddr::V6(ip)) => {
            ip.is_loopback() || ip.is_unspecified() || (ip.segments()[0] & 0xfe00) == 0xfc00
        }
        Err(_) => false,
    }
}

async fn proxy<R: Runtime>(
    app: &AppHandle<R>,
    request: &Request<Vec<u8>>,
) -> Result<Response<Vec<u8>>, StatusCode> {
    let uri = request.uri();
    let encoded = uri.path().trim_start_matches('/');
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(encoded)
        .map_err(|_| StatusCode::BAD_REQUEST)?;
    let url = String::from_utf8(bytes).map_err(|_| StatusCode::BAD_REQUEST)?;
    let parsed = reqwest::Url::parse(&url).map_err(|_| StatusCode::BAD_REQUEST)?;
    if !matches!(parsed.scheme(), "https" | "http") || parsed.host_str().is_none_or(is_private) {
        return Err(StatusCode::FORBIDDEN);
    }
    let no_cache = uri
        .query()
        .is_some_and(|q| q.split('&').any(|p| p == "nc=1"));
    let state = app.state::<AppState>();
    let cache_dir = state.paths.image_cache_dir();
    let key = sha1_hex(url.as_bytes());
    let (body_path, type_path) = (cache_dir.join(&key), cache_dir.join(format!("{key}.type")));
    if !no_cache
        && let (Ok(body), Ok(ct)) = (
            tokio::fs::read(&body_path).await,
            tokio::fs::read_to_string(&type_path).await,
        )
    {
        return Ok(image_response(body, &ct));
    }
    let engine = state.engine().await;
    let resp = engine
        .http
        .client()
        .get(parsed)
        .send()
        .await
        .map_err(|_| StatusCode::BAD_GATEWAY)?;
    if !resp.status().is_success() {
        return Err(StatusCode::NOT_FOUND);
    }
    let ct = resp
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase();
    // Some hosts send images untyped (newer capes on textures.minecraft.net come as
    // `application/octet-stream`); those are accepted only when the bytes are a raster image.
    let generic = ct.is_empty() || ct.starts_with("application/octet-stream");
    if !(ct.starts_with("image/") || generic)
        || resp
            .content_length()
            .is_some_and(|l| l as usize > MAX_IMAGE)
    {
        return Err(StatusCode::UNSUPPORTED_MEDIA_TYPE);
    }
    let body = resp.bytes().await.map_err(|_| StatusCode::BAD_GATEWAY)?;
    if body.len() > MAX_IMAGE {
        return Err(StatusCode::PAYLOAD_TOO_LARGE);
    }
    let ct = if generic {
        sniff_image(&body)
            .ok_or(StatusCode::UNSUPPORTED_MEDIA_TYPE)?
            .to_owned()
    } else {
        ct
    };
    if !no_cache {
        let _ = tokio::fs::create_dir_all(&cache_dir).await;
        let _ = launcher_core::util::fs::atomic_write(&body_path, &body).await;
        let _ = tokio::fs::write(&type_path, &ct).await;
    }
    Ok(image_response(body.to_vec(), &ct))
}

/// The type of a raster image recognised by its magic bytes (never SVG: it can carry script).
fn sniff_image(body: &[u8]) -> Option<&'static str> {
    if body.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some("image/png")
    } else if body.starts_with(b"\xff\xd8\xff") {
        Some("image/jpeg")
    } else if body.starts_with(b"GIF87a") || body.starts_with(b"GIF89a") {
        Some("image/gif")
    } else if body.len() >= 12 && body.starts_with(b"RIFF") && &body[8..12] == b"WEBP" {
        Some("image/webp")
    } else {
        None
    }
}

fn image_response(body: Vec<u8>, content_type: &str) -> Response<Vec<u8>> {
    let mut b = Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CACHE_CONTROL, "max-age=604800")
        .header("X-Content-Type-Options", "nosniff");
    if content_type.contains("svg") {
        b = b.header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'",
        );
    }
    b.body(body).unwrap_or_default()
}

pub fn theme_asset<R: Runtime>(
    ctx: UriSchemeContext<'_, R>,
    request: Request<Vec<u8>>,
    responder: UriSchemeResponder,
) {
    let app = ctx.app_handle().clone();
    tauri::async_runtime::spawn(async move {
        let name = request.uri().path().trim_start_matches('/').to_owned();
        let name = percent_decode(&name);
        if !valid_asset(&name) {
            return responder.respond(empty(StatusCode::BAD_REQUEST));
        }
        let path = app.state::<AppState>().paths.theme_assets_dir().join(&name);
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
                let end: usize = if e.is_empty() {
                    total.saturating_sub(1)
                } else {
                    e.parse().ok()?
                };
                (start <= end && end < total).then_some((start, end))
            });
        let resp = match range {
            Some((start, end)) => Response::builder()
                .status(StatusCode::PARTIAL_CONTENT)
                .header(header::CONTENT_TYPE, ct)
                .header(header::ACCEPT_RANGES, "bytes")
                .header(
                    header::CONTENT_RANGE,
                    format!("bytes {start}-{end}/{total}"),
                )
                .body(data[start..=end].to_vec()),
            None => Response::builder()
                .status(StatusCode::OK)
                .header(header::CONTENT_TYPE, ct)
                .header(header::ACCEPT_RANGES, "bytes")
                .body(data),
        };
        responder.respond(resp.unwrap_or_default());
    });
}

/// A request path of the `localimg` scheme.
#[derive(Debug, PartialEq)]
enum LocalImg {
    InstanceIcon { id: String, file: String },
    Screenshot { id: String, name: String },
    WorldIcon { id: String, folder: String },
    Skin(String),
    Texture(String),
}

fn parse_local_img(path: &str) -> Option<LocalImg> {
    let mut parts = path.trim_start_matches('/').split('/');
    let img = match (parts.next()?, parts.next()?, parts.next()) {
        ("instance", id, Some(kind @ ("screenshots" | "world"))) => {
            let (id, item) = (percent_decode(id), percent_decode(parts.next()?));
            if kind == "world" {
                LocalImg::WorldIcon { id, folder: item }
            } else {
                LocalImg::Screenshot { id, name: item }
            }
        }
        ("instance", id, Some(file)) => LocalImg::InstanceIcon {
            id: percent_decode(id),
            file: percent_decode(file),
        },
        ("skin", id, None) => LocalImg::Skin(percent_decode(id)),
        ("mc", kind, Some(name)) => LocalImg::Texture(format!("{kind}/{name}")),
        _ => return None,
    };
    parts.next().is_none().then_some(img)
}

pub fn local_img<R: Runtime>(
    ctx: UriSchemeContext<'_, R>,
    request: Request<Vec<u8>>,
    responder: UriSchemeResponder,
) {
    let app = ctx.app_handle().clone();
    tauri::async_runtime::spawn(async move {
        let state = app.state::<AppState>();
        let file = match parse_local_img(request.uri().path()) {
            Some(LocalImg::InstanceIcon { id, file }) => state.instances.icon_file(&id, &file),
            Some(LocalImg::Skin(id)) => state.skins.file(&id),
            Some(LocalImg::Screenshot { id, name }) => state
                .instances
                .game_dir(&id)
                .and_then(|g| launcher_core::instances::screenshots::path(&g, &name)),
            Some(LocalImg::WorldIcon { id, folder }) => {
                if folder.contains(['/', '\\']) || folder.starts_with('.') {
                    return responder.respond(empty(StatusCode::BAD_REQUEST));
                }
                state
                    .instances
                    .game_dir(&id)
                    .map(|g| g.join("saves").join(&folder).join("icon.png"))
            }
            Some(LocalImg::Texture(name)) => {
                let engine = state.engine.read().await;
                return match state.textures.get(&engine, &name).await {
                    Ok(data) => responder.respond(
                        Response::builder()
                            .status(StatusCode::OK)
                            .header(header::CONTENT_TYPE, "image/png")
                            .header(header::CACHE_CONTROL, "max-age=604800")
                            .header("X-Content-Type-Options", "nosniff")
                            .body(data)
                            .unwrap_or_default(),
                    ),
                    Err(e) => {
                        tracing::debug!(%name, error = %e, "texture unavailable");
                        responder.respond(empty(StatusCode::NOT_FOUND))
                    }
                };
            }
            None => return responder.respond(empty(StatusCode::BAD_REQUEST)),
        };
        let Ok(path) = file else {
            return responder.respond(empty(StatusCode::BAD_REQUEST));
        };
        let Ok(data) = tokio::fs::read(&path).await else {
            return responder.respond(empty(StatusCode::NOT_FOUND));
        };
        let Some(kind) = launcher_core::util::image::ImageKind::sniff(&data) else {
            return responder.respond(empty(StatusCode::UNSUPPORTED_MEDIA_TYPE));
        };
        let resp = Response::builder()
            .status(StatusCode::OK)
            .header(header::CONTENT_TYPE, kind.content_type())
            .header(header::CACHE_CONTROL, "max-age=31536000, immutable")
            .header("X-Content-Type-Options", "nosniff")
            .body(data);
        responder.respond(resp.unwrap_or_default());
    });
}

pub(crate) fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let Some(v) = std::str::from_utf8(&bytes[i + 1..i + 3])
                .ok()
                .and_then(|h| u8::from_str_radix(h, 16).ok())
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
    let Ok(mut rd) = tokio::fs::read_dir(&dir).await else {
        return;
    };
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
    fn sniffs_untyped_images() {
        assert_eq!(sniff_image(b"\x89PNG\r\n\x1a\n...."), Some("image/png"));
        assert_eq!(sniff_image(b"\xff\xd8\xff\xe0"), Some("image/jpeg"));
        assert_eq!(sniff_image(b"GIF89a.."), Some("image/gif"));
        assert_eq!(sniff_image(b"RIFF\0\0\0\0WEBPVP8 "), Some("image/webp"));
        assert_eq!(
            sniff_image(b"<svg xmlns=\"http://www.w3.org/2000/svg\"/>"),
            None
        );
        assert_eq!(sniff_image(b"<html>"), None);
    }

    #[test]
    fn blocks_private_hosts() {
        for h in [
            "localhost",
            "127.0.0.1",
            "10.0.0.5",
            "192.168.1.1",
            "[::1]",
            "printer.local",
            "169.254.1.1",
        ] {
            assert!(is_private(h), "{h}");
        }
        for h in ["cdn.modrinth.com", "media.forgecdn.net", "8.8.8.8"] {
            assert!(!is_private(h), "{h}");
        }
    }

    #[test]
    fn percent_decode_never_panics() {
        assert_eq!(percent_decode("%aż"), "%aż");
        assert_eq!(percent_decode("a%2"), "a%2");
    }

    #[test]
    fn local_img_paths() {
        assert_eq!(
            parse_local_img("/instance/M%C3%B3j%20%C5%9Bwiat/icon-1.png"),
            Some(LocalImg::InstanceIcon {
                id: "Mój świat".into(),
                file: "icon-1.png".into()
            })
        );
        assert_eq!(
            parse_local_img("/skin/0123abcd"),
            Some(LocalImg::Skin("0123abcd".into()))
        );
        assert_eq!(
            parse_local_img("/mc/item/diamond"),
            Some(LocalImg::Texture("item/diamond".into()))
        );
        assert_eq!(
            parse_local_img("/instance/A/screenshots/shot%201.png"),
            Some(LocalImg::Screenshot {
                id: "A".into(),
                name: "shot 1.png".into()
            })
        );
        assert_eq!(parse_local_img("/instance/a/b/c"), None);
        assert_eq!(parse_local_img("/other/x"), None);
        assert_eq!(parse_local_img("/skin/a/b"), None);
    }

    #[test]
    fn decodes_percent() {
        assert_eq!(percent_decode("bg-a%2Eb.png"), "bg-a.b.png");
    }
}
