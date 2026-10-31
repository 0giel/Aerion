//! Task context: cancellation + lock-free progress counters.
//!
//! Engine functions take a [`TaskCtx`]; the UI layer samples [`Progress::snapshot`] at a fixed
//! rate, which throttles events naturally without the engine knowing about them.

use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

use serde::Serialize;
use tokio_util::sync::CancellationToken;

use crate::error::{Error, Result};

#[derive(Debug, Default)]
pub struct Progress {
    stage: Mutex<(String, Option<String>)>,
    total_bytes: AtomicU64,
    done_bytes: AtomicU64,
    total_items: AtomicU64,
    done_items: AtomicU64,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ProgressSnapshot {
    /// i18n key of the current stage, e.g. `stage.downloadAssets`.
    pub stage: String,
    /// Free-form detail such as a file or version name.
    pub detail: Option<String>,
    pub total_bytes: u64,
    pub done_bytes: u64,
    pub total_items: u64,
    pub done_items: u64,
}

impl Progress {
    pub fn set_stage(&self, key: &str, detail: Option<String>) {
        *self.stage.lock().unwrap_or_else(|e| e.into_inner()) = (key.to_owned(), detail);
    }

    pub fn add_total(&self, items: u64, bytes: u64) {
        self.total_items.fetch_add(items, Ordering::Relaxed);
        self.total_bytes.fetch_add(bytes, Ordering::Relaxed);
    }

    pub fn add_bytes(&self, n: u64) {
        self.done_bytes.fetch_add(n, Ordering::Relaxed);
    }

    pub fn sub_bytes(&self, n: u64) {
        let mut cur = self.done_bytes.load(Ordering::Relaxed);
        while let Err(actual) = self.done_bytes.compare_exchange_weak(
            cur,
            cur.saturating_sub(n),
            Ordering::Relaxed,
            Ordering::Relaxed,
        ) {
            cur = actual;
        }
    }

    pub fn item_done(&self) {
        self.done_items.fetch_add(1, Ordering::Relaxed);
    }

    /// Reset counters when a new phase starts (stage text is kept).
    pub fn reset_counters(&self) {
        for c in [
            &self.total_bytes,
            &self.done_bytes,
            &self.total_items,
            &self.done_items,
        ] {
            c.store(0, Ordering::Relaxed);
        }
    }

    pub fn snapshot(&self) -> ProgressSnapshot {
        let (stage, detail) = self.stage.lock().unwrap_or_else(|e| e.into_inner()).clone();
        ProgressSnapshot {
            stage,
            detail,
            total_bytes: self.total_bytes.load(Ordering::Relaxed),
            done_bytes: self.done_bytes.load(Ordering::Relaxed),
            total_items: self.total_items.load(Ordering::Relaxed),
            done_items: self.done_items.load(Ordering::Relaxed),
        }
    }
}

#[derive(Clone, Default)]
pub struct TaskCtx {
    pub cancel: CancellationToken,
    pub progress: Arc<Progress>,
}

impl TaskCtx {
    pub fn new() -> Self {
        Self::default()
    }

    /// Same progress, cancellable independently (cancelling the parent cancels the child).
    pub fn child(&self) -> Self {
        Self {
            cancel: self.cancel.child_token(),
            progress: self.progress.clone(),
        }
    }

    pub fn check(&self) -> Result<()> {
        if self.cancel.is_cancelled() {
            Err(Error::Cancelled)
        } else {
            Ok(())
        }
    }

    pub fn stage(&self, key: &str, detail: Option<String>) {
        self.progress.set_stage(key, detail);
    }
}
