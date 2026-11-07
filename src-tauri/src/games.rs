//! Running game processes: log buffering/batching, crash analysis, play time.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

use launcher_core::game::crash::{CrashInfo, analyze};
use launcher_core::game::launch::run;
use launcher_core::game::logs::{LogLevel, LogLine};
use launcher_core::instances::launch::LaunchPlan;
use launcher_core::settings::AfterLaunch;
use serde::Serialize;
use specta::Type;
use tauri::{AppHandle, Manager};
use tauri_specta::Event;
use tokio_util::sync::CancellationToken;

use crate::state::AppState;

const MAX_LINES: usize = 20_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub enum GameState {
    Running,
    Exited,
}

#[derive(Debug, Clone, Serialize, Type, Event)]
#[serde(rename_all = "camelCase")]
pub struct GameStateEvent {
    pub instance_id: String,
    pub state: GameState,
    pub exit_code: Option<i32>,
    pub crash: Option<CrashInfo>,
    pub play_secs: f64,
}

#[derive(Debug, Clone, Serialize, Type, Event)]
#[serde(rename_all = "camelCase")]
pub struct GameLogEvent {
    pub instance_id: String,
    pub lines: Vec<LogLine>,
}

#[derive(Debug, Clone, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub struct RunningGame {
    pub instance_id: String,
    pub name: String,
    pub started_at: f64,
    pub java: String,
    pub command: String,
}

struct Session {
    info: RunningGame,
    /// Process id once started, and the memory it may use (for the performance page).
    pid: Option<u32>,
    max_memory_mb: u32,
    stop: CancellationToken,
    logs: VecDeque<LogLine>,
}

#[derive(Clone, Default)]
pub struct GameRegistry {
    inner: Arc<Mutex<HashMap<String, Session>>>,
    /// Logs of the last finished run per instance, so the console survives the exit.
    last: Arc<Mutex<HashMap<String, VecDeque<LogLine>>>>,
}

impl GameRegistry {
    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, Session>> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn is_running(&self, id: &str) -> bool {
        self.lock().contains_key(id)
    }

    pub fn running(&self) -> Vec<RunningGame> {
        self.lock().values().map(|s| s.info.clone()).collect()
    }

    /// Running games with a known process: `(instance id, pid, max memory MB)`.
    pub fn processes(&self) -> Vec<(String, u32, u32)> {
        self.lock()
            .iter()
            .filter_map(|(id, s)| s.pid.map(|p| (id.clone(), p, s.max_memory_mb)))
            .collect()
    }

    pub fn stop(&self, id: &str) -> bool {
        self.lock().get(id).map(|s| s.stop.cancel()).is_some()
    }

    pub fn logs(&self, id: &str) -> Vec<LogLine> {
        if let Some(s) = self.lock().get(id) {
            return s.logs.iter().cloned().collect();
        }
        self.last
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(id)
            .map(|l| l.iter().cloned().collect())
            .unwrap_or_default()
    }

    pub fn clear_logs(&self, id: &str) {
        if let Some(s) = self.lock().get_mut(id) {
            s.logs.clear();
        }
        self.last
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(id);
    }
}

fn launcher_line(text: String) -> LogLine {
    LogLine {
        time: crate::tasks::now_ms(),
        level: LogLevel::Raw,
        thread: Some("Aerion".into()),
        logger: None,
        text,
    }
}

/// Start the game described by `plan` and supervise it until it exits.
pub fn start(app: AppHandle, plan: LaunchPlan, after_launch: AfterLaunch) {
    let state = app.state::<AppState>();
    let id = plan.instance.id.clone();
    let stop = CancellationToken::new();
    let header = vec![
        launcher_line(format!(
            "Aerion {} — {} ({})",
            env!("CARGO_PKG_VERSION"),
            plan.instance.name,
            plan.prepared.version.id
        )),
        launcher_line(format!(
            "Java {} ({}) — {}",
            plan.java.version,
            plan.java.arch,
            plan.java.path.display()
        )),
        launcher_line(format!("Memory: {} MB", plan.max_memory_mb)),
    ];
    let info = RunningGame {
        instance_id: id.clone(),
        name: plan.instance.name.clone(),
        started_at: crate::tasks::now_ms(),
        java: plan.java.path.display().to_string(),
        command: plan.spec.redacted(),
    };
    state.games.lock().insert(
        id.clone(),
        Session {
            info,
            pid: None,
            max_memory_mb: plan.max_memory_mb,
            stop: stop.clone(),
            logs: header.clone().into(),
        },
    );
    let _ = GameLogEvent {
        instance_id: id.clone(),
        lines: header,
    }
    .emit(&app);
    let _ = GameStateEvent {
        instance_id: id.clone(),
        state: GameState::Running,
        exit_code: None,
        crash: None,
        play_secs: 0.0,
    }
    .emit(&app);

    if let Some(w) = app.get_webview_window("main") {
        match after_launch {
            AfterLaunch::Minimize => {
                let _ = w.minimize();
            }
            // "Close" hides now and quits when the game exits cleanly, so play time and
            // crash analysis still work.
            AfterLaunch::Hide | AfterLaunch::Close => {
                let _ = w.hide();
            }
            AfterLaunch::Keep => {}
        }
    }

    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<LogLine>();
    let registry = state.games.clone();
    let app_logs = app.clone();
    let log_id = id.clone();
    // Batch log lines (~16 events/s at most) and keep a ring buffer for the console.
    let pump = tauri::async_runtime::spawn(async move {
        let mut batch: Vec<LogLine> = Vec::new();
        let mut tick = tokio::time::interval(Duration::from_millis(60));
        loop {
            tokio::select! {
                line = rx.recv() => match line {
                    Some(l) => {
                        if let Some(s) = registry.lock().get_mut(&log_id) {
                            if s.logs.len() >= MAX_LINES {
                                s.logs.pop_front();
                            }
                            s.logs.push_back(l.clone());
                        }
                        batch.push(l);
                    }
                    None => break,
                },
                _ = tick.tick() => {
                    if !batch.is_empty() {
                        let _ = GameLogEvent { instance_id: log_id.clone(), lines: std::mem::take(&mut batch) }.emit(&app_logs);
                    }
                }
            }
        }
        if !batch.is_empty() {
            let _ = GameLogEvent {
                instance_id: log_id.clone(),
                lines: batch,
            }
            .emit(&app_logs);
        }
    });

    let quit_after = after_launch == AfterLaunch::Close;
    let pid_registry = state.games.clone();
    let shares_options = plan.share_back;
    tauri::async_runtime::spawn(async move {
        let started = SystemTime::now();
        let game_dir = plan.game_dir.clone();
        let max_mb = plan.max_memory_mb;
        let pid_id = id.clone();
        // FPS and memory while it runs, for the summary on the performance page.
        let (pid_tx, pid_rx) = tokio::sync::watch::channel(None);
        let sampling = CancellationToken::new();
        let recorder = tauri::async_runtime::spawn(launcher_core::sessions::record(
            app.state::<AppState>().paths.clone(),
            id.clone(),
            pid_rx,
            sampling.clone(),
        ));
        let (name, loader) = (plan.instance.name.clone(), plan.instance.loader);
        let result = run(plan.spec, tx, stop, move |pid| {
            let _ = pid_tx.send(pid);
            if let Some(s) = pid_registry.lock().get_mut(&pid_id) {
                s.pid = pid;
            }
        })
        .await;
        sampling.cancel();
        let samples = recorder.await.unwrap_or_default();
        let _ = pump.await;
        let state = app.state::<AppState>();
        let session = state.games.lock().remove(&id);
        let tail: Vec<String> = session
            .as_ref()
            .map(|s| {
                s.logs
                    .iter()
                    .rev()
                    .take(400)
                    .rev()
                    .map(|l| l.text.clone())
                    .collect()
            })
            .unwrap_or_default();
        let (code, killed, secs) = match &result {
            Ok(exit) => (exit.code, exit.killed, exit.duration_secs),
            Err(e) => {
                tracing::error!(error = %e, "game failed to start");
                (None, false, 0)
            }
        };
        if shares_options {
            // Whatever the player changed in this game becomes the shared settings.
            let (dir, shared) = (game_dir.clone(), state.paths.shared_options_dir());
            let pushed = tokio::task::spawn_blocking(move || {
                launcher_core::instances::options::push_shared(&dir, &shared)
            })
            .await;
            if !matches!(pushed, Ok(Ok(_))) {
                tracing::warn!("couldn't keep this game's settings as the shared ones");
            }
        }
        let crash = if killed {
            None
        } else {
            analyze(code, &tail, &game_dir, started, max_mb)
        };
        let crash = match (&result, crash) {
            (Err(e), None) => {
                let mut params = std::collections::BTreeMap::new();
                params.insert("error".into(), e.to_string());
                Some(CrashInfo {
                    kind: "startFailed".into(),
                    params,
                    description: None,
                    report_path: None,
                    excerpt: e.to_string(),
                    exit_code: None,
                    mod_file: None,
                    mod_name: None,
                })
            }
            (_, c) => c,
        };
        if let Some(s) = session {
            state
                .games
                .last
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .insert(id.clone(), s.logs);
        }
        let started_secs = launcher_core::util::unix_secs(started);
        let _ = state.instances.record_play(&id, started_secs, secs).await;
        if crash.is_none() {
            let finished = launcher_core::sessions::Finished {
                instance_id: id.clone(),
                instance_name: name,
                game_dir: game_dir.clone(),
                loader,
                play_secs: secs,
                max_memory_mb: max_mb,
            };
            if let Err(e) =
                launcher_core::sessions::keep_summary(&state.paths, finished, &samples).await
            {
                tracing::warn!(error = %e, "couldn't keep the game's performance summary");
            }
        }
        let _ = GameStateEvent {
            instance_id: id,
            state: GameState::Exited,
            exit_code: code,
            crash: crash.clone(),
            play_secs: secs as f64,
        }
        .emit(&app);
        if quit_after && crash.is_none() && state.games.running().is_empty() {
            app.exit(0);
            return;
        }
        if crash.is_some() || after_launch != AfterLaunch::Keep {
            crate::show_main(&app);
        }
    });
}
