//! Java runtimes: detection, managed installation (Mojang, Adoptium), JVM flags.

pub mod detect;
pub mod flags;
pub mod runtime;

use std::ffi::OsStr;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub enum JavaSource {
    /// Found on the system (JAVA_HOME, PATH, common install folders, other launchers).
    System,
    /// Installed by Aerion from Mojang's runtime repository.
    Mojang,
    /// Installed by Aerion from Eclipse Adoptium.
    Adoptium,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct JavaInstall {
    /// The `java` executable (`javaw.exe` on Windows).
    pub path: PathBuf,
    pub major: u32,
    pub version: String,
    pub arch: String,
    pub vendor: Option<String>,
    pub source: JavaSource,
}

/// `1.8.0_402` → 8, `21.0.12` → 21, `25` → 25, `17-ea` → 17.
pub fn parse_major(version: &str) -> Option<u32> {
    let mut parts = version.trim().trim_matches('"').split(['.', '_', '-', '+']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        parts.next()?.parse().ok()
    } else {
        Some(first)
    }
}

/// Executable name inside a runtime's `bin` folder.
pub fn java_exe_name() -> &'static str {
    if cfg!(windows) { "javaw.exe" } else { "java" }
}

/// Run `java <args>` to completion within `timeout`; failing to run at all means the Java is
/// unusable.
async fn run(java: &Path, args: &[&OsStr], timeout: Duration) -> Result<std::process::Output> {
    let invalid = |reason: String| Error::JavaInvalid {
        path: java.to_path_buf(),
        reason,
    };
    let mut cmd = crate::util::process::command(java);
    cmd.args(args).kill_on_drop(true);
    tokio::time::timeout(timeout, cmd.output())
        .await
        .map_err(|_| invalid("timed out".into()))?
        .map_err(|e| invalid(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn majors() {
        assert_eq!(parse_major("1.8.0_402"), Some(8));
        assert_eq!(parse_major("21.0.12.1"), Some(21));
        assert_eq!(parse_major("\"25\""), Some(25));
        assert_eq!(parse_major("17-ea"), Some(17));
        assert_eq!(parse_major("abc"), None);
    }
}
