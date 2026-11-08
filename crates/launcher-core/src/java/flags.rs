//! JVM flag presets (per Java major version) and validation of user arguments.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, Mutex};
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub enum JvmPreset {
    /// The official launcher's G1 settings.
    #[default]
    Standard,
    /// Low-pause tuning: generational ZGC on Java 21+ with enough heap, tuned G1 otherwise.
    Performance,
    /// Smallest footprint: returns memory to the OS, compact headers on Java 25+.
    LowMemory,
}

fn s(v: &[&str]) -> Vec<String> {
    v.iter().map(|x| (*x).to_owned()).collect()
}

/// Flags for `preset` on Java `major` with a maximum heap of `max_mb`. Every flag used here
/// exists (and is not experimental-locked) on the Java versions it is emitted for.
pub fn preset_args(preset: JvmPreset, major: u32, max_mb: u32) -> Vec<String> {
    let mut a = match preset {
        JvmPreset::Standard => s(&[
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseG1GC",
            "-XX:G1NewSizePercent=20",
            "-XX:G1ReservePercent=20",
            "-XX:MaxGCPauseMillis=50",
            "-XX:G1HeapRegionSize=32M",
        ]),
        JvmPreset::Performance if major >= 21 && max_mb >= 4096 => {
            let mut v = s(&["-XX:+UseZGC"]);
            // Generational mode is opt-in on 21–22 and the only mode from 23 (flag obsolete in 24+).
            if major < 23 {
                v.push("-XX:+ZGenerational".into());
            }
            v.extend(s(&["-XX:+DisableExplicitGC", "-XX:+PerfDisableSharedMem"]));
            v
        }
        JvmPreset::Performance => {
            let region = if max_mb >= 8192 { "32M" } else { "16M" };
            let mut v = s(&[
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseG1GC",
                "-XX:+ParallelRefProcEnabled",
                "-XX:MaxGCPauseMillis=50",
                "-XX:+DisableExplicitGC",
                "-XX:G1NewSizePercent=30",
                "-XX:G1MaxNewSizePercent=40",
                "-XX:G1ReservePercent=20",
                "-XX:G1HeapWastePercent=5",
                "-XX:G1MixedGCCountTarget=4",
                "-XX:InitiatingHeapOccupancyPercent=15",
                "-XX:G1MixedGCLiveThresholdPercent=90",
                "-XX:SurvivorRatio=32",
                "-XX:+PerfDisableSharedMem",
                "-XX:MaxTenuringThreshold=1",
            ]);
            v.push(format!("-XX:G1HeapRegionSize={region}"));
            v
        }
        JvmPreset::LowMemory => {
            let mut v = s(&[
                "-XX:+UseG1GC",
                "-XX:MaxGCPauseMillis=100",
                "-XX:+UseStringDeduplication",
                "-XX:MinHeapFreeRatio=10",
                "-XX:MaxHeapFreeRatio=30",
            ]);
            if major >= 12 {
                v.push("-XX:G1PeriodicGCInterval=15000".into());
            }
            v
        }
    };
    // Compact object headers are a product feature from JDK 25 (JEP 519).
    if major >= 25 && preset != JvmPreset::Standard {
        a.push("-XX:+UseCompactObjectHeaders".into());
    }
    a
}

pub fn memory_args(preset: JvmPreset, min_mb: Option<u32>, max_mb: u32) -> Vec<String> {
    let min = min_mb.unwrap_or(match preset {
        JvmPreset::Performance => max_mb / 2,
        JvmPreset::LowMemory => 256,
        JvmPreset::Standard => 512,
    });
    vec![
        format!("-Xms{}M", min.min(max_mb)),
        format!("-Xmx{max_mb}M"),
    ]
}

/// Default maximum heap derived from installed RAM.
pub fn default_max_memory_mb(total_mb: u64, modded: bool) -> u32 {
    let v = if modded {
        (total_mb / 3).clamp(2048, 6144)
    } else {
        (total_mb / 4).clamp(1536, 4096)
    };
    ((v / 512) * 512) as u32
}

/// What the computer offers; the input of [`auto_plan`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Hardware {
    pub total_mb: u64,
    pub cores: usize,
}

/// The automatic choice of heap size and GC preset for one launch.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct AutoPlan {
    pub max_mb: u32,
    /// The concrete preset the flags are built from.
    pub preset: JvmPreset,
    pub total_mb: u32,
    pub cores: u32,
    pub mods: u32,
    pub java_major: u32,
}

/// Heap and GC picked from the computer and the instance. Memory follows what the instance
/// needs (vanilla ~3 GB, a modded one ~4 GB plus a little for every mod over 40, up to 8 GB),
/// capped at half the installed RAM and always leaving the system 1.5 GB; free memory at launch
/// is deliberately not used, so the same instance gets the same settings every time. The GC:
/// low-pause ZGC only with ≥ 6 cores and ≥ 12 GB (the Performance preset turns it into tuned G1
/// on Java under 21), the low-memory preset on small machines, the standard one otherwise.
pub fn auto_plan(hw: Hardware, java_major: u32, modded: bool, mod_count: u32) -> AutoPlan {
    let need: u64 = if modded {
        4096 + u64::from(mod_count.saturating_sub(40).min(160)) * 24
    } else {
        3072
    };
    let cap = (hw.total_mb / 2).min(hw.total_mb.saturating_sub(1536));
    let max = need.min(cap).clamp(1536, 8192);
    let max_mb = ((max / 512) * 512) as u32;
    let preset = if hw.total_mb < 6144 || hw.cores <= 2 {
        JvmPreset::LowMemory
    } else if hw.cores >= 6 && hw.total_mb >= 12288 {
        JvmPreset::Performance
    } else {
        JvmPreset::Standard
    };
    AutoPlan {
        max_mb,
        preset,
        total_mb: hw.total_mb as u32,
        cores: hw.cores as u32,
        mods: mod_count,
        java_major,
    }
}

/// The Java major version a game version usually needs, for previews before one is picked.
pub fn typical_java_major(game_version: &str) -> u32 {
    let mut parts = game_version
        .split(['.', '-'])
        .map(|p| p.parse::<u32>().unwrap_or(0));
    let first = parts.next().unwrap_or(0);
    let second = parts.next().unwrap_or(0);
    let third = parts.next().unwrap_or(0);
    match (first, second) {
        (26.., _) => 25,
        (1, 21..) => 21,
        (1, 20) if third >= 5 => 21,
        (1, 17..) => 17,
        (1, _) => 8,
        _ => 21,
    }
}

/// Cheap syntactic checks on user-provided JVM arguments.
pub fn validate_syntax(args: &[String]) -> Result<()> {
    let mut bad = Vec::new();
    for a in args {
        let lower = a.to_ascii_lowercase();
        let reserved = ["-cp", "-classpath", "--class-path", "-jar"].contains(&lower.as_str());
        if a.is_empty() || !a.starts_with('-') || a.contains(['\0', '\n', '\r']) || reserved {
            bad.push(a.clone());
        }
    }
    if bad.is_empty() {
        Ok(())
    } else {
        Err(Error::InvalidJvmArgs(bad.join(" ")))
    }
}

/// `(java, args)` → `None` when accepted, `Some(error)` otherwise.
type ProbeCache = HashMap<(PathBuf, Vec<String>), Option<String>>;

static PROBE_CACHE: LazyLock<Mutex<ProbeCache>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// Ask the JVM itself whether it accepts `args` (`java <args> -version`), cached per Java.
pub async fn probe_args(java: &Path, args: &[String]) -> Result<()> {
    validate_syntax(args)?;
    if args.is_empty() {
        return Ok(());
    }
    let key = (java.to_path_buf(), args.to_vec());
    if let Some(cached) = PROBE_CACHE
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&key)
        .cloned()
    {
        return cached.map_or(Ok(()), |e| Err(Error::InvalidJvmArgs(e)));
    }
    let argv: Vec<&std::ffi::OsStr> = args
        .iter()
        .map(|a| a.as_ref())
        .chain(["-version".as_ref()])
        .collect();
    let out = super::run(java, &argv, Duration::from_secs(15)).await?;
    let verdict = if out.status.success() {
        None
    } else {
        let text = String::from_utf8_lossy(&out.stderr);
        let lines: Vec<&str> = text
            .lines()
            .filter(|l| {
                l.contains("Unrecognized") || l.contains("Improperly") || l.starts_with("Error")
            })
            .take(5)
            .collect();
        Some(if lines.is_empty() {
            text.trim().chars().take(400).collect()
        } else {
            lines.join("; ")
        })
    };
    PROBE_CACHE
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(key, verdict.clone());
    verdict.map_or(Ok(()), |e| Err(Error::InvalidJvmArgs(e)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn presets_follow_java_version() {
        let p21 = preset_args(JvmPreset::Performance, 21, 6144);
        assert!(
            p21.contains(&"-XX:+UseZGC".to_owned())
                && p21.contains(&"-XX:+ZGenerational".to_owned())
        );
        let p25 = preset_args(JvmPreset::Performance, 25, 6144);
        assert!(
            !p25.contains(&"-XX:+ZGenerational".to_owned()),
            "obsolete on 24+"
        );
        assert!(p25.contains(&"-XX:+UseCompactObjectHeaders".to_owned()));
        let p8 = preset_args(JvmPreset::Performance, 8, 4096);
        assert!(p8.contains(&"-XX:+UseG1GC".to_owned()) && !p8.iter().any(|f| f.contains("ZGC")));
        let low8 = preset_args(JvmPreset::LowMemory, 8, 2048);
        assert!(
            !low8.iter().any(|f| f.contains("G1PeriodicGCInterval")),
            "JDK 12+ only"
        );
        assert!(
            !preset_args(JvmPreset::Standard, 25, 4096)
                .iter()
                .any(|f| f.contains("Compact"))
        );
    }

    #[test]
    fn auto_plan_follows_the_computer_and_the_instance() {
        let big = Hardware {
            total_mb: 32 * 1024,
            cores: 12,
        };
        let p = auto_plan(big, 25, true, 20);
        assert_eq!((p.max_mb, p.preset), (4096, JvmPreset::Performance));
        // A big pack gets more, but never more than 8 GB.
        assert_eq!(auto_plan(big, 25, true, 150).max_mb, 6656);
        assert_eq!(auto_plan(big, 25, true, 500).max_mb, 7680);
        assert_eq!(auto_plan(big, 21, false, 0).max_mb, 3072);
        // 8 GB / 4 cores: half the RAM at most, the ordinary preset.
        let mid = Hardware {
            total_mb: 8192,
            cores: 4,
        };
        let p = auto_plan(mid, 21, true, 150);
        assert_eq!((p.max_mb, p.preset), (4096, JvmPreset::Standard));
        // A small machine keeps the footprint low and leaves the system room.
        let small = Hardware {
            total_mb: 4096,
            cores: 2,
        };
        let p = auto_plan(small, 17, true, 10);
        assert_eq!((p.max_mb, p.preset), (2048, JvmPreset::LowMemory));
        let tiny = Hardware {
            total_mb: 2048,
            cores: 2,
        };
        assert_eq!(auto_plan(tiny, 8, false, 0).max_mb, 1536);
        // Many cores but little RAM: not ZGC.
        let narrow = Hardware {
            total_mb: 8192,
            cores: 16,
        };
        assert_eq!(auto_plan(narrow, 25, true, 50).preset, JvmPreset::Standard);
    }

    #[test]
    fn typical_java_versions() {
        for (v, java) in [
            ("26.3", 25),
            ("1.21.11", 21),
            ("1.20.6", 21),
            ("1.20.1", 17),
            ("1.18.2", 17),
            ("1.16.5", 8),
            ("1.8.9", 8),
        ] {
            assert_eq!(typical_java_major(v), java, "{v}");
        }
    }

    #[test]
    fn memory_defaults() {
        assert_eq!(default_max_memory_mb(32 * 1024, false), 4096);
        assert_eq!(default_max_memory_mb(32 * 1024, true), 6144);
        assert_eq!(default_max_memory_mb(4 * 1024, false), 1536);
        assert_eq!(default_max_memory_mb(8 * 1024, true), 2560);
        assert_eq!(
            memory_args(JvmPreset::Standard, None, 4096),
            ["-Xms512M", "-Xmx4096M"]
        );
    }

    #[test]
    fn syntax_validation() {
        assert!(validate_syntax(&["-XX:+UseG1GC".into(), "-Dfoo=bar baz".into()]).is_ok());
        assert!(validate_syntax(&["-cp".into()]).is_err());
        assert!(validate_syntax(&["oops".into()]).is_err());
        assert!(validate_syntax(&["-Da=1\n-Db=2".into()]).is_err());
    }
}
