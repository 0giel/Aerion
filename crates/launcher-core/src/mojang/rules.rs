//! `rules` evaluation for libraries and arguments in version JSONs.
//!
//! The environment is explicit (not read from the host) so that tests can check the
//! outcome for Windows, macOS and Linux from any machine.

use std::collections::BTreeMap;

use regex::Regex;
use serde::{Deserialize, Serialize};

use crate::util::sys::{Arch, Os, current_arch, current_os};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RuleAction {
    Allow,
    Disallow,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Rule {
    pub action: RuleAction,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub os: Option<OsRule>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub features: Option<BTreeMap<String, bool>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OsRule {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub arch: Option<String>,
}

/// Launcher feature flags referenced by argument rules.
#[derive(Debug, Clone, Default)]
pub struct Features {
    pub is_demo_user: bool,
    pub has_custom_resolution: bool,
    pub has_quick_plays_support: bool,
    pub is_quick_play_singleplayer: bool,
    pub is_quick_play_multiplayer: bool,
    pub is_quick_play_realms: bool,
}

impl Features {
    fn get(&self, name: &str) -> bool {
        match name {
            "is_demo_user" => self.is_demo_user,
            "has_custom_resolution" => self.has_custom_resolution,
            "has_quick_plays_support" => self.has_quick_plays_support,
            "is_quick_play_singleplayer" => self.is_quick_play_singleplayer,
            "is_quick_play_multiplayer" => self.is_quick_play_multiplayer,
            "is_quick_play_realms" => self.is_quick_play_realms,
            _ => false,
        }
    }
}

#[derive(Debug, Clone)]
pub struct Environment {
    pub os: Os,
    pub arch: Arch,
    /// Matched against `os.version` regexes (Windows reports "10.0" on 10 and 11).
    pub os_version: String,
    pub features: Features,
}

impl Environment {
    pub fn current() -> Self {
        Self::for_os(current_os(), current_arch())
    }

    pub fn for_os(os: Os, arch: Arch) -> Self {
        let os_version = match os {
            Os::Windows => "10.0".to_owned(),
            _ => String::new(),
        };
        Self {
            os,
            arch,
            os_version,
            features: Features::default(),
        }
    }

    /// Name used in version JSON `os.name` and `natives` maps.
    pub fn os_key(&self) -> &'static str {
        match self.os {
            Os::Windows => "windows",
            Os::Macos => "osx",
            Os::Linux => "linux",
        }
    }

    /// Value used for `${arch}` in legacy native classifiers.
    pub fn arch_bits(&self) -> &'static str {
        match self.arch {
            Arch::X86 | Arch::Arm => "32",
            _ => "64",
        }
    }

    fn arch_key(&self) -> &'static str {
        match self.arch {
            Arch::X86 => "x86",
            Arch::X64 => "x86_64",
            Arch::Arm64 => "arm64",
            Arch::Arm => "arm",
        }
    }
}

impl Rule {
    pub fn matches(&self, env: &Environment) -> bool {
        if let Some(os) = &self.os {
            if let Some(name) = &os.name {
                let wanted = match name.as_str() {
                    "macos" => "osx",
                    other => other,
                };
                if wanted != env.os_key() {
                    return false;
                }
            }
            if let Some(arch) = &os.arch
                && arch != env.arch_key()
            {
                return false;
            }
            if let Some(version) = &os.version {
                match Regex::new(version) {
                    Ok(re) if re.is_match(&env.os_version) => {}
                    _ => return false,
                }
            }
        }
        if let Some(features) = &self.features {
            for (name, value) in features {
                if env.features.get(name) != *value {
                    return false;
                }
            }
        }
        true
    }
}

/// Mojang semantics: no rules ⇒ allowed; otherwise start disallowed and let the last
/// matching rule decide.
pub fn rules_allow(rules: &[Rule], env: &Environment) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut allowed = false;
    for rule in rules {
        if rule.matches(env) {
            allowed = rule.action == RuleAction::Allow;
        }
    }
    allowed
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rules(json: &str) -> Vec<Rule> {
        serde_json::from_str(json).unwrap()
    }

    #[test]
    fn allow_all_but_osx() {
        let r = rules(r#"[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]"#);
        assert!(rules_allow(
            &r,
            &Environment::for_os(Os::Windows, Arch::X64)
        ));
        assert!(rules_allow(&r, &Environment::for_os(Os::Linux, Arch::X64)));
        assert!(!rules_allow(
            &r,
            &Environment::for_os(Os::Macos, Arch::Arm64)
        ));
    }

    #[test]
    fn os_only_and_arch_and_version() {
        let only_osx = rules(r#"[{"action":"allow","os":{"name":"osx"}}]"#);
        assert!(rules_allow(
            &only_osx,
            &Environment::for_os(Os::Macos, Arch::X64)
        ));
        assert!(!rules_allow(
            &only_osx,
            &Environment::for_os(Os::Linux, Arch::X64)
        ));

        let x86 = rules(r#"[{"action":"allow","os":{"arch":"x86"}}]"#);
        assert!(rules_allow(
            &x86,
            &Environment::for_os(Os::Windows, Arch::X86)
        ));
        assert!(!rules_allow(
            &x86,
            &Environment::for_os(Os::Windows, Arch::X64)
        ));

        let win10 = rules(r#"[{"action":"allow","os":{"name":"windows","version":"^10\\."}}]"#);
        assert!(rules_allow(
            &win10,
            &Environment::for_os(Os::Windows, Arch::X64)
        ));
        assert!(!rules_allow(
            &win10,
            &Environment::for_os(Os::Linux, Arch::X64)
        ));
    }

    #[test]
    fn feature_rules() {
        let r = rules(r#"[{"action":"allow","features":{"has_custom_resolution":true}}]"#);
        let mut env = Environment::for_os(Os::Linux, Arch::X64);
        assert!(!rules_allow(&r, &env));
        env.features.has_custom_resolution = true;
        assert!(rules_allow(&r, &env));
        let demo = rules(r#"[{"action":"allow","features":{"is_demo_user":true}}]"#);
        assert!(!rules_allow(&demo, &env));
    }
}
