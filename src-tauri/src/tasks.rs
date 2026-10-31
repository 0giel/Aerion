//! Background tasks with progress and cancellation, mirrored to the UI as `TaskEvent`s.

use std::collections::HashMap;
use std::future::Future;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use launcher_core::{ErrorInfo, ProgressSnapshot, TaskCtx};
use serde::Serialize;
use specta::Type;
use tauri::AppHandle;
use tauri_specta::Event;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub enum TaskStatus {
    Running,
    Done,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub struct TaskInfo {
    pub id: String,
    /// `launch`, `install`, `import`, `export`, `update`, `verify`, `modpack`, ...
    pub kind: String,
    /// i18n key of the title.
    pub title: String,
    /// Instance or project name shown next to the title.
    pub subject: Option<String>,
    pub instance_id: Option<String>,
    pub status: TaskStatus,
    pub progress: ProgressSnapshot,
    pub error: Option<ErrorInfo>,
    /// JSON-encoded result for tasks that produce one (e.g. imports).
    pub result: Option<String>,
    pub started_at: f64,
}

#[derive(Debug, Clone, Serialize, Type, Event)]
pub struct TaskEvent(pub TaskInfo);

#[derive(Clone, Default)]
pub struct TaskRegistry {
    inner: Arc<Mutex<HashMap<String, (TaskInfo, TaskCtx)>>>,
}

impl TaskRegistry {
    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, (TaskInfo, TaskCtx)>> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn list(&self) -> Vec<TaskInfo> {
        let mut v: Vec<TaskInfo> = self.lock().values().map(|(i, c)| TaskInfo { progress: c.progress.snapshot(), ..i.clone() }).collect();
        v.sort_by(|a, b| a.started_at.total_cmp(&b.started_at));
        v
    }

    pub fn cancel(&self, id: &str) -> bool {
        match self.lock().get(id) {
            Some((_, ctx)) => {
                ctx.cancel.cancel();
                true
            }
            None => false,
        }
    }

    pub fn dismiss(&self, id: &str) {
        let mut map = self.lock();
        if map.get(id).is_some_and(|(i, _)| i.status != TaskStatus::Running) {
            map.remove(id);
        }
    }

    pub fn running_for(&self, instance_id: &str, kind: &str) -> bool {
        self.lock()
            .values()
            .any(|(i, _)| i.status == TaskStatus::Running && i.kind == kind && i.instance_id.as_deref() == Some(instance_id))
    }

    /// Run `f` in the background; progress is sampled every 150 ms and emitted only on change.
    pub fn spawn<F, Fut>(
        &self,
        app: &AppHandle,
        kind: &str,
        title: &str,
        subject: Option<String>,
        instance_id: Option<String>,
        f: F,
    ) -> String
    where
        F: FnOnce(TaskCtx) -> Fut + Send + 'static,
        Fut: Future<Output = launcher_core::Result<Option<String>>> + Send + 'static,
    {
        let id = uuid::Uuid::new_v4().simple().to_string();
        let ctx = TaskCtx::new();
        let info = TaskInfo {
            id: id.clone(),
            kind: kind.into(),
            title: title.into(),
            subject,
            instance_id,
            status: TaskStatus::Running,
            progress: ctx.progress.snapshot(),
            error: None,
            result: None,
            started_at: chrono_now_ms(),
        };
        self.lock().insert(id.clone(), (info.clone(), ctx.clone()));
        let _ = TaskEvent(info).emit(app);

        let registry = self.clone();
        let app2 = app.clone();
        let tid = id.clone();
        let ticker_ctx = ctx.clone();
        let ticker_registry = self.clone();
        let ticker_app = app.clone();
        let ticker_id = id.clone();
        let ticker = tauri::async_runtime::spawn(async move {
            let mut last: Option<ProgressSnapshot> = None;
            loop {
                tokio::time::sleep(Duration::from_millis(150)).await;
                let snap = ticker_ctx.progress.snapshot();
                if last.as_ref() != Some(&snap) {
                    let info = ticker_registry.lock().get(&ticker_id).map(|(i, _)| TaskInfo { progress: snap.clone(), ..i.clone() });
                    if let Some(i) = info {
                        let _ = TaskEvent(i).emit(&ticker_app);
                    }
                    last = Some(snap);
                }
            }
        });
        tauri::async_runtime::spawn(async move {
            let result = f(ctx.clone()).await;
            ticker.abort();
            let info = {
                let mut map = registry.lock();
                let Some((info, c)) = map.get_mut(&tid) else { return };
                info.progress = c.progress.snapshot();
                match result {
                    Ok(r) => {
                        info.status = TaskStatus::Done;
                        info.result = r;
                    }
                    Err(launcher_core::Error::Cancelled) => info.status = TaskStatus::Cancelled,
                    Err(e) => {
                        tracing::warn!(task = %info.kind, error = %e, "task failed");
                        info.status = TaskStatus::Failed;
                        info.error = Some(e.info());
                    }
                }
                info.clone()
            };
            let _ = TaskEvent(info).emit(&app2);
        });
        id
    }
}

pub fn chrono_now_ms() -> f64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as f64).unwrap_or(0.0)
}
