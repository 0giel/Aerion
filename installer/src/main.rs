// AerionSetup.exe: see Cargo.toml. Everything but the window also builds elsewhere, for the tests.
#![cfg_attr(windows, windows_subsystem = "windows")]
#![cfg_attr(not(windows), allow(dead_code))]

mod core;
mod paint;
#[cfg(windows)]
mod ui;

#[cfg(windows)]
fn main() {
    ui::run();
}

#[cfg(not(windows))]
fn main() {
    eprintln!("AerionSetup is a Windows program.");
}
