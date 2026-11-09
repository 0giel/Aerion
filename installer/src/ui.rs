//! The window: native Win32 and drawn by hand, because it has to work before anything (WebView2
//! included) is installed. A worker thread does the job and posts its progress here.
use std::ffi::OsStr;
use std::os::windows::ffi::OsStrExt;
use std::os::windows::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::ptr::{null, null_mut};
use std::sync::{Mutex, OnceLock};

use windows_sys::Win32::Foundation::*;
use windows_sys::Win32::Globalization::GetUserDefaultUILanguage;
use windows_sys::Win32::Graphics::Dwm::{
    DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND, DwmSetWindowAttribute,
};
use windows_sys::Win32::Graphics::Gdi::*;
use windows_sys::Win32::System::Diagnostics::ToolHelp::*;
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::UI::Controls::WM_MOUSELEAVE;
use windows_sys::Win32::UI::HiDpi::GetDpiForSystem;
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
    TME_LEAVE, TRACKMOUSEEVENT, TrackMouseEvent, VK_ESCAPE, VK_RETURN,
};
use windows_sys::Win32::UI::WindowsAndMessaging::*;

use crate::core::{self, Failure};
use crate::paint::Canvas;

static LOGO: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/logo.rgba"));
const WM_PHASE: u32 = WM_APP + 1;
const TIMER_ANIMATE: usize = 1;
const TIMER_CLOSE: usize = 2;
const WIDTH: i32 = 460;
const HEIGHT: i32 = 340;

const BG: u32 = 0x16171B;
const ACCENT: u32 = 0xFBBF24;
const ACCENT_HOT: u32 = 0xFCCB4D;
const ON_ACCENT: u32 = 0x1A1400;
const TEXT: u32 = 0xECEBF3;
const MUTED: u32 = 0xA9A8B6;
const FAINT: u32 = 0x75747F;
const TRACK: u32 = 0x2C2D35;
const ERROR: u32 = 0xFF8A80;
const TRACK_HOT: u32 = 0x3A3B45;
const DANGER: u32 = 0xD8453D;
const DANGER_HOT: u32 = 0xE85A52;

#[derive(Clone, PartialEq)]
enum Phase {
    Ready,
    Download(u64, u64),
    Verify,
    Install,
    Done,
    Failed(Failure),
}

static PHASE: Mutex<Phase> = Mutex::new(Phase::Ready);
/// Set when this window removes Aerion: the folder it is installed in.
static REMOVING: OnceLock<PathBuf> = OnceLock::new();

fn removing() -> bool {
    REMOVING.get().is_some()
}

fn phase() -> Phase {
    PHASE.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

struct Texts {
    tagline: &'static str,
    install: &'static str,
    retry: &'static str,
    note: &'static str,
    fetching: &'static str,
    verifying: &'static str,
    installing: &'static str,
    done: &'static str,
    legal: &'static str,
    network: &'static str,
    signature: &'static str,
    running: &'static str,
    failed: &'static str,
    remove_tagline: &'static str,
    remove: &'static str,
    cancel: &'static str,
    remove_data: &'static str,
    remove_data_warning: &'static str,
    keeps_data: &'static str,
    removing: &'static str,
    removed: &'static str,
    remove_failed: &'static str,
}

const PL: Texts = Texts {
    tagline: "Launcher do Minecraft: Java Edition",
    install: "Zainstaluj",
    retry: "Spróbuj ponownie",
    note: "Bez hasła administratora. Zawsze najnowsza wersja.",
    fetching: "Pobieranie",
    verifying: "Sprawdzanie podpisu…",
    installing: "Instalowanie…",
    done: "Gotowe! Uruchamiam Aeriona…",
    legal: "Nieoficjalny produkt. Niezatwierdzony przez Mojang ani Microsoft.",
    network: "Nie udało się pobrać Aeriona. Sprawdź połączenie z internetem.",
    signature: "Pobrany plik nie ma poprawnego podpisu, więc go nie uruchomiono.",
    running: "Zamknij Aeriona i spróbuj ponownie.",
    failed: "Instalacja się nie powiodła.",
    remove_tagline: "Czy na pewno chcesz odinstalować launcher?",
    remove: "Odinstaluj",
    cancel: "Anuluj",
    remove_data: "Usuń też instancje, światy i ustawienia",
    remove_data_warning: "Tego nie da się cofnąć.",
    keeps_data: "Twoje światy i ustawienia zostaną na komputerze.",
    removing: "Odinstalowywanie…",
    removed: "Aerion został odinstalowany.",
    remove_failed: "Nie udało się odinstalować Aeriona.",
};

const EN: Texts = Texts {
    tagline: "A launcher for Minecraft: Java Edition",
    install: "Install",
    retry: "Try again",
    note: "No administrator password. Always the newest version.",
    fetching: "Downloading",
    verifying: "Checking the signature…",
    installing: "Installing…",
    done: "Done! Starting Aerion…",
    legal: "Unofficial. Not approved by or associated with Mojang or Microsoft.",
    network: "Couldn't download Aerion. Check your internet connection.",
    signature: "The downloaded file has no valid signature, so it wasn't run.",
    running: "Close Aerion and try again.",
    failed: "The installation didn't succeed.",
    remove_tagline: "Are you sure you want to uninstall the launcher?",
    remove: "Uninstall",
    cancel: "Cancel",
    remove_data: "Also delete instances, worlds and settings",
    remove_data_warning: "This can't be undone.",
    keeps_data: "Your worlds and settings stay on this computer.",
    removing: "Uninstalling…",
    removed: "Aerion has been uninstalled.",
    remove_failed: "Couldn't uninstall Aerion.",
};

fn texts() -> &'static Texts {
    // Primary language 0x15 is Polish.
    if unsafe { GetUserDefaultUILanguage() } & 0x3FF == 0x15 {
        &PL
    } else {
        &EN
    }
}

#[derive(Clone, Copy, PartialEq)]
enum Hit {
    None,
    Close,
    Button,
    Cancel,
    Check,
}

struct App {
    mem: HDC,
    bits: *mut u32,
    w: i32,
    h: i32,
    scale: f32,
    hover: Hit,
    tick: f32,
    delete_data: bool,
    title: HFONT,
    body: HFONT,
    small: HFONT,
    button: HFONT,
    close: HFONT,
}

fn wide(s: &str) -> Vec<u16> {
    OsStr::new(s).encode_wide().chain(Some(0)).collect()
}

impl App {
    fn px(&self, v: i32) -> i32 {
        (v as f32 * self.scale).round() as i32
    }

    fn button_rect(&self) -> RECT {
        let (w, h) = (self.px(220), self.px(46));
        let (x, y) = ((self.w - w) / 2, self.px(206));
        RECT {
            left: x,
            top: y,
            right: x + w,
            bottom: y + h,
        }
    }

    /// Uninstalling has two buttons side by side; the main one is on the right.
    fn remove_rects(&self) -> (RECT, RECT) {
        let (w, h, gap) = (self.px(160), self.px(44), self.px(12));
        let y = self.px(238);
        let left = (self.w - 2 * w - gap) / 2;
        let rect = |x: i32| RECT {
            left: x,
            top: y,
            right: x + w,
            bottom: y + h,
        };
        (rect(left), rect(left + w + gap))
    }

    fn check_rect(&self) -> RECT {
        RECT {
            left: self.px(70),
            top: self.px(198),
            right: self.w - self.px(70),
            bottom: self.px(226),
        }
    }

    fn close_rect(&self) -> RECT {
        let s = self.px(38);
        RECT {
            left: self.w - s,
            top: 0,
            right: self.w,
            bottom: s,
        }
    }

    fn hit(&self, x: i32, y: i32) -> Hit {
        let inside = |r: RECT| x >= r.left && x < r.right && y >= r.top && y < r.bottom;
        let shows_button = matches!(phase(), Phase::Ready | Phase::Failed(_));
        if inside(self.close_rect()) {
            Hit::Close
        } else if removing() {
            let (cancel, go) = self.remove_rects();
            match shows_button {
                true if inside(go) => Hit::Button,
                true if inside(cancel) => Hit::Cancel,
                true if phase() == Phase::Ready && inside(self.check_rect()) => Hit::Check,
                _ => Hit::None,
            }
        } else if shows_button && inside(self.button_rect()) {
            Hit::Button
        } else {
            Hit::None
        }
    }

    unsafe fn text(&self, s: &str, font: HFONT, color: u32, mut r: RECT, flags: u32) {
        unsafe {
            let mut w: Vec<u16> = s.encode_utf16().collect();
            SelectObject(self.mem, font as HGDIOBJ);
            // GDI wants 0x00BBGGRR.
            SetTextColor(
                self.mem,
                ((color & 0xFF) << 16) | (color & 0xFF00) | ((color >> 16) & 0xFF),
            );
            DrawTextW(self.mem, w.as_mut_ptr(), w.len() as i32, &mut r, flags);
        }
    }

    unsafe fn render(&mut self) {
        let t = texts();
        let now = phase();
        let (w, h) = (self.w, self.h);
        let s = self.scale;
        let line = |top: i32, bottom: i32| RECT {
            left: 0,
            top,
            right: w,
            bottom,
        };
        let inner = |top: i32, bottom: i32| {
            let m = (40.0 * s) as i32;
            RECT {
                left: m,
                top,
                right: w - m,
                bottom,
            }
        };
        let mut c = Canvas {
            px: unsafe { std::slice::from_raw_parts_mut(self.bits, (w * h) as usize) },
            w,
            h,
        };
        c.clear(BG);
        c.glow(w as f32 / 2.0, -20.0 * s, 330.0 * s, ACCENT, 0.2);
        let logo = self.px(76);
        c.image(LOGO, 128, (w - logo) / 2, self.px(36), logo);

        let (bar_x, bar_w, bar_y, bar_h) = (60.0 * s, w as f32 - 120.0 * s, 218.0 * s, 8.0 * s);
        let mut status = String::new();
        match &now {
            Phase::Ready | Phase::Failed(_) if removing() => {
                let (cancel, go) = self.remove_rects();
                let mut pill = |r: RECT, color: u32| {
                    c.rounded(
                        r.left as f32,
                        r.top as f32,
                        (r.right - r.left) as f32,
                        (r.bottom - r.top) as f32,
                        11.0 * s,
                        color,
                        1.0,
                    );
                };
                pill(
                    cancel,
                    if self.hover == Hit::Cancel {
                        TRACK_HOT
                    } else {
                        TRACK
                    },
                );
                pill(
                    go,
                    if self.hover == Hit::Button {
                        DANGER_HOT
                    } else {
                        DANGER
                    },
                );
                if now == Phase::Ready {
                    let (x, y, side) =
                        (self.px(70) as f32, self.px(204) as f32, self.px(16) as f32);
                    let on = self.delete_data;
                    let edge = if self.hover == Hit::Check {
                        ACCENT
                    } else {
                        FAINT
                    };
                    c.rounded(
                        x,
                        y,
                        side,
                        side,
                        4.0 * s,
                        if on { DANGER } else { edge },
                        1.0,
                    );
                    if on {
                        c.line(
                            x + 0.25 * side,
                            y + 0.52 * side,
                            x + 0.43 * side,
                            y + 0.7 * side,
                            1.1 * s,
                            0xFFFFFF,
                        );
                        c.line(
                            x + 0.43 * side,
                            y + 0.7 * side,
                            x + 0.76 * side,
                            y + 0.3 * side,
                            1.1 * s,
                            0xFFFFFF,
                        );
                    } else {
                        c.rounded(
                            x + 1.5 * s,
                            y + 1.5 * s,
                            side - 3.0 * s,
                            side - 3.0 * s,
                            3.0 * s,
                            BG,
                            1.0,
                        );
                    }
                }
            }
            Phase::Ready | Phase::Failed(_) => {
                let r = self.button_rect();
                let color = if self.hover == Hit::Button {
                    ACCENT_HOT
                } else {
                    ACCENT
                };
                c.rounded(
                    r.left as f32,
                    r.top as f32,
                    (r.right - r.left) as f32,
                    (r.bottom - r.top) as f32,
                    11.0 * s,
                    color,
                    1.0,
                );
            }
            Phase::Download(got, total) => {
                c.rounded(bar_x, bar_y, bar_w, bar_h, bar_h / 2.0, TRACK, 1.0);
                let part = if *total > 0 {
                    *got as f32 / *total as f32
                } else {
                    0.0
                };
                if part > 0.0 {
                    c.rounded(
                        bar_x,
                        bar_y,
                        (bar_w * part).max(bar_h),
                        bar_h,
                        bar_h / 2.0,
                        ACCENT,
                        1.0,
                    );
                }
                let mb = |b: u64| b as f64 / 1_048_576.0;
                status = if *total > 0 {
                    format!(
                        "{} {}%  ·  {:.1} / {:.1} MB",
                        t.fetching,
                        (part * 100.0) as u32,
                        mb(*got),
                        mb(*total)
                    )
                } else {
                    format!("{}…", t.fetching)
                };
            }
            Phase::Verify | Phase::Install => {
                c.rounded(bar_x, bar_y, bar_w, bar_h, bar_h / 2.0, TRACK, 1.0);
                let piece = bar_w * 0.3;
                let x = bar_x + (bar_w - piece) * (0.5 + 0.5 * self.tick.sin());
                c.rounded(x, bar_y, piece, bar_h, bar_h / 2.0, ACCENT, 1.0);
                status = if removing() {
                    t.removing
                } else if now == Phase::Verify {
                    t.verifying
                } else {
                    t.installing
                }
                .to_owned();
            }
            Phase::Done => {
                c.rounded(bar_x, bar_y, bar_w, bar_h, bar_h / 2.0, ACCENT, 1.0);
                status = if removing() { t.removed } else { t.done }.to_owned();
            }
        }
        if self.hover == Hit::Close {
            let r = self.close_rect();
            c.rounded(
                r.left as f32 + 5.0 * s,
                r.top as f32 + 5.0 * s,
                28.0 * s,
                28.0 * s,
                7.0 * s,
                0xFFFFFF,
                0.12,
            );
        }

        unsafe {
            SetBkMode(self.mem, TRANSPARENT as _);
            let center = DT_CENTER | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX;
            self.text(
                "Aerion",
                self.title,
                TEXT,
                line(self.px(118), self.px(162)),
                center,
            );
            self.text(
                if removing() {
                    t.remove_tagline
                } else {
                    t.tagline
                },
                self.body,
                MUTED,
                line(self.px(162), self.px(188)),
                center,
            );
            match &now {
                Phase::Ready | Phase::Failed(_) if removing() => {
                    let (cancel, go) = self.remove_rects();
                    self.text(t.cancel, self.button, TEXT, cancel, center);
                    let label = if now == Phase::Ready {
                        t.remove
                    } else {
                        t.retry
                    };
                    self.text(label, self.button, 0xFFFFFF, go, center);
                    let below = line(self.px(284), self.px(304));
                    if let Phase::Failed(why) = &now {
                        let message = if *why == Failure::Running {
                            t.running
                        } else {
                            t.remove_failed
                        };
                        self.text(message, self.small, ERROR, below, center);
                    } else {
                        let mut row = self.check_rect();
                        row.left = self.px(96);
                        self.text(
                            t.remove_data,
                            self.body,
                            TEXT,
                            row,
                            DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX,
                        );
                        if self.delete_data {
                            self.text(t.remove_data_warning, self.small, ERROR, below, center);
                        } else {
                            self.text(t.keeps_data, self.small, MUTED, below, center);
                        }
                    }
                }
                Phase::Ready => {
                    self.text(
                        t.install,
                        self.button,
                        ON_ACCENT,
                        self.button_rect(),
                        center,
                    );
                    self.text(
                        t.note,
                        self.small,
                        MUTED,
                        line(self.px(262), self.px(286)),
                        center,
                    );
                }
                Phase::Failed(why) => {
                    self.text(t.retry, self.button, ON_ACCENT, self.button_rect(), center);
                    let message = match why {
                        Failure::Network(_) => t.network,
                        Failure::Signature => t.signature,
                        Failure::Running => t.running,
                        Failure::Manifest(_) | Failure::Install(_) => t.failed,
                    };
                    self.text(
                        message,
                        self.small,
                        ERROR,
                        inner(self.px(260), self.px(300)),
                        DT_CENTER | DT_WORDBREAK | DT_NOPREFIX,
                    );
                }
                _ => self.text(
                    &status,
                    self.body,
                    TEXT,
                    line(self.px(236), self.px(264)),
                    center,
                ),
            }
            self.text(
                t.legal,
                self.small,
                FAINT,
                inner(self.px(304), self.px(332)),
                center | DT_END_ELLIPSIS,
            );
            self.text(
                "×",
                self.close,
                if self.hover == Hit::Close {
                    TEXT
                } else {
                    MUTED
                },
                self.close_rect(),
                center,
            );
        }
    }
}

fn app_is_running() -> bool {
    unsafe {
        let snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if snap == INVALID_HANDLE_VALUE {
            return false;
        }
        let mut entry: PROCESSENTRY32W = std::mem::zeroed();
        entry.dwSize = size_of::<PROCESSENTRY32W>() as u32;
        let mut found = false;
        let mut more = Process32FirstW(snap, &mut entry) != 0;
        while more && !found {
            let len = entry
                .szExeFile
                .iter()
                .position(|c| *c == 0)
                .unwrap_or(entry.szExeFile.len());
            found = String::from_utf16_lossy(&entry.szExeFile[..len])
                .eq_ignore_ascii_case("aerion.exe");
            more = Process32NextW(snap, &mut entry) != 0;
        }
        CloseHandle(snap);
        found
    }
}

fn install_dir() -> Result<PathBuf, Failure> {
    std::env::var_os("LOCALAPPDATA")
        .map(|d| PathBuf::from(d).join("Aerion"))
        .ok_or_else(|| Failure::Install("no LOCALAPPDATA".into()))
}

/// The whole job, on the worker thread. The regular installer does the installing, so the
/// uninstall entry, the shortcuts, WebView2 and later updates are the same as ever.
fn install(report: impl Fn(Phase)) -> Result<(), Failure> {
    if app_is_running() {
        return Err(Failure::Running);
    }
    let release = core::latest()?;
    let mut shown = u64::MAX;
    let bytes = core::download(&release.url, |got, total| {
        // A new picture for each half percent is plenty.
        let step = got
            .saturating_mul(200)
            .checked_div(total)
            .unwrap_or(got >> 20);
        if step != shown {
            shown = step;
            report(Phase::Download(got, total));
        }
    })?;
    report(Phase::Verify);
    core::verify(core::PUBLIC_KEY, &release.signature, &bytes)?;
    let problem = |e: std::io::Error| Failure::Install(e.to_string());
    let dir = std::env::temp_dir().join(format!("aerion-setup-{}", std::process::id()));
    std::fs::create_dir_all(&dir).map_err(problem)?;
    let file = dir.join("Aerion-setup.exe");
    std::fs::write(&file, &bytes).map_err(problem)?;
    drop(bytes);
    report(Phase::Install);
    let status = Command::new(&file).arg("/S").status();
    let _ = std::fs::remove_dir_all(&dir);
    let status = status.map_err(problem)?;
    if !status.success() {
        return Err(Failure::Install(format!("the installer returned {status}")));
    }
    let home = install_dir()?;
    let exe = home.join("aerion.exe");
    if !exe.is_file() {
        return Err(Failure::Install("aerion.exe is missing".into()));
    }
    report(Phase::Done);
    Command::new(&exe)
        .current_dir(&home)
        .spawn()
        .map_err(problem)?;
    Ok(())
}

/// The folders of a player's data: settings, instances and worlds, and what the window system
/// keeps for the launcher. Only the default places; a data folder moved elsewhere stays.
fn data_dirs() -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    if let Some(local) = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .filter(|d| d.is_absolute())
    {
        dirs.push(local.join("Aerion"));
        dirs.push(local.join("pl.aerion.desktop"));
    }
    if let Some(roaming) = std::env::var_os("APPDATA")
        .map(PathBuf::from)
        .filter(|d| d.is_absolute())
    {
        dirs.push(roaming.join("pl.aerion.desktop"));
    }
    dirs
}

/// The regular uninstaller does the removing (files, shortcuts, the entry in Windows), so
/// nothing can be left half done by a second implementation.
fn uninstall(dir: &Path, delete_data: bool) -> Result<(), Failure> {
    if app_is_running() {
        return Err(Failure::Running);
    }
    let regular = dir.join("uninstall.exe");
    if !regular.is_file() {
        return Err(Failure::Install("uninstall.exe is missing".into()));
    }
    // `_?=` runs it in place and waits; the path must be last and unquoted, spaces included.
    let status = Command::new(&regular)
        .raw_arg("/S")
        .raw_arg(format!("_?={}", dir.display()))
        .status()
        .map_err(|e| Failure::Install(e.to_string()))?;
    if !status.success() {
        return Err(Failure::Install(format!(
            "the uninstaller returned {status}"
        )));
    }
    // Run in place, it can't delete itself; neither could this program from there.
    let _ = std::fs::remove_file(&regular);
    let _ = std::fs::remove_file(dir.join(UNINSTALLER));
    if delete_data {
        for d in data_dirs() {
            let _ = std::fs::remove_dir_all(d);
        }
    }
    let _ = std::fs::remove_dir(dir);
    Ok(())
}

fn start_uninstall(hwnd: HWND, delete_data: bool) {
    let Some(dir) = REMOVING.get() else { return };
    let handle = hwnd as isize;
    set_phase(handle, Phase::Install);
    std::thread::spawn(move || match uninstall(dir, delete_data) {
        Ok(()) => set_phase(handle, Phase::Done),
        Err(why) => set_phase(handle, Phase::Failed(why)),
    });
}

const UNINSTALLER: &str = "AerionUninstall.exe";

/// What the program was asked to do; the window opens only for the two interactive cases.
fn dispatch() -> bool {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let first = args.first().map(String::as_str);
    if first == Some("--uninstall-from") {
        if let Some(dir) = args.get(1) {
            let _ = REMOVING.set(PathBuf::from(dir));
        }
        return true;
    }
    if first != Some("--uninstall") {
        return true;
    }
    let Some(here) = std::env::current_exe()
        .ok()
        .and_then(|e| e.parent().map(Path::to_path_buf))
    else {
        return false;
    };
    // The regular installer calls this with its own switches (when replacing an old version,
    // and during updates): that always goes straight to the regular uninstaller.
    if args.len() > 1 {
        let status = Command::new(here.join("uninstall.exe"))
            .raw_arg(args[1..].join(" "))
            .status();
        std::process::exit(status.ok().and_then(|s| s.code()).unwrap_or(2));
    }
    // A running program can't delete itself, so the window runs from a temporary copy.
    let temp = std::env::temp_dir().join(format!("aerion-uninstall-{}", std::process::id()));
    let copy = temp.join(UNINSTALLER);
    let copied =
        std::fs::create_dir_all(&temp).and_then(|_| std::fs::copy(here.join(UNINSTALLER), &copy));
    if copied.is_ok()
        && Command::new(&copy)
            .arg("--uninstall-from")
            .arg(&here)
            .spawn()
            .is_ok()
    {
        return false;
    }
    let _ = REMOVING.set(here);
    true
}

/// A temporary copy (see [`dispatch`]) can only go once it has ended: `cmd` waits a moment and
/// removes its folder.
fn remove_temporary_copy() {
    let Some(temp) = std::env::current_exe()
        .ok()
        .and_then(|e| e.parent().map(Path::to_path_buf))
    else {
        return;
    };
    let ours = temp
        .file_name()
        .is_some_and(|n| n.to_string_lossy().starts_with("aerion-uninstall-"));
    if ours && temp.starts_with(std::env::temp_dir()) {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let _ = Command::new("cmd")
            .creation_flags(CREATE_NO_WINDOW)
            .raw_arg(format!(
                "/c ping -n 3 127.0.0.1 >nul & rmdir /s /q \"{}\"",
                temp.display()
            ))
            .spawn();
    }
}

fn set_phase(hwnd: isize, p: Phase) {
    *PHASE.lock().unwrap_or_else(|e| e.into_inner()) = p;
    unsafe { PostMessageW(hwnd as HWND, WM_PHASE, 0, 0) };
}

fn start(hwnd: HWND) {
    let handle = hwnd as isize;
    set_phase(handle, Phase::Download(0, 0));
    std::thread::spawn(move || {
        if let Err(why) = install(|p| set_phase(handle, p)) {
            set_phase(handle, Phase::Failed(why));
        }
    });
}

unsafe fn app<'a>(hwnd: HWND) -> Option<&'a mut App> {
    unsafe { (GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut App).as_mut() }
}

fn split(lparam: LPARAM) -> (i32, i32) {
    (
        (lparam & 0xFFFF) as i16 as i32,
        ((lparam >> 16) & 0xFFFF) as i16 as i32,
    )
}

fn set_hover(a: &mut App, hwnd: HWND, hit: Hit) {
    if a.hover != hit {
        a.hover = hit;
        unsafe { InvalidateRect(hwnd, null(), 0) };
    }
}

fn activate(hwnd: HWND, a: &mut App, hit: Hit) {
    match hit {
        Hit::Close => {
            // Stopping the regular installer halfway would leave a half-installed program.
            if phase() != Phase::Install {
                unsafe { DestroyWindow(hwnd) };
            }
        }
        Hit::Cancel => unsafe {
            DestroyWindow(hwnd);
        },
        Hit::Check => {
            a.delete_data = !a.delete_data;
            unsafe { InvalidateRect(hwnd, null(), 0) };
        }
        Hit::Button if removing() => start_uninstall(hwnd, a.delete_data),
        Hit::Button => start(hwnd),
        Hit::None => {}
    }
}

unsafe extern "system" fn window_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    unsafe {
        let Some(a) = app(hwnd) else {
            return DefWindowProcW(hwnd, msg, wparam, lparam);
        };
        match msg {
            WM_NCHITTEST => {
                let (x, y) = split(lparam);
                let mut p = POINT { x, y };
                ScreenToClient(hwnd, &mut p);
                // Everything that isn't a control moves the window.
                if a.hit(p.x, p.y) == Hit::None {
                    HTCAPTION as LRESULT
                } else {
                    HTCLIENT as LRESULT
                }
            }
            WM_SETCURSOR if matches!(a.hover, Hit::Button | Hit::Cancel | Hit::Check) => {
                SetCursor(LoadCursorW(null_mut(), IDC_HAND));
                1
            }
            WM_MOUSEMOVE => {
                let (x, y) = split(lparam);
                let mut track = TRACKMOUSEEVENT {
                    cbSize: size_of::<TRACKMOUSEEVENT>() as u32,
                    dwFlags: TME_LEAVE,
                    hwndTrack: hwnd,
                    dwHoverTime: 0,
                };
                TrackMouseEvent(&mut track);
                let hit = a.hit(x, y);
                set_hover(a, hwnd, hit);
                0
            }
            WM_MOUSELEAVE => {
                set_hover(a, hwnd, Hit::None);
                0
            }
            WM_LBUTTONUP => {
                let (x, y) = split(lparam);
                let hit = a.hit(x, y);
                activate(hwnd, a, hit);
                0
            }
            WM_KEYDOWN => {
                let key = wparam as u16;
                if key == VK_RETURN
                    && !removing()
                    && matches!(phase(), Phase::Ready | Phase::Failed(_))
                {
                    start(hwnd);
                } else if key == VK_ESCAPE {
                    activate(hwnd, a, Hit::Close);
                }
                0
            }
            WM_PHASE => {
                let now = phase();
                if matches!(now, Phase::Verify | Phase::Install) {
                    SetTimer(hwnd, TIMER_ANIMATE, 33, None);
                } else {
                    KillTimer(hwnd, TIMER_ANIMATE);
                }
                if now == Phase::Done {
                    SetTimer(
                        hwnd,
                        TIMER_CLOSE,
                        if removing() { 2200 } else { 1200 },
                        None,
                    );
                }
                a.hover = Hit::None;
                InvalidateRect(hwnd, null(), 0);
                0
            }
            WM_TIMER => {
                if wparam == TIMER_CLOSE {
                    DestroyWindow(hwnd);
                } else {
                    a.tick += 0.12;
                    InvalidateRect(hwnd, null(), 0);
                }
                0
            }
            WM_ERASEBKGND => 1,
            WM_PAINT => {
                let mut ps: PAINTSTRUCT = std::mem::zeroed();
                let dc = BeginPaint(hwnd, &mut ps);
                a.render();
                BitBlt(dc, 0, 0, a.w, a.h, a.mem, 0, 0, SRCCOPY);
                EndPaint(hwnd, &ps);
                0
            }
            WM_DESTROY => {
                PostQuitMessage(0);
                0
            }
            _ => DefWindowProcW(hwnd, msg, wparam, lparam),
        }
    }
}

unsafe fn font(px: i32, weight: i32) -> HFONT {
    unsafe {
        let name = wide("Segoe UI");
        CreateFontW(
            -px,
            0,
            0,
            0,
            weight,
            0,
            0,
            0,
            DEFAULT_CHARSET as u32,
            OUT_DEFAULT_PRECIS as u32,
            CLIP_DEFAULT_PRECIS as u32,
            CLEARTYPE_QUALITY as u32,
            DEFAULT_PITCH as u32,
            name.as_ptr(),
        )
    }
}

pub fn run() {
    if !dispatch() {
        return;
    }
    unsafe {
        let instance = GetModuleHandleW(null());
        let class = wide("AerionSetupWindow");
        let wc = WNDCLASSEXW {
            cbSize: size_of::<WNDCLASSEXW>() as u32,
            style: CS_DROPSHADOW,
            lpfnWndProc: Some(window_proc),
            cbClsExtra: 0,
            cbWndExtra: 0,
            hInstance: instance,
            hIcon: LoadIconW(instance, 1 as _),
            hCursor: LoadCursorW(null_mut(), IDC_ARROW),
            hbrBackground: null_mut(),
            lpszMenuName: null(),
            lpszClassName: class.as_ptr(),
            hIconSm: LoadIconW(instance, 1 as _),
        };
        RegisterClassExW(&wc);

        let scale = GetDpiForSystem() as f32 / 96.0;
        let (w, h) = (
            (WIDTH as f32 * scale) as i32,
            (HEIGHT as f32 * scale) as i32,
        );
        let screen = GetDC(null_mut());
        let mem = CreateCompatibleDC(screen);
        let mut info: BITMAPINFO = std::mem::zeroed();
        info.bmiHeader.biSize = size_of::<BITMAPINFOHEADER>() as u32;
        info.bmiHeader.biWidth = w;
        info.bmiHeader.biHeight = -h; // top row first
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = BI_RGB;
        let mut bits = null_mut();
        let dib = CreateDIBSection(mem, &info, DIB_RGB_COLORS, &mut bits, null_mut(), 0);
        SelectObject(mem, dib as HGDIOBJ);
        ReleaseDC(null_mut(), screen);

        let px = |v: f32| (v * scale).round() as i32;
        let state = Box::into_raw(Box::new(App {
            mem,
            bits: bits as *mut u32,
            w,
            h,
            scale,
            hover: Hit::None,
            tick: 0.0,
            delete_data: false,
            title: font(px(32.0), 700),
            body: font(px(14.0), 400),
            small: font(px(12.0), 400),
            button: font(px(16.0), 600),
            close: font(px(20.0), 400),
        }));

        let title = wide("Aerion");
        let hwnd = CreateWindowExW(
            0,
            class.as_ptr(),
            title.as_ptr(),
            WS_POPUP | WS_MINIMIZEBOX | WS_SYSMENU,
            (GetSystemMetrics(SM_CXSCREEN) - w) / 2,
            (GetSystemMetrics(SM_CYSCREEN) - h) / 2,
            w,
            h,
            null_mut(),
            null_mut(),
            instance,
            null(),
        );
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, state as isize);
        // Rounded corners on Windows 11; ignored elsewhere.
        let round = DWMWCP_ROUND;
        DwmSetWindowAttribute(
            hwnd,
            DWMWA_WINDOW_CORNER_PREFERENCE as u32,
            &round as *const _ as _,
            size_of_val(&round) as u32,
        );
        ShowWindow(hwnd, SW_SHOW);
        SetForegroundWindow(hwnd);

        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, null_mut(), 0, 0) > 0 {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
    remove_temporary_copy();
}
