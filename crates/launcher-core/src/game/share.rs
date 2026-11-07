//! Sharing a game log as a link (mclo.gs, the paste service Minecraft communities use): the
//! user can drop the link in a support channel instead of a file.

use std::path::{Path, PathBuf};
use std::sync::LazyLock;

use regex::Regex;
use serde::Deserialize;

use crate::error::{Error, IoContext, Result};
use crate::net::client::Http;

const MCLOGS_API: &str = "https://api.mclo.gs/1/log";

/// mclo.gs keeps at most this many lines; the end of a log is where a crash is.
const MAX_LINES: usize = 25_000;
const MAX_BYTES: usize = 10 * 1024 * 1024;

/// Access tokens (JWTs) and `--accessToken <value>` pairs, in case a mod logged one.
static TOKENS: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}|(--accessToken\s+)\S+",
    )
    .expect("static regex")
});
/// The user's home folder reveals their account name on the computer.
static HOME: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)(/home/|/Users/|[A-Z]:\\Users\\)[^/\\\s]+").expect("static regex")
});

/// What gets uploaded: the crash report when there is one, otherwise the game's latest log.
pub fn pick_log(game_dir: &Path, report: Option<&Path>) -> Option<PathBuf> {
    report
        .filter(|p| p.is_file())
        .map(Path::to_path_buf)
        .or_else(|| Some(game_dir.join("logs").join("latest.log")).filter(|p| p.is_file()))
}

/// The text as it will be published: last lines only, tokens and home folder names removed.
pub fn prepare(text: &str) -> String {
    let lines: Vec<&str> = text.lines().collect();
    let start = lines.len().saturating_sub(MAX_LINES);
    let mut out = lines[start..].join("\n");
    if out.len() > MAX_BYTES {
        let mut cut = out.len() - MAX_BYTES;
        while !out.is_char_boundary(cut) {
            cut += 1;
        }
        out = out[cut..].to_owned();
    }
    let out = TOKENS.replace_all(&out, "${1}•••");
    HOME.replace_all(&out, "${1}•••").into_owned()
}

#[derive(Deserialize)]
struct Reply {
    success: bool,
    url: Option<String>,
    error: Option<String>,
}

/// Upload a log file; returns the public link.
pub async fn upload(http: &Http, path: &Path) -> Result<String> {
    let bytes = tokio::fs::read(path).await.at(path)?;
    let content = prepare(&String::from_utf8_lossy(&bytes));
    let resp = http
        .send(|c| c.post(MCLOGS_API).form(&[("content", content.as_str())]))
        .await?;
    let reply: Reply = crate::net::client::json(resp).await?;
    match (reply.success, reply.url) {
        (true, Some(url)) if url.starts_with("https://") => Ok(url),
        _ => Err(Error::Invalid(format!(
            "mclo.gs: {}",
            reply.error.unwrap_or_else(|| "no link returned".into())
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn removes_tokens_and_user_names() {
        let t = prepare(
            "[main/INFO] Loading /home/piotr/.local/share/aerion/x\n\
             --accessToken eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop\n\
             C:\\Users\\Piotr\\AppData\\x",
        );
        assert!(!t.contains("piotr") && !t.contains("Piotr"));
        assert!(!t.contains("eyJ"));
        assert!(t.contains("/home/•••/.local") && t.contains("--accessToken •••"));
    }

    #[test]
    fn keeps_the_end_of_long_logs() {
        let log: String = (0..30_000).map(|i| format!("line {i}\n")).collect();
        let t = prepare(&log);
        assert_eq!(t.lines().count(), MAX_LINES);
        assert!(t.ends_with("line 29999"));
    }
}
