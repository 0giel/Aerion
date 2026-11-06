//! Aerion engine.
//!
//! Everything a Minecraft launcher does, without any UI or Tauri dependency: metadata and
//! downloads, Java runtimes, Microsoft accounts, instances, mod loaders, mod providers and
//! modpacks. The Tauri crate (`src-tauri`) is a thin IPC layer on top of this.

pub mod accounts;
pub mod aerion_mod;
pub mod auth;
pub mod engine;
pub mod error;
pub mod friends;
pub mod game;
pub mod horizon;
pub mod importers;
pub mod instances;
pub mod java;
pub mod loaders;
pub mod modpacks;
pub mod mods;
pub mod mojang;
pub mod net;
pub mod paths;
pub mod perf;
pub mod progress;
pub mod sessions;
pub mod settings;
pub mod skins;
pub mod stats;
pub mod transfer;
pub mod util;

pub use engine::Engine;
pub use error::{Error, ErrorInfo, Result};
pub use progress::{ProgressSnapshot, TaskCtx};
