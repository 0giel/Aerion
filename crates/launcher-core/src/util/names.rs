//! Validation of user-supplied names that end up as directory names.

use crate::error::{Error, Result};

const FORBIDDEN: &[char] = &['/', '\\', ':', '*', '?', '"', '<', '>', '|'];
const RESERVED: &[&str] = &[
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
    "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

/// Validate a display name (instances, themes). Unicode (e.g. Polish letters) is fine.
pub fn validate_display_name(name: &str) -> Result<String> {
    let name = name.trim();
    if name.is_empty() {
        return Err(Error::InvalidName("empty".into()));
    }
    if name.chars().count() > 64 {
        return Err(Error::InvalidName("too long (max 64 characters)".into()));
    }
    if name.chars().any(char::is_control) {
        return Err(Error::InvalidName(
            "control characters are not allowed".into(),
        ));
    }
    Ok(name.to_owned())
}

/// Turn a display name into a safe folder name on every OS.
pub fn folder_name(name: &str) -> String {
    let mut s: String = name
        .trim()
        .chars()
        .map(|c| {
            if FORBIDDEN.contains(&c) || c.is_control() {
                '_'
            } else {
                c
            }
        })
        .collect();
    while s.ends_with('.') || s.ends_with(' ') {
        s.pop();
    }
    let s = s.trim_start_matches('.').trim().to_owned();
    let stem = s.split('.').next().unwrap_or("").to_ascii_uppercase();
    if s.is_empty() || RESERVED.contains(&stem.as_str()) {
        return format!("_{s}");
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn folder_names_are_safe() {
        assert_eq!(
            folder_name("Mój Świat: Fabric 1.21"),
            "Mój Świat_ Fabric 1.21"
        );
        assert_eq!(folder_name("con"), "_con");
        assert_eq!(folder_name("CON.txt"), "_CON.txt");
        assert_eq!(folder_name("..hidden.. "), "hidden");
        assert_eq!(folder_name("a/b\\c"), "a_b_c");
    }

    #[test]
    fn display_names() {
        assert!(validate_display_name("  ").is_err());
        assert!(validate_display_name("ok\nno").is_err());
        assert_eq!(validate_display_name(" Łukasz ").unwrap(), "Łukasz");
        assert!(validate_display_name(&"x".repeat(65)).is_err());
    }
}
