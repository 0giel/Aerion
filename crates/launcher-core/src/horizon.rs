//! Horizon (<https://github.com/horizon-rust/horizon>, MIT): Minecraft: Java Edition 26.3
//! rewritten in Rust, as its own kind of instance.
//!
//! What Horizon's launcher does, done here the same way (docs/api/launch-guide.md):
//! 1. the newest Nightly build for this platform from the project's GitHub releases, accepted
//!    only when its `SHA256SUMS` carries a valid Ed25519 signature by a key listed in
//!    `crates/horizon-release-sign/release-keys.txt`, and the archive matches that list;
//! 2. the player's own 26.3 files (version JSON, client jar, libraries, assets), the server jar
//!    and Java 25, downloaded by Aerion from Mojang as for any instance;
//! 3. `horizon-prepare` turns them into the resources the game reads (`--check` before starts);
//! 4. `horizon-client` starts with the Microsoft session on its standard input, never in argv.
//!
//! Horizon contains no Minecraft code or assets and runs only for an account that owns the game.

use std::path::{Path, PathBuf};
use std::process::Stdio;

use serde::Deserialize;
use tokio::io::{AsyncBufReadExt, BufReader};

use crate::Engine;
use crate::auth::AuthInfo;
use crate::error::{Error, HorizonFailure, IoContext, Result};
use crate::game::install::{Prepared, prepare};
use crate::game::launch::CommandSpec;
use crate::java::JavaInstall;
use crate::net::download::{DownloadItem, VerifyMode};
use crate::paths::display_path;
use crate::progress::TaskCtx;
use crate::util::hash::Hash;
use crate::util::sys::{Arch, Os};

/// The Minecraft version Horizon reimplements; its instances are always this version.
pub const MINECRAFT: &str = "26.3";

const RELEASES: &str = "https://api.github.com/repos/horizon-rust/horizon/releases?per_page=30";

/// Public keys that sign Horizon's releases (base64 Ed25519), as in the project's
/// `crates/horizon-release-sign/release-keys.txt`.
const RELEASE_KEYS: &[&str] = &["MU4oiIQcCxkZvbwSjjXs7SySCuM6ywRQ3nGaJYZG0gw="];

/// Nightly builds are signed for the channel: `horizon release nightly\n` + `SHA256SUMS`.
const CHANNEL: &str = "nightly";

fn err(kind: HorizonFailure, detail: impl Into<String>) -> Error {
    Error::Horizon {
        kind,
        detail: detail.into(),
    }
}

/// The game archive for this computer, as Horizon's own launcher picks it.
fn asset_name(os: Os, arch: Arch) -> Option<&'static str> {
    Some(match (os, arch) {
        (Os::Linux, Arch::X64) => "horizon-linux-x86_64.tar.gz",
        (Os::Macos, Arch::Arm64) => "horizon-macos-arm64.zip",
        (Os::Macos, Arch::X64) => "horizon-macos-x64.zip",
        (Os::Windows, Arch::X64) => "horizon-windows-x64.zip",
        (Os::Windows, Arch::Arm64) => "horizon-windows-arm64.zip",
        _ => return None,
    })
}

fn exe(name: &str) -> String {
    if cfg!(windows) {
        format!("{name}.exe")
    } else {
        name.to_owned()
    }
}

fn builds_dir(engine: &Engine) -> PathBuf {
    engine.paths.horizon_dir().join("builds")
}

fn prepared_root(engine: &Engine) -> PathBuf {
    engine.paths.horizon_dir().join("prepared")
}

fn prepared_dir(engine: &Engine, build: &str) -> PathBuf {
    prepared_root(engine).join(build)
}

fn server_jar(engine: &Engine) -> PathBuf {
    engine
        .paths
        .horizon_dir()
        .join(format!("server-{MINECRAFT}.jar"))
}

/// An installed build: its folder holds `horizon-client`, `horizon-prepare` and their files.
#[derive(Debug, Clone)]
pub struct Build {
    pub id: String,
    pub dir: PathBuf,
}

impl Build {
    fn client(&self) -> PathBuf {
        self.dir.join(exe("horizon-client"))
    }
    fn preparer(&self) -> PathBuf {
        self.dir.join(exe("horizon-prepare"))
    }
    fn installed(&self) -> bool {
        self.client().is_file() && self.preparer().is_file()
    }
}

/// A build folder name is ours (made in [`install_latest`]); refuse anything else.
fn valid_build_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_' || b == b'.')
        && !id.starts_with('.')
}

pub fn installed_build(engine: &Engine, id: &str) -> Option<Build> {
    if !valid_build_id(id) {
        return None;
    }
    let build = Build {
        id: id.to_owned(),
        dir: builds_dir(engine).join(id),
    };
    build.installed().then_some(build)
}

#[derive(Debug, Deserialize)]
struct Release {
    tag_name: String,
    #[serde(default)]
    published_at: Option<String>,
    #[serde(default)]
    assets: Vec<ReleaseAsset>,
}

#[derive(Debug, Deserialize)]
struct ReleaseAsset {
    name: String,
    browser_download_url: String,
    #[serde(default)]
    size: u64,
}

/// `SHA256SUMS` lines: `<hex>  <file name>`.
fn parse_sums(text: &str) -> Vec<(String, String)> {
    text.lines()
        .filter_map(|l| {
            let (hash, name) = l.split_once(char::is_whitespace)?;
            let name = name.trim().trim_start_matches('*');
            (hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_hexdigit()) && !name.is_empty())
                .then(|| (hash.to_ascii_lowercase(), name.to_owned()))
        })
        .collect()
}

/// True when one of the signature lines is a valid signature of the channel's message by one of
/// Horizon's release keys.
fn signed(sums: &[u8], signatures: &str, keys: &[&str]) -> bool {
    use base64::Engine as _;
    let b64 = base64::engine::general_purpose::STANDARD;
    let mut message = format!("horizon release {CHANNEL}\n").into_bytes();
    message.extend_from_slice(sums);
    signatures.lines().any(|line| {
        let Ok(sig) = b64.decode(line.trim()) else {
            return false;
        };
        keys.iter().any(|k| {
            b64.decode(k).is_ok_and(|key| {
                ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, key)
                    .verify(&message, &sig)
                    .is_ok()
            })
        })
    })
}

async fn get_text(engine: &Engine, url: &str) -> Result<String> {
    let resp = engine.http.send(|c| c.get(url)).await?;
    resp.text()
        .await
        .map_err(|e| err(HorizonFailure::Download, format!("{url}: {e}")))
}

/// The newest Nightly build for this platform that is signed and lists its archive.
async fn latest(engine: &Engine) -> Result<(String, ReleaseAsset, String)> {
    let asset_name = asset_name(engine.env.os, engine.env.arch).ok_or_else(|| {
        err(
            HorizonFailure::Platform,
            format!("{:?} {:?}", engine.env.os, engine.env.arch),
        )
    })?;
    let releases: Vec<Release> = engine.http.get_json(RELEASES).await?;
    for r in releases
        .into_iter()
        .filter(|r| r.tag_name.starts_with(CHANNEL))
    {
        let find = |n: &str| r.assets.iter().find(|a| a.name == n);
        let (Some(archive), Some(sums), Some(sig)) =
            (find(asset_name), find("SHA256SUMS"), find("SHA256SUMS.sig"))
        else {
            continue;
        };
        let sums_text = get_text(engine, &sums.browser_download_url).await?;
        let sig_text = get_text(engine, &sig.browser_download_url).await?;
        if !signed(sums_text.as_bytes(), &sig_text, RELEASE_KEYS) {
            return Err(err(HorizonFailure::Signature, r.tag_name.clone()));
        }
        let Some((hash, _)) = parse_sums(&sums_text)
            .into_iter()
            .find(|(_, n)| n == asset_name)
        else {
            continue;
        };
        // The moving `nightly` tag gets the day it was published; the hash tells builds apart.
        let day = r
            .published_at
            .as_deref()
            .and_then(|p| p.get(..10))
            .unwrap_or("");
        let tag = if r.tag_name == CHANNEL && !day.is_empty() {
            format!("{CHANNEL}-{day}")
        } else {
            r.tag_name.clone()
        };
        let id = format!("{tag}-{}", &hash[..8]);
        let archive = ReleaseAsset {
            name: archive.name.clone(),
            browser_download_url: archive.browser_download_url.clone(),
            size: archive.size,
        };
        return Ok((id, archive, hash));
    }
    Err(err(HorizonFailure::NoBuild, asset_name))
}

/// One install at a time: two instances started together would otherwise unpack the same build
/// into the same folders. The second waits, then finds the build installed.
static INSTALLING: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// Install the newest build, or keep the installed one when it already is the newest.
pub async fn install_latest(engine: &Engine, ctx: &TaskCtx) -> Result<Build> {
    let _one_at_a_time = INSTALLING.lock().await;
    ctx.stage("stage.horizonFind", None);
    let (id, archive, hash) = latest(engine).await?;
    if let Some(b) = installed_build(engine, &id) {
        return Ok(b);
    }
    ctx.stage("stage.horizonDownload", Some(id.clone()));
    let builds = builds_dir(engine);
    tokio::fs::create_dir_all(&builds).await.at(&builds)?;
    let file = builds.join(format!(".{}", archive.name));
    let item = DownloadItem::new(&archive.browser_download_url, &file)
        .hash(Some(Hash::Sha256(hash)))
        .size(Some(archive.size));
    engine.downloader.fetch(&item, ctx).await?;
    ctx.check()?;

    // Unpack beside the final folder, then move it in: a half-unpacked build is never used.
    let staging = builds.join(format!(".{id}.unpack"));
    let _ = tokio::fs::remove_dir_all(&staging).await;
    let (f, s) = (file.clone(), staging.clone());
    tokio::task::spawn_blocking(move || -> Result<()> {
        if f.to_string_lossy().ends_with(".tar.gz") {
            crate::util::archive::extract_tar_gz(&f, &s)
        } else {
            crate::util::archive::extract_zip(&f, &s, &|_: &str| true).map(|_| ())
        }
    })
    .await
    .map_err(|e| err(HorizonFailure::Unpack, e.to_string()))??;
    let _ = tokio::fs::remove_file(&file).await;
    let root = build_root(&staging)
        .await
        .ok_or_else(|| err(HorizonFailure::Unpack, "no horizon-client in the archive"))?;
    make_executable(&root).await;
    let dest = builds.join(&id);
    let _ = tokio::fs::remove_dir_all(&dest).await;
    tokio::fs::rename(&root, &dest).await.at(&dest)?;
    let _ = tokio::fs::remove_dir_all(&staging).await;
    Ok(Build { id, dir: dest })
}

/// Remove builds (and their prepared resources) that aren't in `keep`.
pub async fn prune(engine: &Engine, keep: &[String]) {
    // Not while a build is being installed, and never anything touched in the last minutes: another
    // instance may have installed it a moment ago and not recorded it yet.
    let _no_install = INSTALLING.lock().await;
    let recent = std::time::SystemTime::now() - std::time::Duration::from_secs(600);
    for dir in [builds_dir(engine), prepared_root(engine)] {
        let Ok(mut rd) = tokio::fs::read_dir(&dir).await else {
            continue;
        };
        while let Ok(Some(e)) = rd.next_entry().await {
            let name = e.file_name().to_string_lossy().into_owned();
            // Our own names only; leftovers of an interrupted unpack start with a dot.
            let fresh = e
                .metadata()
                .await
                .and_then(|m| m.modified())
                .is_ok_and(|t| t > recent);
            if (valid_build_id(&name) || name.starts_with('.')) && !keep.contains(&name) && !fresh {
                let p = e.path();
                let _ = if p.is_dir() {
                    tokio::fs::remove_dir_all(&p).await
                } else {
                    tokio::fs::remove_file(&p).await
                };
            }
        }
    }
}

/// The folder with `horizon-client`: the archive's top folder, or the unpacked folder itself.
async fn build_root(dir: &Path) -> Option<PathBuf> {
    if dir.join(exe("horizon-client")).is_file() {
        return Some(dir.to_path_buf());
    }
    let mut rd = tokio::fs::read_dir(dir).await.ok()?;
    while let Ok(Some(e)) = rd.next_entry().await {
        if e.path().join(exe("horizon-client")).is_file() {
            return Some(e.path());
        }
    }
    None
}

/// Zip archives (macOS) may not carry the executable bit.
async fn make_executable(dir: &Path) {
    #[cfg(unix)]
    for name in [
        "horizon-client",
        "horizon-prepare",
        "horizon-ctl",
        "horizon-server",
    ] {
        use std::os::unix::fs::PermissionsExt;
        let p = dir.join(name);
        if let Ok(meta) = tokio::fs::metadata(&p).await {
            let mut perm = meta.permissions();
            perm.set_mode(perm.mode() | 0o755);
            let _ = tokio::fs::set_permissions(&p, perm).await;
        }
    }
    #[cfg(not(unix))]
    let _ = dir;
}

/// One line of `horizon-prepare --progress json`.
#[derive(Debug, Default, Deserialize)]
struct PrepareLine {
    message: Option<String>,
    fraction: Option<f64>,
    error: Option<String>,
}

/// The resources for `build`, prepared from the player's own 26.3 files when missing or stale.
/// Also returns the vanilla files and the Java used, for the launch record.
pub async fn ensure_resources(
    engine: &Engine,
    build: &Build,
    ctx: &TaskCtx,
) -> Result<(PathBuf, Prepared, JavaInstall)> {
    let out = prepared_dir(engine, &build.id);
    // The game files are needed for the launch record either way; they are cached after once.
    let prepared = prepare(&engine.meta, &engine.env, MINECRAFT, VerifyMode::Fast, ctx).await?;
    ctx.stage("stage.java", None);
    let java = engine
        .runtimes()
        .ensure(&prepared.java.component, prepared.java.major_version, ctx)
        .await?;
    if check(build, &out).await {
        return Ok((out, prepared, java));
    }

    // The server jar, only for this owner and only the first time (no launcher keeps it).
    let server = prepared
        .version
        .downloads
        .as_ref()
        .and_then(|d| d.server.clone())
        .ok_or_else(|| {
            err(
                HorizonFailure::Prepare,
                "26.3 has no server jar in its version JSON",
            )
        })?;
    let jar = server_jar(engine);
    let item = DownloadItem::new(&server.url, &jar)
        .sha1(server.sha1.as_deref())
        .size(server.size);
    engine.downloader.fetch(&item, ctx).await?;

    ctx.stage("stage.horizonPrepare", None);
    tokio::fs::create_dir_all(&out).await.at(&out)?;
    let java_home = java
        .path
        .parent()
        .and_then(Path::parent)
        .map(Path::to_path_buf)
        .ok_or_else(|| err(HorizonFailure::Prepare, "no Java home"))?;
    let p = &engine.paths;
    let args: Vec<String> = vec![
        "--prepare".into(),
        "--version-json".into(),
        display_path(&p.version_json(MINECRAFT)),
        "--client-jar".into(),
        display_path(&p.client_jar(MINECRAFT)),
        "--libraries-dir".into(),
        display_path(&p.libraries_dir()),
        "--assets-dir".into(),
        display_path(&p.assets_dir()),
        "--server-jar".into(),
        display_path(&jar),
        "--java-home".into(),
        display_path(&java_home),
        "--output".into(),
        display_path(&out),
        "--complete".into(),
        "--progress".into(),
        "json".into(),
    ];
    let mut cmd = crate::util::process::command(build.preparer());
    cmd.args(&args)
        .current_dir(&build.dir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    let mut child = cmd
        .spawn()
        .map_err(|e| err(HorizonFailure::Prepare, e.to_string()))?;
    let stderr = child.stderr.take();
    let stderr_task = tokio::spawn(async move {
        let mut text = String::new();
        if let Some(e) = stderr {
            let mut lines = BufReader::new(e).lines();
            while let Ok(Some(l)) = lines.next_line().await {
                tracing::debug!(target: "horizon", "prepare: {l}");
                text = l;
            }
        }
        text
    });
    let mut failure = None;
    if let Some(stdout) = child.stdout.take() {
        let mut lines = BufReader::new(stdout).lines();
        let mut message = String::new();
        loop {
            let line = tokio::select! {
                l = lines.next_line() => l,
                _ = ctx.cancel.cancelled() => {
                    let _ = child.kill().await;
                    return Err(Error::Cancelled);
                }
            };
            let Ok(Some(line)) = line else { break };
            let Ok(p) = serde_json::from_str::<PrepareLine>(&line) else {
                continue;
            };
            if let Some(m) = p.message {
                message = m;
            }
            if let Some(e) = p.error {
                failure = Some(e);
            }
            let detail = match p.fraction {
                Some(f) => format!("{}% · {message}", (f.clamp(0.0, 1.0) * 100.0).round()),
                None => message.clone(),
            };
            ctx.stage("stage.horizonPrepare", Some(detail));
        }
    }
    let status = child
        .wait()
        .await
        .map_err(|e| err(HorizonFailure::Prepare, e.to_string()))?;
    let last_err = stderr_task.await.unwrap_or_default();
    if !status.success() {
        return Err(err(
            HorizonFailure::Prepare,
            failure.unwrap_or(if last_err.is_empty() {
                format!("horizon-prepare exited with {status}")
            } else {
                last_err
            }),
        ));
    }
    Ok((out, prepared, java))
}

/// `horizon-prepare --check`: fast, reads no game files; exit 0 when the resources are current.
async fn check(build: &Build, out: &Path) -> bool {
    if !out.is_dir() {
        return false;
    }
    let mut cmd = crate::util::process::command(build.preparer());
    cmd.args(["--check", "--output"])
        .arg(out)
        .current_dir(&build.dir)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    matches!(cmd.status().await, Ok(s) if s.success())
}

#[derive(Debug, Clone)]
pub struct StartOptions {
    pub game_dir: PathBuf,
    pub resources: PathBuf,
    pub resolution: Option<(u32, u32)>,
    pub fullscreen: bool,
    pub world: Option<String>,
    pub server: Option<String>,
    /// The whole environment to add, defaults included.
    pub env: Vec<(String, String)>,
}

/// The session JSON the game reads on its standard input (docs/api/launch.md).
fn session_json(auth: &AuthInfo) -> String {
    let mut v = serde_json::json!({
        "name": auth.name,
        "uuid": auth.uuid,
        "access_token": auth.access_token,
        "user_type": "msa",
    });
    if !auth.xuid.is_empty() && auth.xuid != "0" {
        v["xuid"] = serde_json::Value::String(auth.xuid.clone());
    }
    v.to_string()
}

/// Horizon starts only for a signed-in Microsoft account (which Aerion has checked owns the game).
pub fn command(build: &Build, auth: &AuthInfo, o: &StartOptions) -> Result<CommandSpec> {
    if auth.user_type != "msa" || auth.access_token.is_empty() {
        return Err(err(HorizonFailure::NeedsOwner, ""));
    }
    let mut args = vec![
        "--resources".to_owned(),
        display_path(&o.resources),
        "--game-dir".to_owned(),
        display_path(&o.game_dir),
        "--home".to_owned(),
        display_path(&o.game_dir),
    ];
    if let Some((w, h)) = o.resolution {
        args.extend([
            "--width".into(),
            w.to_string(),
            "--height".into(),
            h.to_string(),
        ]);
    }
    if o.fullscreen {
        args.push("--fullscreen".into());
    }
    if let Some(server) = &o.server {
        args.extend(["--quickPlayMultiplayer".into(), server.clone()]);
    } else if let Some(world) = &o.world {
        args.extend(["--quickPlaySingleplayer".into(), world.clone()]);
    }
    args.push("--session-stdin".into());
    Ok(CommandSpec {
        program: build.client(),
        args,
        cwd: o.game_dir.clone(),
        env: o.env.clone(),
        secrets: vec![auth.access_token.clone()],
        stdin: Some(session_json(auth)),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_checksum_lists() {
        let text = "19b4  bad\n19b439edd1174698e2a27030b3a294048a95f2ac9cd293351b8852856d34f8b6  horizon-linux-x86_64.tar.gz\n";
        let sums = parse_sums(text);
        assert_eq!(sums.len(), 1);
        assert_eq!(sums[0].1, "horizon-linux-x86_64.tar.gz");
    }

    /// The Nightly of 2026-11-05: its real `SHA256SUMS` and `SHA256SUMS.sig`.
    const SUMS: &str = "19b439edd1174698e2a27030b3a294048a95f2ac9cd293351b8852856d34f8b6  horizon-linux-x86_64.tar.gz
0e3157a74c56249573f42624d64bc87774324dec1cd20ba6518c784b7293eda0  horizon-windows-x64.zip
d099e4688d8d8b740f60dd7a0e31fdbd7911be140861c8cad003631b2e0a4c7f  horizon-windows-arm64.zip
2178f5cd71739de9ff66e7102dc1070e1e37975c9c8db6a61f1b1026202f4760  horizon-macos-arm64.zip
3c92b3f1602abe83bbb4ade6d2d23f0560a81315b5eb5a46f9c87ccc5b8a520e  horizon-macos-x64.zip
5bc6a9afba48e1b7fa4a926eedd9656c5cb320ed01222043c7d69527120b64b7  horizon-server-linux-x86_64.tar.gz
3e3f5bf9df5f86b3c72a950f0869f09d1178814ac101d5f9af849b5d70c0fef4  horizon-source.tar.gz
";
    const SIG: &str = "4I1yGxN2BjuD1JJdO3ZafCb9+J8B3ziGlfNGrK4eXzNG+fyQyJgWqT8C88lXvnGvBRthVYQLh4GTz+xVT6kfDw==\n";

    #[test]
    fn accepts_only_lists_signed_by_horizon() {
        assert!(signed(SUMS.as_bytes(), SIG, RELEASE_KEYS));
        let tampered = SUMS.replacen("19b4", "29b4", 1);
        assert!(!signed(tampered.as_bytes(), SIG, RELEASE_KEYS));
        assert!(!signed(SUMS.as_bytes(), "not base64", RELEASE_KEYS));
        let other_key = ["AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="];
        assert!(!signed(SUMS.as_bytes(), SIG, &other_key));
    }

    #[test]
    fn session_goes_to_stdin_never_argv() {
        let dir = tempfile::tempdir().unwrap();
        let build = Build {
            id: "nightly-2026-11-05-19b439ed".into(),
            dir: dir.path().join("b"),
        };
        let auth = AuthInfo {
            name: "Alex".into(),
            uuid: "069a79f444e94726a5befca90e38aaf5".into(),
            access_token: "secret-token-123456".into(),
            xuid: "0".into(),
            user_type: "msa".into(),
        };
        let o = StartOptions {
            game_dir: dir.path().join("g"),
            resources: dir.path().join("r"),
            resolution: Some((1280, 720)),
            fullscreen: false,
            world: None,
            server: Some("example.org".into()),
            env: vec![],
        };
        let spec = command(&build, &auth, &o).unwrap();
        assert!(spec.args.iter().all(|a| !a.contains("secret-token")));
        assert!(spec.args.contains(&"--session-stdin".to_owned()));
        assert!(spec.args.contains(&"--quickPlayMultiplayer".to_owned()));
        let stdin: serde_json::Value =
            serde_json::from_str(spec.stdin.as_deref().unwrap()).unwrap();
        assert_eq!(stdin["access_token"], "secret-token-123456");
        assert!(stdin.get("xuid").is_none());

        let offline = AuthInfo {
            user_type: "legacy".into(),
            ..auth
        };
        assert!(command(&build, &offline, &o).is_err());
    }

    #[test]
    fn build_ids_stay_inside_the_builds_folder() {
        assert!(valid_build_id("nightly-2026-11-05-19b439ed"));
        assert!(!valid_build_id("../x"));
        assert!(!valid_build_id(".hidden"));
        assert!(!valid_build_id(""));
    }
}
