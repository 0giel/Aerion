//! Host information: OS, architecture, physical memory.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "lowercase")]
pub enum Os {
    Windows,
    Macos,
    Linux,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "lowercase")]
pub enum Arch {
    X86,
    X64,
    Arm64,
    Arm,
}

pub fn current_os() -> Os {
    if cfg!(windows) {
        Os::Windows
    } else if cfg!(target_os = "macos") {
        Os::Macos
    } else {
        Os::Linux
    }
}

pub fn current_arch() -> Arch {
    match std::env::consts::ARCH {
        "x86" => Arch::X86,
        "aarch64" => Arch::Arm64,
        "arm" => Arch::Arm,
        _ => Arch::X64,
    }
}

/// Total physical memory in MiB (falls back to 8 GiB if it cannot be determined).
pub fn total_memory_mb() -> u64 {
    let total = sysinfo::System::new().ok().map_or(0, |mut sys| {
        sys.refresh_memory();
        sys.total_memory()
    });
    match total {
        0 => 8192,
        bytes => bytes / (1024 * 1024),
    }
}
