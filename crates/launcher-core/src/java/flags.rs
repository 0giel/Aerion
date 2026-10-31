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

/// Memory arguments for a heap of `max_mb`.
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
    let mut cmd = tokio::process::Command::new(java);
    cmd.args(args).arg("-version").kill_on_drop(true);
    crate::game::process::hide_console(&mut cmd);
    let out = tokio::time::timeout(Duration::from_secs(15), cmd.output())
        .await
        .map_err(|_| Error::JavaInvalid {
            path: java.to_path_buf(),
            reason: "timed out".into(),
        })?
        .map_err(|e| Error::JavaInvalid {
            path: java.to_path_buf(),
            reason: e.to_string(),
        })?;
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
