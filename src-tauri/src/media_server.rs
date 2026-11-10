//! Theme videos for the Linux (WebKitGTK) window. Its media player won't load from the
//! launcher's own `themeasset:` scheme (the error is "source not supported", whatever the file),
//! but it plays the same file from plain HTTP. So videos come from a tiny server that listens on
//! 127.0.0.1 only, on a random port, behind a random token, and serves nothing but the theme
//! asset folder, with byte ranges (needed for seeking and for large files). It starts on first
//! use. Windows (WebView2) plays from the scheme and doesn't use it.
use std::io::SeekFrom;
use std::path::{Path, PathBuf};

use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::OnceCell;

use crate::protocols::percent_decode;
use crate::themes::{content_type, valid_asset};

static BASE: OnceCell<String> = OnceCell::const_new();

/// `http://127.0.0.1:<port>/<token>/`, the address theme videos are under.
pub async fn base(dir: PathBuf) -> std::io::Result<String> {
    BASE.get_or_try_init(|| start(dir)).await.cloned()
}

async fn start(dir: PathBuf) -> std::io::Result<String> {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
    let port = listener.local_addr()?.port();
    let token = uuid::Uuid::new_v4().simple().to_string();
    let base = format!("http://127.0.0.1:{port}/{token}/");
    tauri::async_runtime::spawn(async move {
        loop {
            let Ok((socket, _)) = listener.accept().await else {
                continue;
            };
            let (dir, token) = (dir.clone(), token.clone());
            tauri::async_runtime::spawn(async move {
                if let Err(e) = serve(socket, &dir, &token).await {
                    tracing::debug!(error = %e, "theme video request");
                }
            });
        }
    });
    Ok(base)
}

/// What a request asks for: the asset's name and an optional byte range.
#[derive(Debug, PartialEq)]
struct Wanted {
    name: String,
    head: bool,
    range: Option<(u64, Option<u64>)>,
}

fn parse(request: &str, token: &str) -> Option<Wanted> {
    let mut lines = request.split("\r\n");
    let mut first = lines.next()?.split(' ');
    let method = first.next()?;
    let target = first.next()?.split('?').next()?;
    if method != "GET" && method != "HEAD" {
        return None;
    }
    let name = percent_decode(
        target
            .strip_prefix('/')?
            .strip_prefix(token)?
            .strip_prefix('/')?,
    );
    if !valid_asset(&name) {
        return None;
    }
    let range = lines
        .filter_map(|l| l.split_once(':'))
        .find(|(k, _)| k.trim().eq_ignore_ascii_case("range"))
        .and_then(|(_, v)| v.trim().strip_prefix("bytes=").map(str::to_owned))
        .and_then(|r| {
            let (s, e) = r.split_once('-')?;
            Some((s.trim().parse().ok()?, e.trim().parse().ok()))
        });
    Some(Wanted {
        name,
        head: method == "HEAD",
        range,
    })
}

async fn reply(socket: &mut TcpStream, status: &str) -> std::io::Result<()> {
    let text = format!("HTTP/1.1 {status}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    socket.write_all(text.as_bytes()).await
}

async fn serve(mut socket: TcpStream, dir: &Path, token: &str) -> std::io::Result<()> {
    let mut request = Vec::new();
    let mut chunk = [0u8; 2048];
    while !request.windows(4).any(|w| w == b"\r\n\r\n") {
        let n = socket.read(&mut chunk).await?;
        if n == 0 || request.len() > 16 * 1024 {
            return Ok(());
        }
        request.extend_from_slice(&chunk[..n]);
    }
    let Some(wanted) = parse(&String::from_utf8_lossy(&request), token) else {
        return reply(&mut socket, "404 Not Found").await;
    };
    let Ok(mut file) = tokio::fs::File::open(dir.join(&wanted.name)).await else {
        return reply(&mut socket, "404 Not Found").await;
    };
    let size = file.metadata().await?.len();
    let (start, end) = match wanted.range {
        Some((s, e)) => (
            s,
            e.unwrap_or(size.saturating_sub(1))
                .min(size.saturating_sub(1)),
        ),
        None => (0, size.saturating_sub(1)),
    };
    if size == 0 || start > end {
        let text = format!(
            "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        );
        return socket.write_all(text.as_bytes()).await;
    }
    let length = end - start + 1;
    let mut head = format!(
        "HTTP/1.1 {}\r\nContent-Type: {}\r\nContent-Length: {length}\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n",
        if wanted.range.is_some() {
            "206 Partial Content"
        } else {
            "200 OK"
        },
        content_type(&wanted.name),
    );
    if wanted.range.is_some() {
        head.push_str(&format!("Content-Range: bytes {start}-{end}/{size}\r\n"));
    }
    head.push_str("\r\n");
    socket.write_all(head.as_bytes()).await?;
    if wanted.head {
        return Ok(());
    }
    file.seek(SeekFrom::Start(start)).await?;
    tokio::io::copy(&mut file.take(length), &mut socket).await?;
    socket.flush().await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_requests() {
        let get = "GET /tok/bg-1.mp4 HTTP/1.1\r\nHost: x\r\nRange: bytes=100-\r\n\r\n";
        assert_eq!(
            parse(get, "tok"),
            Some(Wanted {
                name: "bg-1.mp4".into(),
                head: false,
                range: Some((100, None))
            })
        );
        let both = "GET /tok/bg-1.mp4 HTTP/1.1\r\nrange: bytes=0-99\r\n\r\n";
        assert_eq!(parse(both, "tok").unwrap().range, Some((0, Some(99))));
        // Another token, another folder or another method gets nothing.
        assert_eq!(parse("GET /other/bg-1.mp4 HTTP/1.1\r\n\r\n", "tok"), None);
        assert_eq!(
            parse("GET /tok/../settings.json HTTP/1.1\r\n\r\n", "tok"),
            None
        );
        assert_eq!(
            parse("GET /tok/%2E%2E%2Fsettings.json HTTP/1.1\r\n\r\n", "tok"),
            None
        );
        assert_eq!(parse("POST /tok/bg-1.mp4 HTTP/1.1\r\n\r\n", "tok"), None);
    }
}
