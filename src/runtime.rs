//! Process-global Tokio runtime, modeled on
//! `laminardb-python/src/async_support.rs`.

use std::sync::OnceLock;

use tokio::runtime::Runtime;

use laminar_db::api::ApiError;

static RUNTIME: OnceLock<Option<Runtime>> = OnceLock::new();

/// One multi-thread runtime for the process, created on first connection.
/// Blocking `api` calls run on the calling Java thread; the runtime must be
/// *entered* around them because the core spawns background tokio tasks.
///
/// INVARIANT (core v0.30.0, verified in `api/connection/mod.rs` and
/// `api/subscription.rs`): the blocking named-stream `subscribe()` and
/// `next_frame()` reject being called inside a runtime context — the Phase 2
/// subscription paths must stay outside the enter guard.
pub(crate) fn runtime() -> Result<&'static Runtime, ApiError> {
    let cached = RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .ok()
    });
    cached.as_ref().ok_or_else(|| {
        ApiError::internal("failed to create the process-wide tokio runtime".to_string())
    })
}
