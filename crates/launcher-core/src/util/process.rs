//! Child processes: every program the launcher starts (Java, loader processors, Horizon, tools).

use std::ffi::OsStr;
use std::sync::OnceLock;

static PRIVATE_ENV: OnceLock<Vec<String>> = OnceLock::new();

/// Records environment variables the launcher set for its own process only (webview
/// workarounds); no child inherits them. Only the first call has an effect.
pub fn set_private_env(vars: Vec<String>) {
    let _ = PRIVATE_ENV.set(vars);
}

/// A command without the launcher's private environment and, on Windows, without a console
/// window.
pub fn command(program: impl AsRef<OsStr>) -> tokio::process::Command {
    let mut cmd = tokio::process::Command::new(program);
    for var in PRIVATE_ENV.get().map_or(&[][..], Vec::as_slice) {
        cmd.env_remove(var);
    }
    #[cfg(target_os = "linux")]
    if let Some(appdir) = std::env::var_os("APPDIR") {
        // Started from the AppImage: its runtime puts its own folders first in these lists.
        // Children (Java and the game) must use the system's libraries, not the AppImage's.
        let appdir = appdir.to_string_lossy().into_owned();
        for var in APPIMAGE_PATHS {
            if let Ok(value) = std::env::var(var) {
                match without_prefix(&value, &appdir) {
                    Some(kept) => cmd.env(var, kept),
                    None => cmd.env_remove(var),
                };
            }
        }
    }
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd
}

/// Search paths the AppImage runtime prepends its own folders to.
#[cfg(target_os = "linux")]
const APPIMAGE_PATHS: [&str; 10] = [
    "LD_LIBRARY_PATH",
    "PATH",
    "XDG_DATA_DIRS",
    "GSETTINGS_SCHEMA_DIR",
    "PYTHONHOME",
    "PYTHONPATH",
    "PERLLIB",
    "QT_PLUGIN_PATH",
    "GST_PLUGIN_SYSTEM_PATH",
    "GST_PLUGIN_SYSTEM_PATH_1_0",
];

/// A `:`-separated list without the entries inside `dir`; `None` when nothing is left.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
fn without_prefix(list: &str, dir: &str) -> Option<String> {
    let dir = dir.trim_end_matches('/');
    let kept: Vec<&str> = list
        .split(':')
        .filter(|e| !e.is_empty() && *e != dir && !e.starts_with(&format!("{dir}/")))
        .collect();
    (!kept.is_empty()).then(|| kept.join(":"))
}

#[cfg(test)]
mod tests {
    use super::without_prefix;

    #[test]
    fn appimage_folders_are_dropped_from_search_paths() {
        let dir = "/tmp/.mount_AerionX1";
        assert_eq!(
            without_prefix(
                "/tmp/.mount_AerionX1/usr/lib:/usr/lib:/tmp/.mount_AerionX1/lib",
                dir
            ),
            Some("/usr/lib".into())
        );
        assert_eq!(without_prefix("/tmp/.mount_AerionX1/usr/bin", dir), None);
        // A sibling folder that only shares the name's beginning stays.
        assert_eq!(
            without_prefix("/tmp/.mount_AerionX12/lib:/usr/bin", dir),
            Some("/tmp/.mount_AerionX12/lib:/usr/bin".into())
        );
        assert_eq!(
            without_prefix("/usr/bin::/bin", "/tmp/.mount_A/"),
            Some("/usr/bin:/bin".into())
        );
    }
}
