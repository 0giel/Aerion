//! Process-wide environment fixes that must run before the webview starts (no threads yet).

/// Applies platform workarounds and returns notes to log once tracing is up.
pub fn prepare_env() -> Vec<String> {
    #[allow(unused_mut)]
    let mut notes = Vec::new();
    #[cfg(target_os = "linux")]
    {
        // WebKitGTK's DMA-BUF renderer shows a blank window with the proprietary NVIDIA driver.
        if std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none()
            && std::path::Path::new("/proc/driver/nvidia/version").exists()
        {
            // SAFETY: called first thing in `main`, before any other thread exists.
            unsafe { std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1") };
            notes.push("NVIDIA driver detected: WebKit DMA-BUF renderer disabled".into());
        }
        notes.extend(fontconfig::apply());
    }
    notes
}

/// Some tools (newer fontconfig builds, e.g. inside Flatpak runtimes) leave cache files named
/// for an older cache version that are symlinks to a newer version's file. The system fontconfig
/// then reads a foreign format and WebKitGTK's web process spins forever before loading the page
/// (blank window). When that is detected, fontconfig gets a copy of the system configuration
/// whose per-user cache lives in the launcher's data directory; the user's cache is not touched.
#[cfg(target_os = "linux")]
mod fontconfig {
    use std::fs;
    use std::path::{Path, PathBuf};

    const XDG_CACHEDIR: &str = r#"<cachedir prefix="xdg">fontconfig</cachedir>"#;
    const LEGACY_CACHEDIR: &str = "<cachedir>~/.fontconfig</cachedir>";

    pub fn apply() -> Option<String> {
        if std::env::var_os("FONTCONFIG_FILE").is_some() {
            return None;
        }
        let home = std::env::var_os("HOME").map(PathBuf::from)?;
        let user_cache = std::env::var_os("XDG_CACHE_HOME")
            .map(PathBuf::from)
            .filter(|p| p.is_absolute())
            .unwrap_or_else(|| home.join(".cache"))
            .join("fontconfig");
        let bad = mismatched_links(&user_cache) + mismatched_links(&home.join(".fontconfig"));
        if bad == 0 {
            return None;
        }
        let dir = launcher_core::paths::Paths::detect()
            .ok()?
            .fontconfig_cache_dir();
        match write_config(Path::new("/etc/fonts/fonts.conf"), &dir) {
            Ok(file) => {
                // SAFETY: see `prepare_env`.
                unsafe { std::env::set_var("FONTCONFIG_FILE", &file) };
                Some(format!(
                    "fontconfig: {bad} cache file(s) in {} link to another cache version; using a \
                     private cache ({}). Fix for all apps: delete those links and run `fc-cache`",
                    user_cache.display(),
                    dir.display()
                ))
            }
            Err(e) => Some(format!(
                "fontconfig: broken user cache detected, workaround failed: {e}"
            )),
        }
    }

    /// `<hash>-<arch>.cache-<N>` → `N` (other files, e.g. `…cache-reindex1-10`, are ignored).
    pub(super) fn cache_version(name: &str) -> Option<&str> {
        let (_, v) = name.rsplit_once(".cache-")?;
        (!v.is_empty() && v.bytes().all(|b| b.is_ascii_digit())).then_some(v)
    }

    /// Number of cache files that are symlinks to a file of a different cache version.
    pub(super) fn mismatched_links(dir: &Path) -> usize {
        let Ok(entries) = fs::read_dir(dir) else {
            return 0;
        };
        entries
            .flatten()
            .filter(|e| e.file_type().is_ok_and(|t| t.is_symlink()))
            .filter(|e| {
                let name = e.file_name();
                let Some(own) = name.to_str().and_then(cache_version) else {
                    return false;
                };
                fs::read_link(e.path()).ok().is_some_and(|target| {
                    let target = target
                        .file_name()
                        .and_then(|n| n.to_str())
                        .and_then(cache_version);
                    target.is_some_and(|t| t != own)
                })
            })
            .count()
    }

    /// Copies the system configuration with the per-user cache directories replaced.
    pub(super) fn write_config(system: &Path, cache_dir: &Path) -> std::io::Result<PathBuf> {
        let text = fs::read_to_string(system)?;
        if !text.contains(XDG_CACHEDIR) {
            return Err(std::io::Error::other("unexpected fonts.conf layout"));
        }
        let base = system.parent().unwrap_or(Path::new("/etc/fonts"));
        let private = format!(
            "<cachedir>{}</cachedir>",
            xml_escape(&cache_dir.to_string_lossy())
        );
        // Relative includes resolve against the including file, which now lives elsewhere.
        let conf_d = format!(">{}/conf.d</include>", xml_escape(&base.to_string_lossy()));
        let text = text
            .replace(XDG_CACHEDIR, &private)
            .replace(LEGACY_CACHEDIR, "")
            .replace(">conf.d</include>", &conf_d);
        fs::create_dir_all(cache_dir)?;
        let file = cache_dir.join("fonts.conf");
        fs::write(&file, text)?;
        Ok(file)
    }

    fn xml_escape(s: &str) -> String {
        s.replace('&', "&amp;")
            .replace('<', "&lt;")
            .replace('>', "&gt;")
    }
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::fontconfig::*;
    use std::os::unix::fs::symlink;

    #[test]
    fn cache_versions() {
        assert_eq!(cache_version("0843ca89-le64.cache-9"), Some("9"));
        assert_eq!(cache_version("0843ca89-le64.cache-12"), Some("12"));
        assert_eq!(cache_version("0843ca89-le64.cache-reindex1-10"), None);
        assert_eq!(cache_version("CACHEDIR.TAG"), None);
    }

    #[test]
    fn detects_cross_version_links() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path();
        std::fs::write(p.join("a-le64.cache-12"), b"x").unwrap();
        std::fs::write(p.join("b-le64.cache-9"), b"x").unwrap();
        symlink("a-le64.cache-12", p.join("a-le64.cache-10")).unwrap();
        assert_eq!(mismatched_links(p), 1);
        symlink("a-le64.cache-12", p.join("a-le64.cache-9")).unwrap();
        assert_eq!(mismatched_links(p), 2);
        assert_eq!(mismatched_links(&p.join("missing")), 0);
    }

    #[test]
    fn rewrites_cache_dirs_and_includes() {
        let dir = tempfile::tempdir().unwrap();
        let sys = dir.path().join("etc/fonts.conf");
        std::fs::create_dir_all(sys.parent().unwrap()).unwrap();
        std::fs::write(
            &sys,
            r#"<fontconfig><include ignore_missing="yes">conf.d</include>
<cachedir>/var/cache/fontconfig</cachedir>
<cachedir prefix="xdg">fontconfig</cachedir>
<cachedir>~/.fontconfig</cachedir></fontconfig>"#,
        )
        .unwrap();
        let cache = dir.path().join("Mój cache & co");
        let out = std::fs::read_to_string(write_config(&sys, &cache).unwrap()).unwrap();
        assert!(out.contains("<cachedir>/var/cache/fontconfig</cachedir>"));
        assert!(out.contains("Mój cache &amp; co</cachedir>"));
        assert!(!out.contains("prefix=\"xdg\""));
        assert!(!out.contains("~/.fontconfig"));
        assert!(out.contains(&format!(
            ">{}/conf.d</include>",
            dir.path().join("etc").display()
        )));
    }
}
