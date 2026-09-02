//! JNI bindings over `laminar_db::api` for the laminardb-java Maven artifact.

mod arrow_jni;
mod callback;
mod catalog;
mod config;
mod connection;
mod error;
mod handle;
mod query;
mod runtime;
mod subscription;
mod writer;

use std::ffi::c_void;

use jni::sys;

/// INVARIANT: `JNI_OnLoad` only caches the `JavaVM` — no I/O, no engine
/// construction (plan 01 Task 0.2).
#[unsafe(no_mangle)]
#[allow(clippy::not_unsafe_ptr_arg_deref)] // WHY: the JNI spec fixes this symbol's signature; only the JVM invokes it.
pub extern "system" fn JNI_OnLoad(_vm: *mut sys::JavaVM, _reserved: *mut c_void) -> i32 {
    // The JVM's JavaVM pointer is not cached: Phase 2's callback bridge
    // obtains its own reference via env.get_java_vm() at bridge-init time.
    sys::JNI_VERSION_1_8
}
