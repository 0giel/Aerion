//! IPC commands. Each is a thin wrapper over `launcher_core`; types are exported to
//! TypeScript by tauri-specta (`src/lib/bindings.ts`).

pub mod accounts;
pub mod app;
pub mod instances;
pub mod launch;
pub mod mods;
pub mod skins;
pub mod tasks;
pub mod themes;
pub mod versions;

pub type CmdResult<T> = Result<T, launcher_core::ErrorInfo>;
