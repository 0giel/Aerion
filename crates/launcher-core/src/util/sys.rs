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
    imp::total_memory_bytes()
        .map(|b| b / (1024 * 1024))
        .unwrap_or(8192)
}

#[cfg(target_os = "linux")]
mod imp {
    pub fn total_memory_bytes() -> Option<u64> {
        let info = std::fs::read_to_string("/proc/meminfo").ok()?;
        let line = info.lines().find(|l| l.starts_with("MemTotal:"))?;
        let kb: u64 = line.split_whitespace().nth(1)?.parse().ok()?;
        Some(kb * 1024)
    }
}

#[cfg(windows)]
mod imp {
    use windows_sys::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};

    pub fn total_memory_bytes() -> Option<u64> {
        // SAFETY: MEMORYSTATUSEX is plain data; dwLength must be set before the call.
        unsafe {
            let mut status: MEMORYSTATUSEX = std::mem::zeroed();
            status.dwLength = std::mem::size_of::<MEMORYSTATUSEX>() as u32;
            (GlobalMemoryStatusEx(&mut status) != 0).then_some(status.ullTotalPhys)
        }
    }
}

#[cfg(target_os = "macos")]
mod imp {
    pub fn total_memory_bytes() -> Option<u64> {
        let out = std::process::Command::new("sysctl")
            .args(["-n", "hw.memsize"])
            .output()
            .ok()?;
        String::from_utf8_lossy(&out.stdout).trim().parse().ok()
    }
}

#[cfg(not(any(target_os = "linux", windows, target_os = "macos")))]
mod imp {
    pub fn total_memory_bytes() -> Option<u64> {
        None
    }
}
