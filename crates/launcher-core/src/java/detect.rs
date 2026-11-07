//! Finding Java installations already present on the machine.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::time::Duration;

use crate::error::{Error, Result};
use crate::java::{JavaInstall, JavaSource, java_exe_name, parse_major};

/// Inspect one `java` binary: read `<home>/release`, or run it as a fallback.
pub async fn inspect(java: &Path, source: JavaSource) -> Result<JavaInstall> {
    let java = java.to_path_buf();
    if !java.is_file() {
        return Err(Error::JavaInvalid {
            path: java,
            reason: "file does not exist".into(),
        });
    }
    if let Some(home) = java.parent().and_then(Path::parent)
        && let Ok(release) = tokio::fs::read_to_string(home.join("release")).await
    {
        let field = |key: &str| {
            release.lines().find_map(|l| {
                l.strip_prefix(key)
                    .and_then(|r| r.strip_prefix('='))
                    .map(|v| v.trim().trim_matches('"').to_owned())
            })
        };
        if let Some(version) = field("JAVA_VERSION")
            && let Some(major) = parse_major(&version)
        {
            return Ok(JavaInstall {
                path: java,
                major,
                version,
                arch: normalize_arch(&field("OS_ARCH").unwrap_or_default()),
                vendor: field("IMPLEMENTOR"),
                source,
            });
        }
    }
    probe(&java, source).await
}

/// Run `java -XshowSettings:properties -version` and parse the properties it prints.
pub async fn probe(java: &Path, source: JavaSource) -> Result<JavaInstall> {
    let out = super::run(
        java,
        &["-XshowSettings:properties".as_ref(), "-version".as_ref()],
        Duration::from_secs(10),
    )
    .await?;
    let text = String::from_utf8_lossy(&out.stderr);
    let prop = |key: &str| {
        text.lines().find_map(|l| {
            let (k, v) = l.trim().split_once(" = ")?;
            (k == key).then(|| v.trim().to_owned())
        })
    };
    let version = prop("java.version").ok_or_else(|| Error::JavaInvalid {
        path: java.to_path_buf(),
        reason: "no java.version".into(),
    })?;
    let major = parse_major(&version).ok_or_else(|| Error::JavaInvalid {
        path: java.to_path_buf(),
        reason: format!("bad version {version}"),
    })?;
    Ok(JavaInstall {
        path: java.to_path_buf(),
        major,
        version,
        arch: normalize_arch(&prop("os.arch").unwrap_or_default()),
        vendor: prop("java.vendor"),
        source,
    })
}

fn normalize_arch(a: &str) -> String {
    match a {
        "amd64" | "x86_64" | "x64" => "x64",
        "aarch64" | "arm64" => "arm64",
        "x86" | "i386" | "i686" => "x86",
        other => other,
    }
    .to_owned()
}

fn push_bins(out: &mut Vec<PathBuf>, home: &Path) {
    out.push(home.join("bin").join(java_exe_name()));
    out.push(
        home.join("Contents")
            .join("Home")
            .join("bin")
            .join(java_exe_name()),
    );
}

fn children(dir: &Path) -> Vec<PathBuf> {
    std::fs::read_dir(dir)
        .map(|rd| rd.flatten().map(|e| e.path()).collect())
        .unwrap_or_default()
}

/// Candidate executables, in rough priority order.
pub fn candidates() -> Vec<PathBuf> {
    let mut out = Vec::new();
    if let Some(home) = std::env::var_os("JAVA_HOME") {
        push_bins(&mut out, Path::new(&home));
    }
    if let Some(path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path) {
            out.push(dir.join(java_exe_name()));
        }
    }
    let home = directories::BaseDirs::new().map(|b| b.home_dir().to_path_buf());
    let mut roots: Vec<PathBuf> = Vec::new();
    if cfg!(windows) {
        for pf in ["ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"] {
            if let Some(base) = std::env::var_os(pf) {
                for vendor in [
                    "Java",
                    "Eclipse Adoptium",
                    "Eclipse Foundation",
                    "Microsoft",
                    "Zulu",
                    "BellSoft",
                    "Amazon Corretto",
                    "Semeru",
                    "OpenJDK",
                ] {
                    roots.push(Path::new(&base).join(vendor));
                }
            }
        }
        if let Some(appdata) = std::env::var_os("APPDATA") {
            // Official launcher runtimes: runtime/<component>/<platform>/<component>
            for comp in children(&Path::new(&appdata).join(".minecraft").join("runtime")) {
                for platform in children(&comp) {
                    roots.push(platform);
                }
            }
        }
    } else if cfg!(target_os = "macos") {
        roots.push("/Library/Java/JavaVirtualMachines".into());
        if let Some(h) = &home {
            roots.push(h.join("Library/Java/JavaVirtualMachines"));
        }
    } else {
        for r in [
            "/usr/lib/jvm",
            "/usr/lib64/jvm",
            "/usr/java",
            "/opt/java",
            "/opt/jdk",
            "/opt",
        ] {
            roots.push(r.into());
        }
    }
    if let Some(h) = &home {
        roots.push(h.join(".jdks"));
        roots.push(h.join(".sdkman/candidates/java"));
        for comp in children(&h.join(".minecraft").join("runtime")) {
            for platform in children(&comp) {
                roots.push(platform);
            }
        }
    }
    for root in roots {
        for home in children(&root) {
            push_bins(&mut out, &home);
        }
    }
    out
}

/// All distinct, working Java installations found on the system.
pub async fn detect_system() -> Vec<JavaInstall> {
    let mut seen = HashSet::new();
    let mut found = Vec::new();
    for c in candidates() {
        if !c.is_file() {
            continue;
        }
        let canonical = std::fs::canonicalize(&c).unwrap_or_else(|_| c.clone());
        if !seen.insert(canonical.clone()) {
            continue;
        }
        // Prefer the canonical location unless it is a Windows `\\?\` path.
        let path = if cfg!(windows) { c } else { canonical };
        if let Ok(install) = inspect(&path, JavaSource::System).await {
            found.push(install);
        }
    }
    found.sort_by(|a, b| b.major.cmp(&a.major).then(a.path.cmp(&b.path)));
    found
}
