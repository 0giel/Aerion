//! "Playing Aerion: Minecraft 26.3" on the player's Discord profile while a game runs (Settings:
//! on unless switched off). Talks to the Discord app on this computer over its local socket;
//! nothing goes over the internet from here, and nothing happens when Discord isn't running.
use std::time::Duration;

use discord_rich_presence::{DiscordIpc, DiscordIpcClient, activity};
use tauri::{AppHandle, Manager};

use crate::state::AppState;

/// The Discord application "Aerion" (the project's bot), whose name Discord shows.
const APP_ID: &str = "1568736477309837332";
const SITE: &str = "https://0giel.github.io/Aerion/";
const ICON: &str = "https://0giel.github.io/Aerion/assets/icon.png";

pub fn start(app: AppHandle) {
    std::thread::spawn(move || {
        let mut client: Option<DiscordIpcClient> = None;
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
            if client.is_none() {
                let Ok(mut c) = DiscordIpcClient::new(APP_ID) else {
                    continue;
                };
                if c.connect().is_err() {
                    continue;
                }
                client = Some(c);
            }
            let Some(c) = client.as_mut() else { continue };
            let result = match &game {
                Some(g) => {
                    let inst =
                        tauri::async_runtime::block_on(state.instances.get(&g.instance_id)).ok();
                    let details = inst.map_or_else(
                        || "Minecraft".to_owned(),
                        |i| format!("Minecraft {} \u{b7} {:?}", i.game_version, i.loader),
                    );
                    let activity = activity::Activity::new()
                        .details(&details)
                        .timestamps(
                            activity::Timestamps::new().start((g.started_at / 1000.0) as i64),
                        )
                        .assets(
                            activity::Assets::new()
                                .large_image(ICON)
                                .large_text("Aerion"),
                        )
                        .buttons(vec![activity::Button::new("Aerion", SITE)]);
                    c.set_activity(activity)
                }
                None => c.clear_activity(),
            };
            match result {
                Ok(()) => shown = key,
                // Discord closed or restarted: connect again next time.
                Err(e) => {
                    tracing::debug!(error = %e, "Discord status not updated");
                    let _ = c.close();
                    client = None;
                }
            }
        }
    });
}
