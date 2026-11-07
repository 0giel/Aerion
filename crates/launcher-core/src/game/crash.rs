//! Turning a failed game run into a plain-language explanation with a fix.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;
use std::time::SystemTime;

use regex::Regex;
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct CrashInfo {
    /// i18n key under `crash.*`, e.g. `outOfMemory`.
    pub kind: String,
    pub params: BTreeMap<String, String>,
    /// The crash report's `Description:` line.
    pub description: Option<String>,
    pub report_path: Option<PathBuf>,
    /// The most relevant lines (stack trace head, loader error list).
    pub excerpt: String,
    pub exit_code: Option<i32>,
    /// The jar in `mods/` that most likely caused the crash, and its mod's name; the UI offers
    /// to disable it.
    pub mod_file: Option<String>,
    pub mod_name: Option<String>,
}

static CLASS_VERSION: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"class file version (\d+)(?:\.\d+)?").expect("static regex"));
static VM_OPTION: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"Unrecognized VM option '([^']+)'").expect("static regex"));
static MIXIN_CONFIG: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"([A-Za-z0-9_\-]+)\.mixins?\.json").expect("static regex"));
static FROM_MOD: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"from mod ([A-Za-z0-9_\-]+)").expect("static regex"));
/// Fabric's dependency errors: `Mod 'Sodium' (sodium) 0.6.0 requires …`.
static FABRIC_MOD: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"Mod '[^']+' \(([A-Za-z0-9_\-]+)\)").expect("static regex"));
/// `Name (modid)`, as crash reports list suspected mods.
static NAME_ID: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\(([A-Za-z0-9_\-]+)\)\s*$").expect("static regex"));
/// Forge/NeoForge crash reports name the jar directly.
static MOD_FILE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"Mod File: (?:.*[/\\])?([^/\\\r\n]+\.jar)").expect("static regex")
});

/// Mod ids from a crash report's `Suspected Mods:` block (since 1.17): either on the same line
/// or one per indented line below it, followed by details such as `Issue tracker URL: …`.
fn suspected_mods(report: &str) -> Vec<String> {
    let mut ids = Vec::new();
    let mut lines = report.lines();
    while let Some(line) = lines.next() {
        let Some(rest) = line
            .trim_start()
            .strip_prefix("Suspected Mods:")
            .or_else(|| line.trim_start().strip_prefix("Suspected Mod:"))
        else {
            continue;
        };
        let rest = rest.trim();
        let block: Vec<&str> = if rest.is_empty() {
            lines
                .by_ref()
                .take_while(|l| l.starts_with([' ', '\t']) && !l.trim().is_empty())
                .collect()
        } else {
            rest.split(", ").collect()
        };
        ids.extend(
            block
                .iter()
                .filter(|l| !l.contains(": "))
                .filter_map(|l| NAME_ID.captures(l.trim()).map(|c| c[1].to_owned())),
        );
    }
    ids
}

/// Loaders and libraries that show up as "mods" but are never the one to switch off.
fn is_builtin(id: &str) -> bool {
    crate::mods::meta::provided_by_loader(id)
        || id == "fabric"
        || id.starts_with("fabric-")
        || id.starts_with("quilted_fabric")
}

fn newest_after(dir: &Path, since: SystemTime, matches: impl Fn(&str) -> bool) -> Option<PathBuf> {
    std::fs::read_dir(dir)
        .ok()?
        .flatten()
        .filter(|e| matches(&e.file_name().to_string_lossy()))
        .filter_map(|e| {
            let m = e.metadata().ok()?.modified().ok()?;
            (m >= since).then(|| (m, e.path()))
        })
        .max_by_key(|(m, _)| *m)
        .map(|(_, p)| p)
}

fn read_capped(p: &Path) -> String {
    let bytes = std::fs::read(p).unwrap_or_default();
    String::from_utf8_lossy(&bytes[..bytes.len().min(2 * 1024 * 1024)]).into_owned()
}

/// Analyse a finished run. Returns `None` for clean exits without a crash report.
pub fn analyze(
    exit_code: Option<i32>,
    log_tail: &[String],
    game_dir: &Path,
    since: SystemTime,
    max_memory_mb: u32,
) -> Option<CrashInfo> {
    let report_path = newest_after(&game_dir.join("crash-reports"), since, |n| {
        n.starts_with("crash-") && n.ends_with(".txt")
    });
    let hs_err = newest_after(game_dir, since, |n| {
        n.starts_with("hs_err_pid") && n.ends_with(".log")
    });
    if exit_code == Some(0) && report_path.is_none() && hs_err.is_none() {
        return None;
    }
    let report = report_path.as_deref().map(read_capped).unwrap_or_default();
    let hs = hs_err.as_deref().map(read_capped).unwrap_or_default();
    let log = log_tail.join("\n");
    let all = format!("{report}\n{log}\n{hs}");

    let mut params = BTreeMap::new();
    let has = |needle: &str| all.contains(needle);
    let kind = if has("java.lang.OutOfMemoryError")
        || has("Could not reserve enough space")
        || has("Insufficient memory")
    {
        params.insert("current".into(), max_memory_mb.to_string());
        params.insert(
            "suggested".into(),
            (max_memory_mb.saturating_mul(3) / 2)
                .div_ceil(512)
                .saturating_mul(512)
                .max(2048)
                .to_string(),
        );
        "outOfMemory"
    } else if let Some(c) = CLASS_VERSION
        .captures(&all)
        .filter(|_| has("UnsupportedClassVersionError"))
    {
        let required = c[1]
            .parse::<u32>()
            .map(|v| v.saturating_sub(44))
            .unwrap_or(0);
        params.insert("required".into(), required.to_string());
        "javaVersion"
    } else if let Some(c) = VM_OPTION.captures(&all) {
        params.insert("option".into(), c[1].to_owned());
        "badJvmArgs"
    } else if has("Could not create the Java Virtual Machine") {
        "badJvmArgs"
    } else if has("Pixel format not accelerated")
        || has("GLFW error 65542")
        || has("WGL: The driver does not appear to support OpenGL")
        || has("GLFW error 65543")
        || has("OpenGL 3.2") && has("not supported")
        || (!hs.is_empty()
            && [
                "nvoglv",
                "atio6axx",
                "atioglxx",
                "ig9icd",
                "ig7icd",
                "ig75icd",
                "libnvidia-glcore",
                "radeonsi",
                "iris_dri",
            ]
            .iter()
            .any(|d| hs.contains(d)))
    {
        "gpuDriver"
    } else if has("DuplicateModsFoundException")
        || has("Found duplicate mods")
        || has("is provided by multiple")
        || has("Duplicate mods")
    {
        "duplicateMod"
    } else if has("Incompatible mods found!")
        || has("incompatible mod set")
        || has("Incompatible mod set")
    {
        "modConflict"
    } else if has("which is missing!")
        || has("Missing or unsupported mandatory dependencies")
        || has("requires any version of")
    {
        "missingDependency"
    } else if has("Mixin apply failed")
        || has("MixinApplyError")
        || has("InvalidInjectionException")
        || has("MixinTransformerError")
    {
        if let Some(m) = FROM_MOD
            .captures(&all)
            .or_else(|| MIXIN_CONFIG.captures(&all))
        {
            params.insert("mod".into(), m[1].to_owned());
        }
        "mixin"
    } else if has("org.lwjgl.opengl.LinuxDisplay") || has("xrandr") && has("Exception") {
        "xrandr"
    } else if has("java.lang.NoClassDefFoundError") || has("java.lang.ClassNotFoundException") {
        "missingDependency"
    } else if matches!(
        exit_code,
        Some(-1073740791) | Some(-1073741819) | Some(-1073741571)
    ) || !hs.is_empty()
    {
        "nativeCrash"
    } else if suspected_mods(&report).iter().any(|id| !is_builtin(id)) {
        "modCrash"
    } else {
        "generic"
    };
    if let Some(c) = exit_code {
        params.insert("exitCode".into(), c.to_string());
    }

    let description = report
        .lines()
        .find_map(|l| l.strip_prefix("Description: "))
        .map(|s| s.trim().to_owned());
    let excerpt = excerpt(&report, log_tail, &hs);
    // Only crashes a mod can cause point at one; memory, Java or driver problems never do.
    let (mod_file, mod_name) = if matches!(
        kind,
        "mixin" | "modConflict" | "missingDependency" | "duplicateMod" | "modCrash"
    ) {
        culprit(&params, &all, &report, &game_dir.join("mods"))
    } else {
        (None, None)
    };
    if let Some(name) = &mod_name {
        params.insert("mod".into(), name.clone());
    }
    Some(CrashInfo {
        kind: kind.into(),
        params,
        description,
        report_path: report_path.or(hs_err),
        excerpt,
        exit_code,
        mod_file,
        mod_name,
    })
}

fn culprit(
    params: &BTreeMap<String, String>,
    all: &str,
    report: &str,
    mods: &Path,
) -> (Option<String>, Option<String>) {
    if let Some(file) = MOD_FILE
        .captures_iter(report)
        .map(|c| c[1].to_owned())
        .find(|f| mods.join(f).is_file())
    {
        let name = crate::mods::meta::read(&mods.join(&file)).map(|m| m.display_name());
        return (Some(file), name);
    }
    let ids = params
        .get("mod")
        .cloned()
        .into_iter()
        .chain(suspected_mods(report))
        .chain(FABRIC_MOD.captures_iter(all).map(|c| c[1].to_owned()));
    for id in ids.filter(|id| !is_builtin(id)) {
        if let Some((file, name)) = crate::mods::meta::find(mods, &id) {
            return (Some(file), Some(name));
        }
    }
    (None, None)
}

fn excerpt(report: &str, log_tail: &[String], hs: &str) -> String {
    if let Some(start) = report.find("Description: ") {
        return report[start..]
            .lines()
            .skip(1)
            .filter(|l| !l.trim().is_empty())
            .take(24)
            .collect::<Vec<_>>()
            .join("\n");
    }
    if !hs.is_empty() {
        return hs.lines().take(12).collect::<Vec<_>>().join("\n");
    }
    let start = log_tail.len().saturating_sub(30);
    log_tail[start..].join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(log: &[&str]) -> CrashInfo {
        let dir = tempfile::tempdir().unwrap();
        let tail: Vec<String> = log.iter().map(|s| (*s).to_owned()).collect();
        analyze(Some(1), &tail, dir.path(), SystemTime::now(), 4096).unwrap()
    }

    #[test]
    fn recognises_common_failures() {
        let oom = run(&["java.lang.OutOfMemoryError: Java heap space"]);
        assert_eq!(oom.kind, "outOfMemory");
        assert_eq!(oom.params["suggested"], "6144");
        let jv = run(&[
            "java.lang.UnsupportedClassVersionError: net/minecraft/Main has been compiled by a more recent version of the Java Runtime (class file version 69.0)",
        ]);
        assert_eq!(
            (jv.kind.as_str(), jv.params["required"].as_str()),
            ("javaVersion", "25")
        );
        let vm = run(&[
            "Unrecognized VM option 'UseFooGC'",
            "Error: Could not create the Java Virtual Machine.",
        ]);
        assert_eq!(
            (vm.kind.as_str(), vm.params["option"].as_str()),
            ("badJvmArgs", "UseFooGC")
        );
        assert_eq!(
            run(&["GLFW error 65542: WGL: The driver does not appear to support OpenGL"]).kind,
            "gpuDriver"
        );
        assert_eq!(
            run(&[
                "Incompatible mods found!",
                " - Mod 'Sodium' (sodium) 0.6 requires version 1.21 of minecraft"
            ])
            .kind,
            "modConflict"
        );
        let mixin =
            run(&["Mixin apply failed sodium.mixins.json:core.MixinFoo -> net.minecraft.Foo"]);
        assert_eq!(
            (
                mixin.kind.as_str(),
                mixin.params.get("mod").map(String::as_str)
            ),
            ("mixin", Some("sodium"))
        );
        assert_eq!(run(&["something else"]).kind, "generic");
    }

    #[test]
    fn clean_exit_is_not_a_crash() {
        let dir = tempfile::tempdir().unwrap();
        assert!(analyze(Some(0), &[], dir.path(), SystemTime::now(), 4096).is_none());
    }

    #[test]
    fn reads_crash_report() {
        let dir = tempfile::tempdir().unwrap();
        let since = SystemTime::now() - std::time::Duration::from_secs(5);
        let reports = dir.path().join("crash-reports");
        std::fs::create_dir_all(&reports).unwrap();
        std::fs::write(
            reports.join("crash-2026-10-30_12.00.00-client.txt"),
            "---- Minecraft Crash Report ----\nDescription: Rendering overlay\n\njava.lang.NullPointerException: boom\n\tat a.b(c.java:1)\n",
        )
        .unwrap();
        let c = analyze(Some(-1), &[], dir.path(), since, 4096).unwrap();
        assert_eq!(c.description.as_deref(), Some("Rendering overlay"));
        assert!(
            c.excerpt
                .starts_with("java.lang.NullPointerException: boom")
        );
        assert!(
            c.report_path
                .unwrap()
                .ends_with("crash-2026-10-30_12.00.00-client.txt")
        );
    }

    #[test]
    fn points_at_the_suspected_mod_jar() {
        use std::io::Write;
        let dir = tempfile::tempdir().unwrap();
        let since = SystemTime::now() - std::time::Duration::from_secs(5);
        let mods = dir.path().join("mods");
        std::fs::create_dir_all(&mods).unwrap();
        let mut z = zip::ZipWriter::new(std::fs::File::create(mods.join("iris-1.8.jar")).unwrap());
        z.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default())
            .unwrap();
        z.write_all(br#"{"id":"iris","name":"Iris"}"#).unwrap();
        z.finish().unwrap();
        let reports = dir.path().join("crash-reports");
        std::fs::create_dir_all(&reports).unwrap();
        std::fs::write(
            reports.join("crash-2026-10-31_20.00.00-client.txt"),
            "Description: Rendering overlay\n\njava.lang.NullPointerException\n\n-- Head --\nSuspected Mods: \n\tFabric API (fabric-rendering-v1)\n\tIris (iris)\n",
        )
        .unwrap();
        let c = analyze(Some(1), &[], dir.path(), since, 4096).unwrap();
        assert_eq!(c.kind, "modCrash");
        assert_eq!(
            (c.mod_file.as_deref(), c.mod_name.as_deref()),
            (Some("iris-1.8.jar"), Some("Iris"))
        );

        // Fabric's dependency error names the mod that needs the missing one.
        let tail = vec![
            "- Mod 'Iris' (iris) 1.8 requires any version of sodium, which is missing!".to_owned(),
        ];
        let c = analyze(Some(1), &tail, dir.path(), SystemTime::now(), 4096).unwrap();
        assert_eq!(
            (c.kind.as_str(), c.mod_file.as_deref()),
            ("missingDependency", Some("iris-1.8.jar"))
        );
    }
}
