//! Process-wide environment fixes that must run before the webview starts (no threads yet).

/// Applies platform workarounds and returns notes to log once tracing is up.
pub fn prepare_env() -> Vec<String> {
    #[allow(unused_mut)]
    let mut notes = Vec::new();
    // Variables set here for the webview only; the game does not inherit them.
    #[allow(unused_mut)]
    let mut private = Vec::new();
    // glibc gives every thread its own malloc arena (up to 8 × cores); a mostly idle launcher
    // does not need them, and each one keeps freed memory resident.
    #[cfg(all(target_os = "linux", target_env = "gnu"))]
    // SAFETY: plain libc call before any other thread exists.
    unsafe {
        libc::mallopt(libc::M_ARENA_MAX, 2);
    }
    #[cfg(target_os = "linux")]
    {
        // WebKitGTK's DMA-BUF renderer shows a blank window with the proprietary NVIDIA driver.
        if std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none()
            && std::path::Path::new("/proc/driver/nvidia/version").exists()
        {
            // SAFETY: called first thing in `main`, before any other thread exists.
            unsafe { std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1") };
            private.push("WEBKIT_DISABLE_DMABUF_RENDERER".into());
            notes.push("NVIDIA driver detected: WebKit DMA-BUF renderer disabled".into());
        }
        if let Some(note) = egl::apply() {
            private.push(egl::VAR.into());
            notes.push(note);
        }
        notes.extend(fontconfig::apply());
    }
    launcher_core::util::process::set_private_env(private);
    notes
}

/// GLVND loads every EGL vendor into both WebKit processes. With the proprietary NVIDIA driver
/// as the only GPU driver, Mesa can offer nothing but a software rasteriser there, yet its
/// LLVM costs ~30 MB; EGL is restricted to NVIDIA's vendor file. Machines with any other GPU
/// driver (hybrid laptops, Mesa drivers, nouveau) are left alone.
#[cfg(target_os = "linux")]
mod egl {
    use std::fs;
    use std::path::{Path, PathBuf};

    pub const VAR: &str = "__EGL_VENDOR_LIBRARY_FILENAMES";
    /// GLVND's default search path.
    const VENDOR_DIRS: [&str; 2] = ["/etc/glvnd/egl_vendor.d", "/usr/share/glvnd/egl_vendor.d"];

    pub fn apply() -> Option<String> {
        if std::env::var_os(VAR).is_some()
            || std::env::var_os("__EGL_VENDOR_LIBRARY_DIRS").is_some()
        {
            return None;
        }
        if !only_nvidia(Path::new("/sys/class/drm")) {
            return None;
        }
        let dirs = VENDOR_DIRS.map(Path::new);
        let file = nvidia_vendor_file(&dirs)?;
        // SAFETY: see `prepare_env`.
        unsafe { std::env::set_var(VAR, &file) };
        Some(format!(
            "NVIDIA is the only GPU driver: EGL restricted to {}",
            file.display()
        ))
    }

    /// True when there is at least one DRM card and every card is driven by `nvidia`.
    pub(super) fn only_nvidia(drm: &Path) -> bool {
        let Ok(entries) = fs::read_dir(drm) else {
            return false;
        };
        let mut found = false;
        for e in entries.flatten() {
            let name = e.file_name();
            // `card0`, `card1`…; connectors are `card0-HDMI-A-1` and so on.
            let is_card = name
                .to_str()
                .and_then(|n| n.strip_prefix("card"))
                .is_some_and(|n| !n.is_empty() && n.bytes().all(|b| b.is_ascii_digit()));
            if !is_card {
                continue;
            }
            let driver = fs::read_link(e.path().join("device/driver")).ok();
            if driver.as_deref().and_then(Path::file_name) != Some("nvidia".as_ref()) {
                return false;
            }
            found = true;
        }
        found
    }

    pub(super) fn nvidia_vendor_file(dirs: &[&Path]) -> Option<PathBuf> {
        dirs.iter().find_map(|dir| {
            let mut files: Vec<_> = fs::read_dir(dir)
                .ok()?
                .flatten()
                .map(|e| e.path())
                .filter(|p| {
                    p.extension().is_some_and(|x| x == "json")
                        && p.file_name()
                            .and_then(|n| n.to_str())
                            .is_some_and(|n| n.contains("nvidia"))
                })
                .collect();
            files.sort();
            files.into_iter().next()
        })
    }
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
    use super::egl::*;
    use super::fontconfig::*;
    use std::os::unix::fs::symlink;

    #[test]
    fn nvidia_only_detection() {
        let dir = tempfile::tempdir().unwrap();
        let drm = dir.path().join("drm");
        let card = |name: &str, driver: &str| {
            let dev = dir.path().join("devices").join(name);
            std::fs::create_dir_all(&dev).unwrap();
            symlink(
                format!("../../../bus/pci/drivers/{driver}"),
                dev.join("driver"),
            )
            .unwrap();
            std::fs::create_dir_all(drm.join(name)).unwrap();
            symlink(&dev, drm.join(name).join("device")).unwrap();
        };
        assert!(!only_nvidia(&drm));
        std::fs::create_dir_all(&drm).unwrap();
        assert!(!only_nvidia(&drm));
        card("card1", "nvidia");
        std::fs::create_dir_all(drm.join("card1-HDMI-A-1")).unwrap();
        assert!(only_nvidia(&drm));
        card("card0", "i915");
        assert!(!only_nvidia(&drm));
    }

    #[test]
    fn finds_the_nvidia_vendor_file() {
        let dir = tempfile::tempdir().unwrap();
        let (etc, share) = (dir.path().join("etc"), dir.path().join("share"));
        std::fs::create_dir_all(&share).unwrap();
        std::fs::write(share.join("50_mesa.json"), "{}").unwrap();
        assert_eq!(nvidia_vendor_file(&[&etc, &share]), None);
        std::fs::write(share.join("10_nvidia.json"), "{}").unwrap();
        assert_eq!(
            nvidia_vendor_file(&[&etc, &share]),
            Some(share.join("10_nvidia.json"))
        );
    }

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
