//! "What slows the game": samples taken while a game runs (FPS and heap from the Aerion mod,
//! the process's memory, free memory of the computer) turned into a short summary with hints
//! when it exits. The last summaries are kept for the performance page.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use sysinfo::{Pid, ProcessRefreshKind, ProcessesToUpdate, System};
use tokio_util::sync::CancellationToken;

use crate::loaders::LoaderKind;
use crate::paths::Paths;
use crate::util::fs::{read_json, write_json};

/// How often a running game is sampled.
const EVERY: Duration = Duration::from_secs(2);
/// Shorter games say too little to be worth a summary.
const MIN_SECS: u64 = 45;
const KEEP: usize = 20;
const MB: u64 = 1024 * 1024;

#[derive(Debug, Clone, Default)]
pub struct Sample {
    /// Only while a world is open (menus run at other frame rates).
    pub fps: Option<u32>,
    pub heap_mb: Option<u32>,
    pub heap_max_mb: Option<u32>,
    pub process_mb: u32,
    pub system_free_mb: u32,
    /// The mod's own processor time per frame, in a world (mods from 1.3 on).
    pub cost_ms: Option<f32>,
    pub cost_parts: BTreeMap<String, f32>,
    pub background_ms: Option<f32>,
}

/// One part of the mod and the time it took per frame, on average.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct CostPart {
    /// HUD module or feature id.
    pub id: String,
    pub ms: f32,
}

/// One tip, translated by the UI as `perf.hints.<kind>` with `params`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct Hint {
    pub kind: String,
    /// `warn` for something to fix, `info` for something to know.
    pub level: String,
    pub params: BTreeMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct SessionSummary {
    pub instance_id: String,
    pub instance_name: String,
    /// Unix seconds.
    pub ended: i64,
    pub play_secs: u64,
    pub fps_avg: Option<u32>,
    /// The worst 5 % of the samples: stutter shows here, not in the average.
    pub fps_low: Option<u32>,
    pub heap_peak_mb: Option<u32>,
    pub heap_avg_mb: Option<u32>,
    /// `-Xmx`, 0 when unknown (Horizon).
    pub max_memory_mb: u32,
    pub process_peak_mb: u32,
    pub system_total_mb: u32,
    pub system_free_min_mb: u32,
    /// What the Aerion mod itself took per frame on average (ms), when the mod reports it.
    #[serde(default)]
    pub mod_cost_ms: Option<f32>,
    /// Its biggest parts, largest first.
    #[serde(default)]
    pub mod_cost_parts: Vec<CostPart>,
    /// Time on the mod's own threads (ms per second).
    #[serde(default)]
    pub mod_background_ms: Option<f32>,
    pub hints: Vec<Hint>,
}

/// A game that just ended normally.
pub struct Finished {
    pub instance_id: String,
    pub instance_name: String,
    pub game_dir: PathBuf,
    pub loader: LoaderKind,
    pub play_secs: u64,
    /// `-Xmx`, 0 when unknown (Horizon).
    pub max_memory_mb: u32,
}

/// Summarize a finished game's samples and keep the summary (sessions too short to tell
/// anything are skipped).
pub async fn keep_summary(paths: &Paths, game: Finished, samples: &[Sample]) -> crate::Result<()> {
    let dir = game.game_dir.clone();
    let (has_sodium, render_distance) =
        crate::util::blocking(move || Ok((has_sodium(&dir), render_distance(&dir)))).await?;
    let ctx = Context {
        instance_id: game.instance_id,
        instance_name: game.instance_name,
        play_secs: game.play_secs,
        max_memory_mb: game.max_memory_mb,
        system_total_mb: crate::util::sys::total_memory_mb() as u32,
        has_sodium,
        render_distance,
        modded: game.loader.is_modded(),
    };
    match summarize(&ctx, samples, crate::util::now_secs()) {
        Some(summary) => add(paths, summary).await,
        None => Ok(()),
    }
}

/// What the summary needs to know besides the samples.
struct Context {
    pub instance_id: String,
    pub instance_name: String,
    pub play_secs: u64,
    pub max_memory_mb: u32,
    pub system_total_mb: u32,
    /// An optimisation mod (Sodium or a fork) is in the instance.
    pub has_sodium: bool,
    /// `renderDistance` from the game's options.
    pub render_distance: Option<u32>,
    pub modded: bool,
}

fn hint(kind: &str, level: &str, params: &[(&str, String)]) -> Hint {
    Hint {
        kind: kind.into(),
        level: level.into(),
        params: params
            .iter()
            .map(|(k, v)| ((*k).to_owned(), v.clone()))
            .collect(),
    }
}

/// Round up to whole gigabytes (what the memory slider steps by).
fn whole_gb(mb: u32) -> u32 {
    mb.div_ceil(1024) * 1024
}

fn avg(v: &[u32]) -> Option<u32> {
    (!v.is_empty()).then(|| (v.iter().map(|&x| u64::from(x)).sum::<u64>() / v.len() as u64) as u32)
}

fn summarize(ctx: &Context, samples: &[Sample], ended: i64) -> Option<SessionSummary> {
    if samples.is_empty() || ctx.play_secs < MIN_SECS {
        return None;
    }
    let mut fps: Vec<u32> = samples.iter().filter_map(|s| s.fps).collect();
    fps.sort_unstable();
    let fps_avg = avg(&fps);
    let fps_low = (!fps.is_empty()).then(|| fps[(fps.len() - 1) * 5 / 100]);
    let heaps: Vec<u32> = samples.iter().filter_map(|s| s.heap_mb).collect();
    let heap_peak = heaps.iter().copied().max();
    // The mod knows the real heap limit; the launcher's value is the fallback.
    let max_mb = samples
        .iter()
        .find_map(|s| s.heap_max_mb)
        .filter(|&m| m > 0)
        .unwrap_or(ctx.max_memory_mb);
    let free_min = samples.iter().map(|s| s.system_free_mb).min().unwrap_or(0);
    let (cost, cost_parts, background) = mod_cost(samples);

    let mut hints = Vec::new();
    if let Some(peak) = heap_peak
        && max_mb > 0
    {
        let suggest = whole_gb(peak.saturating_mul(3) / 2).min(whole_gb(ctx.system_total_mb / 2));
        if peak * 100 >= max_mb * 90 && suggest > max_mb {
            hints.push(hint(
                "lowMemory",
                "warn",
                &[
                    ("peak", peak.to_string()),
                    ("max", max_mb.to_string()),
                    ("suggest", suggest.to_string()),
                ],
            ));
        } else if max_mb >= 6144 && peak * 100 < max_mb * 40 {
            let suggest = whole_gb(peak * 2).max(if ctx.modded { 4096 } else { 2048 });
            if suggest < max_mb {
                hints.push(hint(
                    "tooMuchMemory",
                    "info",
                    &[
                        ("peak", peak.to_string()),
                        ("max", max_mb.to_string()),
                        ("suggest", suggest.to_string()),
                    ],
                ));
            }
        }
    }
    if ctx.system_total_mb > 0 && free_min * 100 < ctx.system_total_mb * 8 {
        hints.push(hint(
            "systemMemory",
            "warn",
            &[("free", free_min.to_string())],
        ));
    }
    if let (Some(a), Some(low)) = (fps_avg, fps_low) {
        if a < 50 && !ctx.has_sodium && ctx.modded {
            hints.push(hint("noSodium", "warn", &[("fps", a.to_string())]));
        }
        if a < 50
            && let Some(rd) = ctx.render_distance.filter(|&rd| rd > 12)
        {
            hints.push(hint(
                "renderDistance",
                "warn",
                &[("now", rd.to_string()), ("fps", a.to_string())],
            ));
        }
        if a >= 30 && low * 100 < a * 40 {
            hints.push(hint(
                "stutter",
                "warn",
                &[("low", low.to_string()), ("avg", a.to_string())],
            ));
        }
    }
    // The mod's share of each frame: worth a word when it is both noticeable and a real part.
    if let (Some(c), Some(a)) = (cost, fps_avg)
        && a > 0
        && c >= 2.0
        && c * a as f32 >= 100.0
    {
        let mut params = vec![
            ("ms", format!("{c:.1}")),
            ("share", format!("{:.0}", c * a as f32 / 10.0)),
        ];
        if let Some(top) = cost_parts.first() {
            params.push(("part", top.id.clone()));
        }
        hints.push(hint("aerionCost", "warn", &params));
    }
    if hints.is_empty() && fps_avg.is_some() {
        hints.push(hint("allGood", "ok", &[]));
    }

    Some(SessionSummary {
        instance_id: ctx.instance_id.clone(),
        instance_name: ctx.instance_name.clone(),
        ended,
        play_secs: ctx.play_secs,
        fps_avg,
        fps_low,
        heap_peak_mb: heap_peak,
        heap_avg_mb: avg(&heaps),
        max_memory_mb: max_mb,
        process_peak_mb: samples.iter().map(|s| s.process_mb).max().unwrap_or(0),
        system_total_mb: ctx.system_total_mb,
        system_free_min_mb: free_min,
        mod_cost_ms: cost,
        mod_cost_parts: cost_parts,
        mod_background_ms: background,
        hints,
    })
}

/// The mod's average cost per frame, its five biggest parts and its background time, from the
/// samples taken in a world where the mod reported them.
fn mod_cost(samples: &[Sample]) -> (Option<f32>, Vec<CostPart>, Option<f32>) {
    let measured: Vec<&Sample> = samples
        .iter()
        .filter(|s| s.fps.is_some() && s.cost_ms.is_some())
        .collect();
    if measured.is_empty() {
        return (None, Vec::new(), None);
    }
    let n = measured.len() as f32;
    let cost = measured.iter().filter_map(|s| s.cost_ms).sum::<f32>() / n;
    let background = measured.iter().filter_map(|s| s.background_ms).sum::<f32>() / n;
    let mut totals: BTreeMap<&str, f32> = BTreeMap::new();
    for s in &measured {
        for (id, ms) in &s.cost_parts {
            *totals.entry(id).or_default() += ms;
        }
    }
    let mut parts: Vec<CostPart> = totals
        .into_iter()
        .map(|(id, ms)| CostPart {
            id: id.to_owned(),
            ms: ms / n,
        })
        .filter(|p| p.ms >= 0.005)
        .collect();
    parts.sort_by(|a, b| b.ms.total_cmp(&a.ms));
    parts.truncate(5);
    (Some(cost), parts, Some(background))
}

/// Sample the game with process id `pid` until `done` is cancelled.
pub async fn record(
    paths: Paths,
    instance_id: String,
    mut pid: tokio::sync::watch::Receiver<Option<u32>>,
    done: CancellationToken,
) -> Vec<Sample> {
    let pid = loop {
        if let Some(p) = *pid.borrow() {
            break Pid::from_u32(p);
        }
        tokio::select! {
            changed = pid.changed() => if changed.is_err() { return Vec::new() },
            _ = done.cancelled() => return Vec::new(),
        }
    };
    let Some(mut sys) = System::new().ok() else {
        return Vec::new();
    };
    let mut out = Vec::new();
    let mut tick = tokio::time::interval(EVERY);
    loop {
        tokio::select! {
            _ = tick.tick() => {}
            _ = done.cancelled() => break,
        }
        sys.refresh_memory();
        sys.refresh_processes_specifics(
            ProcessesToUpdate::Some(&[pid]),
            true,
            ProcessRefreshKind::nothing().with_memory(),
        );
        let Some(process) = sys.process(pid) else {
            continue;
        };
        let status = crate::aerion_mod::read_status(&paths, &instance_id);
        out.push(Sample {
            fps: status
                .as_ref()
                .filter(|s| s.in_world.unwrap_or(true))
                .map(|s| s.fps),
            heap_mb: status.as_ref().and_then(|s| s.heap_mb),
            heap_max_mb: status.as_ref().and_then(|s| s.heap_max_mb),
            process_mb: (process.memory() / MB) as u32,
            system_free_mb: (sys.available_memory() / MB) as u32,
            cost_ms: status.as_ref().and_then(|s| s.cost_ms),
            cost_parts: status
                .as_ref()
                .map(|s| s.cost_parts.clone())
                .unwrap_or_default(),
            background_ms: status.as_ref().and_then(|s| s.background_ms),
        });
        // A day of samples is plenty; keep memory bounded on very long sessions.
        if out.len() > 43_200 {
            out.drain(..out.len() / 2);
        }
    }
    out
}

/// Sodium or one of its forks in the instance's mods folder.
fn has_sodium(game_dir: &Path) -> bool {
    std::fs::read_dir(game_dir.join("mods"))
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| e.file_name().to_str().map(str::to_ascii_lowercase))
        .any(|n| n.ends_with(".jar") && (n.contains("sodium") || n.contains("embeddium")))
}

fn render_distance(game_dir: &Path) -> Option<u32> {
    crate::instances::options::value(game_dir, "renderDistance")?
        .trim()
        .parse()
        .ok()
}

/// Newest first.
pub async fn load(paths: &Paths) -> Vec<SessionSummary> {
    read_json(paths.sessions_file())
        .await
        .ok()
        .flatten()
        .unwrap_or_default()
}

async fn add(paths: &Paths, s: SessionSummary) -> crate::Result<()> {
    let mut all = load(paths).await;
    all.insert(0, s);
    all.truncate(KEEP);
    write_json(paths.sessions_file(), &all).await
}

pub async fn clear(paths: &Paths) -> crate::Result<()> {
    write_json(paths.sessions_file(), &Vec::<SessionSummary>::new()).await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> Context {
        Context {
            instance_id: "i".into(),
            instance_name: "I".into(),
            play_secs: 600,
            max_memory_mb: 2048,
            system_total_mb: 16384,
            has_sodium: true,
            render_distance: Some(10),
            modded: true,
        }
    }
    fn s(fps: u32, heap: u32) -> Sample {
        Sample {
            fps: Some(fps),
            heap_mb: Some(heap),
            heap_max_mb: Some(2048),
            process_mb: 3000,
            system_free_mb: 8000,
            ..Sample::default()
        }
    }
    fn kinds(sum: &SessionSummary) -> Vec<&str> {
        sum.hints.iter().map(|h| h.kind.as_str()).collect()
    }

    #[test]
    fn a_full_heap_asks_for_more_memory() {
        let samples: Vec<Sample> = (0..100).map(|i| s(90, 1200 + i * 8)).collect();
        let sum = summarize(&ctx(), &samples, 0).unwrap();
        assert_eq!(sum.heap_peak_mb, Some(1992));
        assert_eq!(kinds(&sum), ["lowMemory"]);
        assert_eq!(sum.hints[0].params["suggest"], "3072");
    }

    #[test]
    fn healthy_games_get_a_thumbs_up_and_short_ones_nothing() {
        let samples: Vec<Sample> = (0..100).map(|_| s(120, 900)).collect();
        assert_eq!(kinds(&summarize(&ctx(), &samples, 0).unwrap()), ["allGood"]);
        let short = Context {
            play_secs: 10,
            ..ctx()
        };
        assert!(summarize(&short, &samples, 0).is_none());
    }

    #[test]
    fn slow_games_get_the_likely_causes() {
        let mut samples: Vec<Sample> = (0..100).map(|_| s(40, 900)).collect();
        for x in samples.iter_mut().take(10) {
            x.fps = Some(8);
        }
        let c = Context {
            has_sodium: false,
            render_distance: Some(24),
            max_memory_mb: 8192,
            ..ctx()
        };
        for x in &mut samples {
            x.heap_max_mb = None;
        }
        let sum = summarize(&c, &samples, 0).unwrap();
        assert_eq!(
            kinds(&sum),
            ["tooMuchMemory", "noSodium", "renderDistance", "stutter"]
        );
        assert_eq!(sum.fps_low, Some(8));
    }

    #[test]
    fn menus_and_old_mods_give_no_fps_hints() {
        let samples: Vec<Sample> = (0..50)
            .map(|_| Sample {
                process_mb: 2000,
                system_free_mb: 500,
                ..Sample::default()
            })
            .collect();
        let sum = summarize(&ctx(), &samples, 0).unwrap();
        assert_eq!(sum.fps_avg, None);
        assert_eq!(kinds(&sum), ["systemMemory"]);
    }

    #[test]
    fn a_costly_mod_part_is_named() {
        let cheap: Vec<Sample> = (0..100)
            .map(|_| Sample {
                cost_ms: Some(0.4),
                cost_parts: BTreeMap::from([("fps".to_owned(), 0.3), ("clock".to_owned(), 0.1)]),
                background_ms: Some(2.0),
                ..s(120, 900)
            })
            .collect();
        let sum = summarize(&ctx(), &cheap, 0).unwrap();
        assert_eq!(kinds(&sum), ["allGood"]);
        assert_eq!(sum.mod_cost_parts[0].id, "fps");
        assert!((sum.mod_cost_ms.unwrap() - 0.4).abs() < 1e-4);

        let costly: Vec<Sample> = (0..100)
            .map(|_| Sample {
                cost_ms: Some(3.0),
                cost_parts: BTreeMap::from([("mirrors".to_owned(), 2.6), ("fps".to_owned(), 0.4)]),
                ..s(60, 900)
            })
            .collect();
        let sum = summarize(&ctx(), &costly, 0).unwrap();
        assert_eq!(kinds(&sum), ["aerionCost"]);
        assert_eq!(sum.hints[0].params["part"], "mirrors");
        assert_eq!(sum.hints[0].params["share"], "18");
    }

    #[test]
    fn reads_render_distance_and_sodium() {
        let d = tempfile::tempdir().unwrap();
        std::fs::write(d.path().join("options.txt"), "fov:0.0\nrenderDistance:16\n").unwrap();
        assert_eq!(render_distance(d.path()), Some(16));
        assert!(!has_sodium(d.path()));
        std::fs::create_dir(d.path().join("mods")).unwrap();
        std::fs::write(d.path().join("mods/Sodium-0.8.jar"), b"").unwrap();
        assert!(has_sodium(d.path()));
    }
}
