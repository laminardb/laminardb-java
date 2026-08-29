//! JNI bindings over `laminar_db::api` for the laminardb-java Maven artifact.

mod arrow_jni;
mod connection;
mod error;
mod handle;
mod runtime;

use std::ffi::c_void;
use std::sync::OnceLock;

use jni::{sys, JavaVM};

#[allow(dead_code)] // WHY: read by Phase 2's Rust→JVM callbacks (plan 03 §4); Phase 0 only caches it.
static VM: OnceLock<JavaVM> = OnceLock::new();

/// INVARIANT: `JNI_OnLoad` only caches the `JavaVM` — no I/O, no engine
/// construction (plan 01 Task 0.2).
#[unsafe(no_mangle)]
#[allow(clippy::not_unsafe_ptr_arg_deref)] // WHY: the JNI spec fixes this symbol's signature; only the JVM invokes it.
pub extern "system" fn JNI_OnLoad(vm: *mut sys::JavaVM, _reserved: *mut c_void) -> i32 {
    // SAFETY: the JVM passes a valid JavaVM pointer to JNI_OnLoad; the
    // wrapper has no Drop, so caching it never destroys the VM.
    let vm = unsafe { JavaVM::from_raw(vm) };
    let _ = VM.set(vm);
    sys::JNI_VERSION_1_8
}
