//! Peer-pointer discipline shared by every JNI module.

use std::os::raw::c_void;

use parking_lot::Mutex;

use crate::error::{connection_closed, Failure};

/// Native handles are `Box::into_raw` peers. Every `*_from_java` returns an
/// owned pointer; every free is NULL-tolerant and idempotent.
/// INVARIANT: a handle is freed exactly once; use-after-free is impossible
/// from Java because the Java wrapper nulls its `long` on close and guards
/// every call.
pub(crate) struct ConnHandle(pub(crate) Mutex<Option<laminar_db::api::Connection>>);

impl ConnHandle {
    pub(crate) fn new(conn: laminar_db::api::Connection) -> Self {
        Self(Mutex::new(Some(conn)))
    }
}

/// Resolves a connection peer pointer, mapping a null handle to
/// `LaminarConnectionException` (code 101) instead of panicking across the
/// JNI boundary.
pub(crate) fn conn<'a>(ptr: *mut c_void) -> Result<&'a ConnHandle, Failure> {
    if ptr.is_null() {
        return Err(Failure::Api(connection_closed()));
    }
    // SAFETY: the pointer is a live `Box::into_raw` peer; Java nulls its long
    // under lock before freeing, so a non-null pointer is never dangling.
    Ok(unsafe { &*(ptr as *const ConnHandle) })
}
