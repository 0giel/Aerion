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
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd
}
