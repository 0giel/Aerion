//! Building the launch command and running the game process.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::{Instant, SystemTime};

use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use crate::error::{Error, IoContext, Result};
use crate::game::install::Prepared;
use crate::game::logs::{LogLevel, LogLine, LogParser};
use crate::game::process::hide_console;
use crate::java::flags::{JvmPreset, memory_args, preset_args};
use crate::mojang::args::{Vars, game_args, jvm_args, supports_quick_play};
use crate::mojang::rules::Environment;
use crate::paths::{Paths, display_path};
use crate::util::sys::Os;

#[derive(Debug, Clone)]
pub struct AuthInfo {
    pub name: String,
    pub uuid: String,
    pub access_token: String,
    pub xuid: String,
    /// `msa` for Microsoft accounts, `legacy` for offline ones (removed from 26.x arguments).
    pub user_type: String,
}

#[derive(Debug, Clone)]
pub struct LaunchOptions {
    pub game_dir: PathBuf,
    pub java: PathBuf,
    pub java_major: u32,
    pub max_memory_mb: u32,
    pub min_memory_mb: Option<u32>,
    pub preset: JvmPreset,
    pub extra_jvm_args: Vec<String>,
    pub extra_game_args: Vec<String>,
    pub resolution: Option<(u32, u32)>,
    /// `host[:port]` to join right after start.
    pub quick_play_server: Option<String>,
    /// Folder in `saves/` to enter right after start (needs a version with Quick Play).
    pub quick_play_world: Option<String>,
    pub env: Vec<(String, String)>,
    /// Command prefix such as `gamemoderun` or `prime-run`.
    pub wrapper: Vec<String>,
    pub auth: AuthInfo,
    /// Telemetry client id (`--clientId`), random per installation.
    pub client_id: String,
}

#[derive(Debug, Clone)]
pub struct CommandSpec {
    pub program: PathBuf,
    pub args: Vec<String>,
    pub cwd: PathBuf,
    pub env: Vec<(String, String)>,
    /// Values that must never appear in logs (access token).
    pub secrets: Vec<String>,
    /// Written to the game's standard input, then closed (Horizon's session JSON). Never logged.
    pub stdin: Option<String>,
}

impl CommandSpec {
    /// Printable command line with secrets masked.
    pub fn redacted(&self) -> String {
        let mut s = display_path(&self.program);
        for a in &self.args {
            s.push(' ');
            s.push_str(&mask(a, &self.secrets));
        }
        s
    }
}

pub fn mask(text: &str, secrets: &[String]) -> String {
    let mut out = text.to_owned();
    for s in secrets.iter().filter(|s| s.len() >= 8) {
        if out.contains(s.as_str()) {
            out = out.replace(s.as_str(), "•••");
        }
    }
    out
}

/// Environment defaults for the game process; instance variables override them.
pub fn default_env(os: Os) -> Vec<(String, String)> {
    let mut env = Vec::new();
    // NVIDIA's threaded GL optimisations crash early loading windows in native code (NeoForge
    // 26.3: SIGSEGV in libnvidia-glcore) and are known to hurt Minecraft; Sodium turns them off
    // for itself as well.
    if os == Os::Linux
        && std::env::var_os("__GL_THREADED_OPTIMIZATIONS").is_none()
        && Path::new("/proc/driver/nvidia/version").exists()
    {
        env.push(("__GL_THREADED_OPTIMIZATIONS".into(), "0".into()));
    }
    env
}

pub fn build_command(
    prepared: &Prepared,
    opts: &LaunchOptions,
    paths: &Paths,
    base_env: &Environment,
) -> CommandSpec {
    let mut env = base_env.clone();
    env.features.has_custom_resolution = opts.resolution.is_some();
    let version = &prepared.version;
    let quick_play_modern = opts.quick_play_server.is_some() && supports_quick_play(version);
    env.features.is_quick_play_multiplayer = quick_play_modern;
    env.features.is_quick_play_singleplayer =
        opts.quick_play_world.is_some() && supports_quick_play(version);

    let sep = if base_env.os == Os::Windows { ";" } else { ":" };
    let classpath = prepared
        .classpath
        .iter()
        .map(|p| display_path(p))
        .collect::<Vec<_>>()
        .join(sep);
    let auth = &opts.auth;
    let mut vars = Vars::new();
    let mut set = |k: &str, v: String| {
        vars.insert(k.to_owned(), v);
    };
    set("auth_player_name", auth.name.clone());
    set("version_name", version.id.clone());
    set("game_directory", display_path(&opts.game_dir));
    set("assets_root", display_path(&prepared.assets_root));
    set("game_assets", display_path(&prepared.game_assets));
    set("assets_index_name", prepared.asset_index_id.clone());
    set("auth_uuid", auth.uuid.clone());
    set("auth_access_token", auth.access_token.clone());
    set(
        "auth_session",
        format!("token:{}:{}", auth.access_token, auth.uuid),
    );
    set("clientid", opts.client_id.clone());
    set("auth_xuid", auth.xuid.clone());
    set("user_type", auth.user_type.clone());
    set(
        "version_type",
        version.kind.clone().unwrap_or_else(|| "release".into()),
    );
    set("user_properties", "{}".into());
    set("natives_directory", display_path(&prepared.natives_dir));
    set("launcher_name", "aerion".into());
    set("launcher_version", env!("CARGO_PKG_VERSION").into());
    set("classpath", classpath);
    set("classpath_separator", sep.into());
    set("library_directory", display_path(&paths.libraries_dir()));
    if let Some((w, h)) = opts.resolution {
        set("resolution_width", w.to_string());
        set("resolution_height", h.to_string());
    }
    if let Some(server) = &opts.quick_play_server {
        set("quickPlayMultiplayer", server.clone());
    }
    if let Some(world) = &opts.quick_play_world {
        set("quickPlaySingleplayer", world.clone());
    }

    let mut args: Vec<String> = Vec::new();
    args.extend(memory_args(
        opts.preset,
        opts.min_memory_mb,
        opts.max_memory_mb,
    ));
    args.extend(preset_args(
        opts.preset,
        opts.java_major,
        opts.max_memory_mb,
    ));
    // Make the console readable regardless of the OS code page (ignored by older JVMs).
    args.push("-Dstdout.encoding=UTF-8".into());
    args.push("-Dstderr.encoding=UTF-8".into());
    args.extend(opts.extra_jvm_args.iter().cloned());
    args.extend(jvm_args(version, &env, &vars));
    if let Some(l) = &prepared.logging_arg {
        args.push(l.clone());
    }
    args.push(
        version
            .main_class
            .clone()
            .unwrap_or_else(|| "net.minecraft.client.main.Main".into()),
    );
    args.extend(game_args(version, &env, &vars));
    if let (Some(server), false) = (&opts.quick_play_server, quick_play_modern) {
        let (host, port) = server
            .rsplit_once(':')
            .unwrap_or((server.as_str(), "25565"));
        args.extend([
            "--server".into(),
            host.to_owned(),
            "--port".into(),
            port.to_owned(),
        ]);
    }
    args.extend(opts.extra_game_args.iter().cloned());

    let (program, args) = match opts.wrapper.split_first() {
        Some((w, rest)) => {
            let mut a = rest.to_vec();
            a.push(display_path(&opts.java));
            a.extend(args);
            (PathBuf::from(w), a)
        }
        None => (opts.java.clone(), args),
    };
    CommandSpec {
        program,
        args,
        cwd: opts.game_dir.clone(),
        env: opts.env.clone(),
        secrets: vec![auth.access_token.clone()],
        stdin: None,
    }
}

#[derive(Debug, Clone)]
pub struct GameExit {
    pub code: Option<i32>,
    /// Stopped by the user from the launcher.
    pub killed: bool,
    pub started: SystemTime,
    pub duration_secs: u64,
}

/// Run the game until it exits (or `stop` is cancelled), streaming parsed log lines.
/// `on_spawn` receives the game's process id once it is running (for resource monitoring).
pub async fn run(
    spec: CommandSpec,
    lines: mpsc::UnboundedSender<LogLine>,
    stop: CancellationToken,
    on_spawn: impl FnOnce(Option<u32>) + Send,
) -> Result<GameExit> {
    tokio::fs::create_dir_all(&spec.cwd).await.at(&spec.cwd)?;
    let mut cmd = tokio::process::Command::new(&spec.program);
    for var in crate::util::sys::private_env() {
        cmd.env_remove(var);
    }
    cmd.args(&spec.args)
        .current_dir(&spec.cwd)
        .envs(spec.env.iter().cloned())
        .stdin(if spec.stdin.is_some() {
            Stdio::piped()
        } else {
            Stdio::null()
        })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    hide_console(&mut cmd);
    let started = SystemTime::now();
    let t0 = Instant::now();
    let mut child = cmd.spawn().map_err(|e| Error::JavaInvalid {
        path: spec.program.clone(),
        reason: e.to_string(),
    })?;
    tracing::info!(pid = child.id(), cmd = %spec.redacted(), "game started");
    if let (Some(data), Some(mut input)) = (spec.stdin.as_deref(), child.stdin.take()) {
        use tokio::io::AsyncWriteExt;
        // Then dropped, which closes it: the game reads to the end.
        if let Err(e) = input.write_all(data.as_bytes()).await {
            tracing::warn!("couldn't hand the session to the game: {e}");
        }
    }
    on_spawn(child.id());

    let mut readers = Vec::new();
    if let Some(out) = child.stdout.take() {
        readers.push(tokio::spawn(pump(
            out,
            lines.clone(),
            spec.secrets.clone(),
            false,
        )));
    }
    if let Some(err) = child.stderr.take() {
        readers.push(tokio::spawn(pump(
            err,
            lines.clone(),
            spec.secrets.clone(),
            true,
        )));
    }

    let mut killed = false;
    let status = tokio::select! {
        s = child.wait() => s,
        _ = stop.cancelled() => {
            killed = true;
            let _ = child.start_kill();
            child.wait().await
        }
    }
    .at(&spec.program)?;
    for r in readers {
        let _ = r.await;
    }
    Ok(GameExit {
        code: status.code(),
        killed,
        started,
        duration_secs: t0.elapsed().as_secs(),
    })
}

async fn pump<R: tokio::io::AsyncRead + Unpin>(
    reader: R,
    tx: mpsc::UnboundedSender<LogLine>,
    secrets: Vec<String>,
    is_stderr: bool,
) {
    let mut parser = LogParser::default();
    let mut reader = BufReader::new(reader);
    let mut buf = Vec::with_capacity(4096);
    loop {
        buf.clear();
        match reader.read_until(b'\n', &mut buf).await {
            Ok(0) | Err(_) => break,
            Ok(_) => {
                let line = String::from_utf8_lossy(&buf);
                let line = line.trim_end_matches(['\n', '\r']);
                if let Some(mut l) = parser.push(line) {
                    l.text = mask(&l.text, &secrets);
                    if is_stderr && l.level == LogLevel::Raw {
                        l.level = LogLevel::Error;
                    }
                    if tx.send(l).is_err() {
                        break;
                    }
                }
            }
        }
    }
    if let Some(l) = parser.finish() {
        let _ = tx.send(l);
    }
}

/// Copy assets into `<game dir>/resources` for pre-1.6 versions.
pub async fn prepare_game_dir(prepared: &Prepared, game_dir: &Path) -> Result<()> {
    tokio::fs::create_dir_all(game_dir).await.at(game_dir)?;
    if let Some(idx) = &prepared.resources_index {
        idx.materialize(&prepared.assets_root, &game_dir.join("resources"))
            .await?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mojang::version::VersionJson;
    use crate::util::sys::Arch;

    fn prepared(fixture: &str) -> Prepared {
        let path = format!(
            "{}/tests/fixtures/versions/{fixture}",
            env!("CARGO_MANIFEST_DIR")
        );
        let version: VersionJson =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        Prepared {
            java: version.java_requirement(),
            root_id: version.id.clone(),
            classpath: vec![
                PathBuf::from("/data/libraries/a.jar"),
                PathBuf::from("/data/versions/x/x.jar"),
            ],
            natives_dir: PathBuf::from("/data/natives/x"),
            assets_root: PathBuf::from("/data/assets"),
            game_assets: PathBuf::from("/data/assets"),
            asset_index_id: "34".into(),
            resources_index: None,
            logging_arg: Some(
                "-Dlog4j.configurationFile=/data/assets/log_configs/client-1.21.2.xml".into(),
            ),
            version,
        }
    }

    fn options(game_dir: &str) -> LaunchOptions {
        LaunchOptions {
            game_dir: PathBuf::from(game_dir),
            java: PathBuf::from("/java/bin/java"),
            java_major: 25,
            max_memory_mb: 4096,
            min_memory_mb: None,
            preset: JvmPreset::Standard,
            extra_jvm_args: vec!["-Dcustom=yes".into()],
            extra_game_args: vec![],
            resolution: Some((1280, 720)),
            quick_play_server: None,
            quick_play_world: None,
            env: vec![],
            wrapper: vec![],
            auth: AuthInfo {
                name: "Łukasz".into(),
                uuid: "uuid".into(),
                access_token: "secret-access-token".into(),
                xuid: "2535".into(),
                user_type: "msa".into(),
            },
            client_id: "client".into(),
        }
    }

    #[test]
    fn full_command_for_26_3() {
        let p = prepared("26.3.json");
        let paths = Paths::at(PathBuf::from("/data"), false);
        let spec = build_command(
            &p,
            &options("/data/instances/Mój świat/minecraft"),
            &paths,
            &Environment::for_os(Os::Linux, Arch::X64),
        );
        let a = &spec.args;
        assert_eq!(&a[..2], ["-Xms512M", "-Xmx4096M"]);
        let main = a
            .iter()
            .position(|x| x == "net.minecraft.client.main.Main")
            .unwrap();
        let custom = a.iter().position(|x| x == "-Dcustom=yes").unwrap();
        let cp = a.iter().position(|x| x == "-cp").unwrap();
        assert!(custom < cp && cp < main);
        assert_eq!(a[cp + 1], "/data/libraries/a.jar:/data/versions/x/x.jar");
        assert!(a[main - 1].starts_with("-Dlog4j.configurationFile="));
        let gd = a.iter().position(|x| x == "--gameDir").unwrap();
        assert_eq!(a[gd + 1], "/data/instances/Mój świat/minecraft");
        assert!(a.windows(2).any(|w| w == ["--width", "1280"]));
        assert!(a.iter().all(|x| !x.contains("${")), "{a:?}");
        assert!(!spec.redacted().contains("secret-access-token"));
    }

    #[test]
    fn legacy_quick_play_and_wrapper() {
        let p = prepared("1.8.9.json");
        let mut o = options("/g");
        o.quick_play_server = Some("mc.example.net:25570".into());
        o.wrapper = vec!["gamemoderun".into()];
        let spec = build_command(
            &p,
            &o,
            &Paths::at(PathBuf::from("/data"), false),
            &Environment::for_os(Os::Windows, Arch::X64),
        );
        assert_eq!(spec.program, PathBuf::from("gamemoderun"));
        assert_eq!(spec.args[0], "/java/bin/java");
        assert!(
            spec.args
                .windows(4)
                .any(|w| w == ["--server", "mc.example.net", "--port", "25570"])
        );
        let cp = spec.args.iter().position(|x| x == "-cp").unwrap();
        assert_eq!(
            spec.args[cp + 1],
            "/data/libraries/a.jar;/data/versions/x/x.jar",
            "Windows separator"
        );
    }

    #[test]
    fn modern_quick_play() {
        let p = prepared("1.21.11.json");
        let mut o = options("/g");
        o.quick_play_server = Some("play.example.org".into());
        let spec = build_command(
            &p,
            &o,
            &Paths::at(PathBuf::from("/data"), false),
            &Environment::for_os(Os::Linux, Arch::X64),
        );
        assert!(
            spec.args
                .windows(2)
                .any(|w| w == ["--quickPlayMultiplayer", "play.example.org"])
        );
        assert!(!spec.args.contains(&"--server".to_owned()));
    }

    #[test]
    fn masks_tokens() {
        assert_eq!(
            mask(
                "token:secret-access-token:uuid",
                &["secret-access-token".into()]
            ),
            "token:•••:uuid"
        );
    }
}
