//! Peer-pointer discipline shared by every JNI module.

use std::os::raw::c_void;

use parking_lot::Mutex;

use crate::error::{connection_closed, Failure};

pub(crate) type ConnHandle = NativeHandle<laminar_db::api::Connection>;
pub(crate) type ConfigHandle = NativeHandle<laminar_db::api::LaminarConfig>;
pub(crate) type ExecHandle = NativeHandle<laminar_db::api::ExecuteResult>;
pub(crate) type QueryResultHandle = NativeHandle<laminar_db::api::QueryResult>;
pub(crate) type QueryStreamHandle = NativeHandle<laminar_db::api::QueryStream>;
pub(crate) type WriterHandle = NativeHandle<laminar_db::api::Writer>;

/// Native handles are `Box::into_raw` peers. Every constructor returns an
/// owned pointer; every free is NULL-tolerant and idempotent (the interior
/// `Option` is taken exactly once).
/// INVARIANT: a handle is freed exactly once; use-after-free is impossible
/// from Java because the Java wrapper nulls its `long` on close and guards
/// every call.
pub(crate) struct NativeHandle<T>(pub Mutex<Option<T>>);

impl<T> NativeHandle<T> {
    pub(crate) fn new(value: T) -> Self {
        Self(Mutex::new(Some(value)))
    }

    /// Runs `f` against the interior value under one lock guard. Checking
    /// state and acting under a single guard avoids close races (the
    /// Python-binding lesson carried over).
    pub(crate) fn with<R>(&self, f: impl FnOnce(Option<&T>) -> R) -> R {
        let guard = self.0.lock();
        f(guard.as_ref())
    }

    /// Mutable variant of [`NativeHandle::with`]; same one-guard rule.
    pub(crate) fn with_mut<R>(&self, f: impl FnOnce(Option<&mut T>) -> R) -> R {
        let mut guard = self.0.lock();
        f(guard.as_mut())
    }

    /// Takes the interior value out exactly once; `None` on a second take.
    pub(crate) fn take(&self) -> Option<T> {
        self.0.lock().take()
    }
}

/// Resolves a connection peer pointer, mapping a null handle to
/// `LaminarConnectionException` (code 101) instead of panicking across the
/// JNI boundary.
pub(crate) fn conn(ptr: *mut c_void) -> Result<&'static ConnHandle, Failure> {
    resolve(ptr).ok_or_else(|| Failure::Api(connection_closed()))
}

/// Resolves a non-connection peer pointer; null maps to an internal error
/// naming the handle kind — only a connection's lifetime rules produce the
/// 101 contract from Java.
pub(crate) fn typed<T>(ptr: *mut c_void, kind: &str) -> Result<&'static NativeHandle<T>, Failure> {
    resolve(ptr).ok_or_else(|| {
        Failure::Api(laminar_db::api::ApiError::internal(format!(
            "{kind} handle is null"
        )))
    })
}

/// SAFETY contract for both resolvers: the pointer is a live
/// `Box::into_raw` peer; Java nulls its long under lock before freeing, so a
/// non-null pointer is never dangling.
fn resolve<T>(ptr: *mut c_void) -> Option<&'static NativeHandle<T>> {
    if ptr.is_null() {
        return None;
    }
    Some(unsafe { &*(ptr as *const NativeHandle<T>) })
}
