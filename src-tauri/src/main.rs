// Prevents an additional console window on Windows in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    let notes = aerion_lib::platform::prepare_env();
    aerion_lib::run(notes);
}
