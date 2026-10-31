// Prevents an additional console window on Windows in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    let notes = luncher_lib::platform::prepare_env();
    luncher_lib::run(notes);
}
