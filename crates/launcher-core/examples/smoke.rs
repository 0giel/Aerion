//! End-to-end smoke test of the vanilla pipeline (developer tool).
//!
//! `cargo run -p launcher-core --example smoke -- <version> [<game dir>]`
//!
//! Installs the version + its Java runtime, launches it with an offline dev profile, waits
//! until the title screen has loaded (sound engine / texture atlas lines), then stops the
//! game. Exit code 0 = the version reached the title screen.

use std::path::PathBuf;
use std::time::{Duration, Instant};

use launcher_core::game::install::prepare;
use launcher_core::game::launch::{AuthInfo, LaunchOptions, build_command, prepare_game_dir, run};
use launcher_core::java::flags::JvmPreset;
use launcher_core::net::download::VerifyMode;
use launcher_core::paths::Paths;
use launcher_core::settings::Settings;
use launcher_core::{Engine, TaskCtx};
use tokio_util::sync::CancellationToken;

#[tokio::main]
async fn main() {
    let mut args = std::env::args().skip(1);
    let version = args.next().unwrap_or_else(|| "26.3".into());
    let paths = Paths::detect().expect("paths");
    let game_dir = args
        .next()
        .map(PathBuf::from)
        .unwrap_or_else(|| paths.data.join("smoke").join(&version));
    let settings = Settings::default();
    let engine = Engine::new(paths.clone(), &settings).expect("engine");
    let ctx = TaskCtx::new();

    let progress = ctx.progress.clone();
    let ticker = tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(2)).await;
            let s = progress.snapshot();
            eprintln!(
                "[progress] {} {:?}: {}/{} files, {:.1}/{:.1} MB",
                s.stage,
                s.detail,
                s.done_items,
                s.total_items,
                s.done_bytes as f64 / 1e6,
                s.total_bytes as f64 / 1e6
            );
        }
    });

    let t = Instant::now();
    let prepared = prepare(&engine.meta, &engine.env, &version, VerifyMode::Fast, &ctx)
        .await
        .expect("prepare");
    eprintln!(
        "[smoke] prepared {version} in {:.1}s (java {} via {})",
        t.elapsed().as_secs_f32(),
        prepared.java.major_version,
        prepared.java.component
    );
    let java = engine
        .runtimes()
        .ensure(&prepared.java.component, prepared.java.major_version, &ctx)
        .await
        .expect("java");
    ticker.abort();
    eprintln!(
        "[smoke] java {} {} at {}",
        java.version,
        java.arch,
        java.path.display()
    );
    prepare_game_dir(&prepared, &game_dir)
        .await
        .expect("game dir");

    let opts = LaunchOptions {
        game_dir: game_dir.clone(),
        java: java.path.clone(),
        java_major: java.major,
        max_memory_mb: 3072,
        min_memory_mb: None,
        preset: JvmPreset::Standard,
        extra_jvm_args: vec![],
        extra_game_args: vec![],
        resolution: Some((854, 480)),
        quick_play_server: None,
        env: vec![],
        wrapper: vec![],
        auth: AuthInfo {
            name: "LuncherDev".into(),
            uuid: "7f3b3f2b2c6c3d6e9a1e6f4f0c1d2e3f".into(),
            access_token: "0".into(),
            xuid: "0".into(),
            user_type: "legacy".into(),
        },
        client_id: settings.client_id.clone(),
    };
    let spec = build_command(&prepared, &opts, &paths, &engine.env);
    eprintln!("[smoke] {}", spec.redacted());

    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
    let stop = CancellationToken::new();
    let stop2 = stop.clone();
    let game = tokio::spawn(run(spec, tx, stop.clone()));
    let started = Instant::now();
    let mut ready = false;
    let deadline = Duration::from_secs(240);
    loop {
        tokio::select! {
            line = rx.recv() => {
                let Some(l) = line else { break };
                println!("{:?} [{}] {}", l.level, l.thread.clone().unwrap_or_default(), l.text);
                let t = &l.text;
                // The title screen has loaded once the sound engine is up or the texture atlases exist
                // (old OpenAL builds may fail to open audio on some Linux setups).
                if !ready && (t.contains("Sound engine started") || t.contains("SoundSystem started") || (t.starts_with("Created: ") && t.contains("atlas"))) {
                    ready = true;
                    eprintln!("[smoke] READY after {:.1}s", started.elapsed().as_secs_f32());
                    let s = stop2.clone();
                    tokio::spawn(async move { tokio::time::sleep(Duration::from_secs(6)).await; s.cancel(); });
                }
            }
            _ = tokio::time::sleep_until((started + deadline).into()) => { eprintln!("[smoke] timeout"); stop.cancel(); }
        }
    }
    let exit = game.await.expect("join").expect("run");
    eprintln!(
        "[smoke] exit {:?} killed={} after {}s",
        exit.code, exit.killed, exit.duration_secs
    );
    std::process::exit(if ready { 0 } else { 1 });
}
