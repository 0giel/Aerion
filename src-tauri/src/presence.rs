//! "Playing Aerion: Minecraft 26.3" on the player's Discord profile while a game runs (Settings:
//! on unless switched off). Talks to the Discord app on this computer over its local socket (a
//! named pipe on Windows); nothing goes over the internet from here, and nothing happens when
//! Discord isn't running. The socket is looked for in the places the Discord apps use, the
//! Flatpak and Snap ones included.
use std::io::{Read, Write};
use std::path::PathBuf;
use std::time::Duration;

use serde_json::{Value, json};
use tauri::{AppHandle, Manager};

use crate::state::AppState;

/// The Discord application "Aerion" (the project's bot), whose name Discord shows.
const APP_ID: &str = "1568736477309837332";
const SITE: &str = "https://0giel.github.io/Aerion/";
const ICON: &str = "https://0giel.github.io/Aerion/assets/icon.png";

#[cfg(unix)]
type Socket = std::os::unix::net::UnixStream;
#[cfg(windows)]
type Socket = std::fs::File;

/// Where a Discord app may listen, most likely first.
#[cfg(unix)]
fn candidates() -> Vec<PathBuf> {
    let mut bases: Vec<PathBuf> = ["XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"]
        .iter()
        .filter_map(|k| std::env::var_os(k).map(PathBuf::from))
        .collect();
    bases.push(PathBuf::from("/tmp"));
    let subs = [
        "",
        "app/com.discordapp.Discord",
        ".flatpak/com.discordapp.Discord/xdg-run",
        "app/com.discordapp.DiscordCanary",
        ".flatpak/com.discordapp.DiscordCanary/xdg-run",
        ".flatpak/dev.vencord.Vesktop/xdg-run",
        "snap.discord",
        "snap.discord-canary",
    ];
    let mut out = Vec::new();
    for i in 0..10 {
        for base in &bases {
            for sub in subs {
                out.push(base.join(sub).join(format!("discord-ipc-{i}")));
            }
        }
    }
    out
}

#[cfg(unix)]
fn open() -> Option<Socket> {
    candidates().into_iter().find_map(|p| {
        let s = Socket::connect(p).ok()?;
        let _ = s.set_read_timeout(Some(Duration::from_secs(3)));
        Some(s)
    })
}

#[cfg(windows)]
fn open() -> Option<Socket> {
    (0..10).find_map(|i| {
        std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(format!(r"\\.\pipe\discord-ipc-{i}"))
            .ok()
    })
}

/// One message: opcode and length (little-endian), then JSON. Discord answers each one.
fn send(socket: &mut Socket, op: u32, body: &Value) -> std::io::Result<Value> {
    let data = body.to_string();
    let mut frame = Vec::with_capacity(8 + data.len());
    frame.extend_from_slice(&op.to_le_bytes());
    frame.extend_from_slice(&(data.len() as u32).to_le_bytes());
    frame.extend_from_slice(data.as_bytes());
    socket.write_all(&frame)?;
    let mut head = [0u8; 8];
    socket.read_exact(&mut head)?;
    let len = u32::from_le_bytes([head[4], head[5], head[6], head[7]]) as usize;
    if len > 64 * 1024 {
        return Err(std::io::Error::other("answer too large"));
    }
    let mut answer = vec![0u8; len];
    socket.read_exact(&mut answer)?;
    Ok(serde_json::from_slice(&answer).unwrap_or(Value::Null))
}

fn connect() -> Option<Socket> {
    let mut socket = open()?;
    let ready = send(&mut socket, 0, &json!({ "v": 1, "client_id": APP_ID })).ok()?;
    (ready["evt"] == "READY").then_some(socket)
}

fn set_activity(socket: &mut Socket, activity: Value) -> std::io::Result<()> {
    let nonce = uuid::Uuid::new_v4().to_string();
    let body = json!({ "cmd": "SET_ACTIVITY", "args": { "pid": std::process::id(), "activity": activity }, "nonce": nonce });
    let answer = send(socket, 1, &body)?;
    if answer["evt"] == "ERROR" {
        return Err(std::io::Error::other(answer["data"]["message"].to_string()));
    }
    Ok(())
}

pub fn start(app: AppHandle) {
    std::thread::spawn(move || {
        let mut socket: Option<Socket> = None;
        let mut shown: Option<String> = None;
        loop {
            std::thread::sleep(Duration::from_secs(10));
            let state = app.state::<AppState>();
            let enabled = state.settings.blocking_read().discord_presence;
            let game = enabled
                .then(|| state.games.running().into_iter().next())
                .flatten();
            let key = game
                .as_ref()
                .map(|g| format!("{}|{}", g.instance_id, g.started_at));
            if key == shown {
                continue;
            }
            if socket.is_none() {
                socket = connect();
            }
            let Some(s) = socket.as_mut() else { continue };
            let activity = match &game {
                Some(g) => {
                    let inst =
                        tauri::async_runtime::block_on(state.instances.get(&g.instance_id)).ok();
                    let details = inst.map_or_else(
                        || "Minecraft".to_owned(),
                        |i| format!("Minecraft {} \u{b7} {:?}", i.game_version, i.loader),
                    );
                    json!({
                        "details": details,
                        "timestamps": { "start": (g.started_at / 1000.0) as i64 },
                        "assets": { "large_image": ICON, "large_text": "Aerion" },
                        "buttons": [{ "label": "Aerion", "url": SITE }],
                    })
                }
                None => Value::Null,
            };
            match set_activity(s, activity) {
                Ok(()) => shown = key,
                // Discord closed or restarted: connect again next time.
                Err(e) => {
                    tracing::debug!(error = %e, "Discord status not updated");
                    socket = None;
                }
            }
        }
    });
}

#[cfg(all(test, unix))]
mod tests {
    #[test]
    fn looks_in_the_flatpak_folder_too() {
        let all = super::candidates();
        assert!(
            all.iter()
                .any(|p| p.ends_with(".flatpak/com.discordapp.Discord/xdg-run/discord-ipc-0"))
        );
        assert!(all.iter().any(|p| p.ends_with("discord-ipc-0")));
    }

    /// Against the Discord app running here: connects and clears (shows nothing to anyone).
    /// `cargo test -p aerion discord_app -- --ignored`
    #[test]
    #[ignore]
    fn discord_app_answers() {
        let mut socket = super::connect().expect("Discord answered the handshake");
        super::set_activity(&mut socket, serde_json::Value::Null).expect("clearing the status");
    }
}
