//! JVM and game argument construction for both version JSON formats.
//!
//! Placeholders are substituted per argument *after* splitting, so values with spaces or
//! non-ASCII characters (`C:\Users\Łukasz\...`) always stay a single argument.

use std::collections::HashMap;

use crate::mojang::rules::{Environment, rules_allow};
use crate::mojang::version::{Argument, VersionJson};
use crate::util::sys::{Arch, Os};

pub type Vars = HashMap<String, String>;

/// Replace every `${name}` with its value; unknown placeholders are left untouched.
pub fn substitute(template: &str, vars: &Vars) -> String {
    let mut out = String::with_capacity(template.len());
    let mut rest = template;
    while let Some(start) = rest.find("${") {
        out.push_str(&rest[..start]);
        let after = &rest[start + 2..];
        let Some(end) = after.find('}') else {
            out.push_str(&rest[start..]);
            return out;
        };
        let key = &after[..end];
        match vars.get(key) {
            Some(v) => out.push_str(v),
            None => {
                out.push_str("${");
                out.push_str(key);
                out.push('}');
            }
        }
        rest = &after[end + 1..];
    }
    out.push_str(rest);
    out
}

fn expand(args: &[Argument], env: &Environment, vars: &Vars) -> Vec<String> {
    let mut out = Vec::new();
    for arg in args {
        match arg {
            Argument::Plain(s) => out.push(substitute(s, vars)),
            Argument::Conditional { rules, value } => {
                if rules_allow(rules, env) {
                    out.extend(value.values().iter().map(|s| substitute(s, vars)));
                }
            }
        }
    }
    out
}

/// JVM arguments the official launcher adds for versions that predate `arguments.jvm`.
fn legacy_jvm_templates(env: &Environment) -> Vec<&'static str> {
    let mut t = Vec::new();
    match env.os {
        Os::Macos => t.push("-XstartOnFirstThread"),
        Os::Windows => {
            t.push("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump")
        }
        Os::Linux => {}
    }
    if env.arch == Arch::X86 {
        t.push("-Xss1M");
    }
    t.extend([
        "-Djava.library.path=${natives_directory}",
        "-Dminecraft.launcher.brand=${launcher_name}",
        "-Dminecraft.launcher.version=${launcher_version}",
        "-cp",
        "${classpath}",
    ]);
    t
}

pub fn jvm_args(version: &VersionJson, env: &Environment, vars: &Vars) -> Vec<String> {
    match &version.arguments {
        Some(a) if !a.jvm.is_empty() => expand(&a.jvm, env, vars),
        _ => legacy_jvm_templates(env)
            .into_iter()
            .map(|s| substitute(s, vars))
            .collect(),
    }
}

pub fn game_args(version: &VersionJson, env: &Environment, vars: &Vars) -> Vec<String> {
    if let Some(legacy) = &version.minecraft_arguments {
        let mut out: Vec<String> = legacy
            .split_whitespace()
            .map(|s| substitute(s, vars))
            .collect();
        if env.features.has_custom_resolution {
            for (flag, key) in [
                ("--width", "resolution_width"),
                ("--height", "resolution_height"),
            ] {
                if let Some(v) = vars.get(key) {
                    out.push(flag.to_owned());
                    out.push(v.clone());
                }
            }
        }
        return out;
    }
    version
        .arguments
        .as_ref()
        .map(|a| expand(&a.game, env, vars))
        .unwrap_or_default()
}

/// Whether the version understands `--quickPlay*` arguments (1.20+).
pub fn supports_quick_play(version: &VersionJson) -> bool {
    version.arguments.as_ref().is_some_and(|a| {
        a.game.iter().any(|arg| {
            matches!(arg, Argument::Conditional { rules, .. }
                if rules.iter().any(|r| r.features.as_ref().is_some_and(|f| f.contains_key("is_quick_play_multiplayer"))))
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mojang::version::VersionJson;

    fn fixture(name: &str) -> VersionJson {
        let path = format!(
            "{}/tests/fixtures/versions/{name}",
            env!("CARGO_MANIFEST_DIR")
        );
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    fn vars(game_dir: &str) -> Vars {
        [
            ("auth_player_name", "Steve"),
            ("version_name", "x"),
            ("game_directory", game_dir),
            ("assets_root", "/a"),
            ("assets_index_name", "idx"),
            ("auth_uuid", "uuid"),
            ("auth_access_token", "token"),
            ("clientid", "cid"),
            ("auth_xuid", "0"),
            ("user_type", "msa"),
            ("version_type", "release"),
            ("user_properties", "{}"),
            ("natives_directory", "/n"),
            ("launcher_name", "luncher"),
            ("launcher_version", "0.1.0"),
            ("classpath", "/l/a.jar:/l/b.jar"),
            ("resolution_width", "1280"),
            ("resolution_height", "720"),
        ]
        .into_iter()
        .map(|(k, v)| (k.to_owned(), v.to_owned()))
        .collect()
    }

    #[test]
    fn substitution_keeps_unknown_and_handles_unicode() {
        let v =
            vars(r"C:\Users\Łukasz Kowalski\AppData\Roaming\Luncher\instances\Mój świat\minecraft");
        assert_eq!(
            substitute("--gameDir=${game_directory}", &v),
            r"--gameDir=C:\Users\Łukasz Kowalski\AppData\Roaming\Luncher\instances\Mój świat\minecraft"
        );
        assert_eq!(substitute("${unknown} ${", &v), "${unknown} ${");
        assert_eq!(
            substitute("-Dx=${natives_directory}/lwjgl", &v),
            "-Dx=/n/lwjgl"
        );
    }

    #[test]
    fn legacy_1_8_9_arguments() {
        let ver = fixture("1.8.9.json");
        let env = Environment::for_os(Os::Windows, Arch::X64);
        let dir = r"C:\Users\Łukasz\gra z spacją";
        let game = game_args(&ver, &env, &vars(dir));
        let i = game.iter().position(|a| a == "--gameDir").unwrap();
        assert_eq!(game[i + 1], dir, "path with spaces stays one argument");
        assert!(game.contains(&"--userProperties".to_owned()));
        let jvm = jvm_args(&ver, &env, &vars(dir));
        assert!(jvm.iter().any(|a| a.starts_with("-XX:HeapDumpPath=")));
        assert_eq!(&jvm[jvm.len() - 2..], ["-cp", "/l/a.jar:/l/b.jar"]);
        let mac = jvm_args(&ver, &Environment::for_os(Os::Macos, Arch::X64), &vars(dir));
        assert_eq!(mac[0], "-XstartOnFirstThread");
    }

    #[test]
    fn legacy_resolution_is_appended() {
        let ver = fixture("1.12.2.json");
        let mut env = Environment::for_os(Os::Linux, Arch::X64);
        env.features.has_custom_resolution = true;
        let game = game_args(&ver, &env, &vars("/g"));
        assert_eq!(
            &game[game.len() - 4..],
            ["--width", "1280", "--height", "720"]
        );
    }

    #[test]
    fn modern_26_3_arguments() {
        let ver = fixture("26.3.json");
        let mut env = Environment::for_os(Os::Linux, Arch::X64);
        let jvm = jvm_args(&ver, &env, &vars("/g"));
        assert!(jvm.contains(&"-Djava.library.path=/n/java".to_owned()));
        assert!(jvm.contains(&"--enable-native-access=ALL-UNNAMED".to_owned()));
        assert!(
            !jvm.iter()
                .any(|a| a == "-XstartOnFirstThread" || a.contains("HeapDumpPath"))
        );
        let game = game_args(&ver, &env, &vars("/g"));
        assert!(!game.contains(&"--width".to_owned()));
        assert!(!game.contains(&"--demo".to_owned()));
        assert!(
            !game.contains(&"--userType".to_owned()),
            "26.x removed --userType"
        );
        let i = game.iter().position(|a| a == "--xuid").unwrap();
        assert_eq!(game[i + 1], "0");
        env.features.has_custom_resolution = true;
        let game = game_args(&ver, &env, &vars("/g"));
        assert!(game.windows(2).any(|w| w == ["--width", "1280"]));
        assert!(
            game.iter().all(|a| !a.contains("${")),
            "all placeholders resolved: {game:?}"
        );
    }

    #[test]
    fn os_version_rule_on_1_16_5() {
        let ver = fixture("1.16.5.json");
        let jvm = jvm_args(
            &ver,
            &Environment::for_os(Os::Windows, Arch::X64),
            &vars("/g"),
        );
        assert!(jvm.contains(&"-Dos.name=Windows 10".to_owned()));
        let jvm = jvm_args(
            &ver,
            &Environment::for_os(Os::Linux, Arch::X64),
            &vars("/g"),
        );
        assert!(!jvm.iter().any(|a| a.starts_with("-Dos.name")));
    }

    #[test]
    fn quick_play_detection() {
        assert!(supports_quick_play(&fixture("26.3.json")));
        assert!(supports_quick_play(&fixture("1.20.1.json")));
        assert!(!supports_quick_play(&fixture("1.16.5.json")));
        assert!(!supports_quick_play(&fixture("1.8.9.json")));
    }
}
