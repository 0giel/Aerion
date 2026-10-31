//! Desktop shortcuts that start an instance directly (`luncher --launch <id>`).

use std::path::{Path, PathBuf};

use crate::error::{Error, IoContext, Result};
use crate::instances::Instance;
use crate::util::names::folder_name;

pub fn desktop_dir() -> Result<PathBuf> {
    directories::UserDirs::new()
        .and_then(|d| d.desktop_dir().map(Path::to_path_buf))
        .ok_or_else(|| Error::Invalid("no desktop folder".into()))
}

/// Quote one argument for a `.desktop` `Exec=` line.
#[cfg_attr(not(any(target_os = "linux", test)), allow(dead_code))]
fn desktop_quote(arg: &str) -> String {
    let mut s = String::from("\"");
    for c in arg.chars() {
        if matches!(c, '"' | '`' | '$' | '\\') {
            s.push('\\');
        }
        s.push(c);
    }
    s.push('"');
    s
}

pub fn create(exe: &Path, inst: &Instance, icon: Option<&Path>) -> Result<PathBuf> {
    let dir = desktop_dir()?;
    let base = folder_name(&inst.name);
    create_in(&dir, &base, exe, inst, icon)
}

#[cfg(windows)]
fn create_in(
    dir: &Path,
    base: &str,
    exe: &Path,
    inst: &Instance,
    icon: Option<&Path>,
) -> Result<PathBuf> {
    let path = dir.join(format!("{base}.lnk"));
    let mut link = mslnk::ShellLink::new(exe).map_err(|e| Error::Invalid(e.to_string()))?;
    link.set_arguments(Some(format!("--launch \"{}\"", inst.id)));
    link.set_name(Some(inst.name.clone()));
    if let Some(i) = icon {
        link.set_icon_location(Some(i.display().to_string()));
    }
    link.create_lnk(&path)
        .map_err(|e| Error::Invalid(e.to_string()))?;
    Ok(path)
}

#[cfg(target_os = "linux")]
fn create_in(
    dir: &Path,
    base: &str,
    exe: &Path,
    inst: &Instance,
    icon: Option<&Path>,
) -> Result<PathBuf> {
    let path = dir.join(format!("{base}.desktop"));
    let name = inst.name.replace(['\n', '\r'], " ");
    let content = format!(
        "[Desktop Entry]\nType=Application\nName={name}\nComment=Minecraft — Luncher\nExec={} --launch {}\nIcon={}\nTerminal=false\nCategories=Game;\n",
        desktop_quote(&exe.display().to_string()),
        desktop_quote(&inst.id),
        icon.map(|i| i.display().to_string())
            .unwrap_or_else(|| "luncher".into()),
    );
    std::fs::write(&path, content).at(&path)?;
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).at(&path)?;
    // GNOME only starts launchers marked as trusted; best effort.
    let _ = std::process::Command::new("gio")
        .args(["set"])
        .arg(&path)
        .args(["metadata::trusted", "true"])
        .output();
    Ok(path)
}

#[cfg(target_os = "macos")]
fn create_in(
    dir: &Path,
    base: &str,
    exe: &Path,
    inst: &Instance,
    _icon: Option<&Path>,
) -> Result<PathBuf> {
    let path = dir.join(format!("{base}.command"));
    let q = |s: &str| format!("'{}'", s.replace('\'', "'\\''"));
    let content = format!(
        "#!/bin/sh\n{} --launch {} >/dev/null 2>&1 &\n",
        q(&exe.display().to_string()),
        q(&inst.id)
    );
    std::fs::write(&path, content).at(&path)?;
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).at(&path)?;
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quotes_desktop_exec_arguments() {
        assert_eq!(desktop_quote("Mój świat $1"), "\"Mój świat \\$1\"");
        assert_eq!(desktop_quote("a\"b\\c"), "\"a\\\"b\\\\c\"");
    }
}
