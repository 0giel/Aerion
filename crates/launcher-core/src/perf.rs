//! Live resource use for the performance page: the whole computer (CPU, memory, GPU when the
//! driver tells), Aerion itself (its process and the webview processes it started) and each
//! running game. Sampled on request, about once a second while the page is open.

use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use serde::Serialize;
use sysinfo::{Pid, ProcessRefreshKind, ProcessesToUpdate, System};

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct CpuInfo {
    pub name: String,
    pub cores: u32,
    /// Whole machine, 0–100.
    pub usage: f32,
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct GpuInfo {
    pub name: String,
    pub usage: Option<f32>,
    pub vram_used_mb: Option<u32>,
    pub vram_total_mb: Option<u32>,
    pub temperature_c: Option<f32>,
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ProcessUse {
    /// Share of the whole machine, 0–100 (a process using two of eight cores is 25).
    pub cpu: f32,
    pub memory_mb: u32,
    pub processes: u32,
}

/// A program on the computer (its processes summed), for "what is using the computer".
#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ProgramUse {
    pub name: String,
    pub usage: ProcessUse,
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct GameUse {
    pub instance_id: String,
    pub usage: ProcessUse,
    /// The heap limit given to the game (`-Xmx`).
    pub max_memory_mb: u32,
    /// Frames per second, reported by the Aerion mod.
    pub fps: Option<u32>,
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct PerfSnapshot {
    pub cpu: CpuInfo,
    pub memory_total_mb: u32,
    pub memory_used_mb: u32,
    pub gpu: Option<GpuInfo>,
    pub launcher: ProcessUse,
    pub games: Vec<GameUse>,
    /// The programs using the most processor time right now (shown only on this computer).
    pub top: Vec<ProgramUse>,
}

/// A running game as the launcher knows it.
pub struct GameProcess {
    pub instance_id: String,
    pub pid: u32,
    pub max_memory_mb: u32,
    pub fps: Option<u32>,
}

/// Keeps the previous sample: CPU usage is measured between two refreshes.
pub struct Monitor {
    sys: System,
    gpu: Option<(Instant, Option<GpuInfo>)>,
}

const MB: u64 = 1024 * 1024;
/// nvidia-smi costs a process start; GPU numbers are refreshed at most this often.
const GPU_EVERY: Duration = Duration::from_secs(2);

impl Monitor {
    pub fn new() -> Option<Self> {
        let mut sys = System::new().ok()?;
        sys.refresh_cpu_usage();
        Some(Self { sys, gpu: None })
    }

    /// Process ids of `root` and everything it started, except `exclude` and their children.
    fn tree(&self, root: Pid, exclude: &HashSet<Pid>) -> Vec<Pid> {
        let mut children: HashMap<Pid, Vec<Pid>> = HashMap::new();
        for (pid, p) in self.sys.processes() {
            // Linux lists threads too; each repeats its process's memory.
            if p.thread_kind().is_some() {
                continue;
            }
            if let Some(parent) = p.parent() {
                children.entry(parent).or_default().push(*pid);
            }
        }
        let mut out = Vec::new();
        let mut stack = vec![root];
        while let Some(p) = stack.pop() {
            if exclude.contains(&p) {
                continue;
            }
            out.push(p);
            if let Some(c) = children.get(&p) {
                stack.extend(c);
            }
        }
        out
    }

    fn usage(&self, pids: &[Pid]) -> ProcessUse {
        let cores = self.sys.cpus().len().max(1) as f32;
        let (mut cpu, mut mem, mut n) = (0.0f32, 0u64, 0u32);
        for p in pids {
            if let Some(proc_) = self.sys.process(*p) {
                cpu += proc_.cpu_usage();
                mem += proc_.memory();
                n += 1;
            }
        }
        ProcessUse {
            cpu: (cpu / cores).min(100.0),
            memory_mb: (mem / MB) as u32,
            processes: n,
        }
    }

    /// Processes grouped by name, busiest first.
    fn top(&self, n: usize) -> Vec<ProgramUse> {
        let cores = self.sys.cpus().len().max(1) as f32;
        let mut by_name: HashMap<String, ProcessUse> = HashMap::new();
        for p in self.sys.processes().values() {
            if p.thread_kind().is_some() {
                continue;
            }
            let u = by_name
                .entry(p.name().to_string_lossy().into_owned())
                .or_insert(ProcessUse {
                    cpu: 0.0,
                    memory_mb: 0,
                    processes: 0,
                });
            u.cpu += p.cpu_usage() / cores;
            u.memory_mb += (p.memory() / MB) as u32;
            u.processes += 1;
        }
        let mut list: Vec<ProgramUse> = by_name
            .into_iter()
            .map(|(name, usage)| ProgramUse { name, usage })
            .filter(|p| p.usage.cpu >= 0.5)
            .collect();
        list.sort_by(|a, b| b.usage.cpu.total_cmp(&a.usage.cpu));
        list.truncate(n);
        for p in &mut list {
            p.usage.cpu = p.usage.cpu.min(100.0);
        }
        list
    }

    pub async fn sample(&mut self, games: &[GameProcess]) -> PerfSnapshot {
        self.sys.refresh_cpu_usage();
        self.sys.refresh_memory();
        self.sys.refresh_processes_specifics(
            ProcessesToUpdate::All,
            true,
            ProcessRefreshKind::nothing().with_cpu().with_memory(),
        );
        let game_roots: HashSet<Pid> = games.iter().map(|g| Pid::from_u32(g.pid)).collect();
        let me = Pid::from_u32(std::process::id());
        let launcher = self.usage(&self.tree(me, &game_roots));
        let games = games
            .iter()
            .map(|g| GameUse {
                instance_id: g.instance_id.clone(),
                usage: self.usage(&self.tree(Pid::from_u32(g.pid), &HashSet::new())),
                max_memory_mb: g.max_memory_mb,
                fps: g.fps,
            })
            .collect();
        let cpus = self.sys.cpus();
        let cpu = CpuInfo {
            name: cpus
                .first()
                .map(|c| c.brand().trim().to_owned())
                .unwrap_or_default(),
            cores: cpus.len() as u32,
            usage: self.sys.global_cpu_usage(),
        };
        if self
            .gpu
            .as_ref()
            .is_none_or(|(t, _)| t.elapsed() >= GPU_EVERY)
        {
            self.gpu = Some((Instant::now(), gpu().await));
        }
        PerfSnapshot {
            cpu,
            memory_total_mb: (self.sys.total_memory() / MB) as u32,
            memory_used_mb: (self.sys.used_memory() / MB) as u32,
            gpu: self.gpu.as_ref().and_then(|(_, g)| g.clone()),
            launcher,
            games,
            top: self.top(5),
        }
    }
}

/// `name, utilization, memory used, memory total, temperature` from nvidia-smi.
fn parse_nvidia_smi(out: &str) -> Option<GpuInfo> {
    let line = out.lines().next()?;
    let f: Vec<&str> = line.split(',').map(str::trim).collect();
    let num = |i: usize| f.get(i).and_then(|v| v.parse::<f32>().ok());
    Some(GpuInfo {
        name: f.first()?.to_string(),
        usage: num(1),
        vram_used_mb: num(2).map(|v| v as u32),
        vram_total_mb: num(3).map(|v| v as u32),
        temperature_c: num(4),
    })
}

async fn nvidia() -> Option<GpuInfo> {
    let mut cmd = tokio::process::Command::new("nvidia-smi");
    cmd.args([
        "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu",
        "--format=csv,noheader,nounits",
    ]);
    crate::game::process::hide_console(&mut cmd);
    let out = tokio::time::timeout(Duration::from_secs(2), cmd.output())
        .await
        .ok()?
        .ok()?;
    out.status
        .success()
        .then(|| parse_nvidia_smi(&String::from_utf8_lossy(&out.stdout)))
        .flatten()
}

/// AMD on Linux: the amdgpu driver's sysfs files.
#[cfg(target_os = "linux")]
fn amdgpu() -> Option<GpuInfo> {
    for entry in std::fs::read_dir("/sys/class/drm").ok()?.flatten() {
        let dev = entry.path().join("device");
        let read = |f: &str| std::fs::read_to_string(dev.join(f)).ok();
        let Some(busy) = read("gpu_busy_percent") else {
            continue;
        };
        let num = |v: Option<String>| v.and_then(|s| s.trim().parse::<u64>().ok());
        let temp = std::fs::read_dir(dev.join("hwmon"))
            .ok()
            .and_then(|mut d| d.next())
            .and_then(|h| h.ok())
            .and_then(|h| std::fs::read_to_string(h.path().join("temp1_input")).ok())
            .and_then(|t| t.trim().parse::<f32>().ok())
            .map(|t| t / 1000.0);
        return Some(GpuInfo {
            name: "AMD Radeon".into(),
            usage: busy.trim().parse().ok(),
            vram_used_mb: num(read("mem_info_vram_used")).map(|b| (b / MB) as u32),
            vram_total_mb: num(read("mem_info_vram_total")).map(|b| (b / MB) as u32),
            temperature_c: temp,
        });
    }
    None
}

async fn gpu() -> Option<GpuInfo> {
    if let Some(g) = nvidia().await {
        return Some(g);
    }
    #[cfg(target_os = "linux")]
    if let Some(g) = amdgpu() {
        return Some(g);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_nvidia_smi() {
        let g = parse_nvidia_smi("NVIDIA GeForce RTX 4070, 37, 2048, 12282, 54\n").unwrap();
        assert_eq!(g.name, "NVIDIA GeForce RTX 4070");
        assert_eq!(
            (g.usage, g.vram_used_mb, g.vram_total_mb, g.temperature_c),
            (Some(37.0), Some(2048), Some(12282), Some(54.0))
        );
        // Fields a GPU doesn't support come back as "[N/A]".
        let g = parse_nvidia_smi("Quadro, [N/A], 100, 4096, [N/A]").unwrap();
        assert_eq!((g.usage, g.temperature_c), (None, None));
    }

    #[tokio::test]
    async fn samples_this_process() {
        let mut m = Monitor::new().unwrap();
        let s = m.sample(&[]).await;
        assert!(s.cpu.cores > 0 && s.memory_total_mb > 0);
        assert!(s.launcher.processes >= 1 && s.launcher.memory_mb > 0);
    }
}
