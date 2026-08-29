//! Phase 0 JNI surface over `Java_io_laminardb_internal_Native` (plan 01
//! Task 0.2): `openDefault`, `executeSql`, `closeConnection`, `version`.

use std::os::raw::c_void;

use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::{Env, EnvUnowned};

use crate::error::{Failure, ThrowLaminar};
use crate::handle::{conn, ConnHandle};
use crate::runtime::runtime;

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_openDefault<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> jlong {
    let outcome = unowned_env.with_env(|_| open());
    outcome.resolve::<ThrowLaminar>()
}

fn open() -> Result<jlong, Failure> {
    let runtime = runtime()?;
    let _guard = runtime.enter();
    let connection = laminar_db::api::Connection::open()?;
    Ok(Box::into_raw(Box::new(ConnHandle::new(connection))) as jlong)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_executeSql<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
    sql: JString<'caller>,
) {
    let outcome = unowned_env.with_env(|env| execute(env, conn_ptr, &sql));
    outcome.resolve::<ThrowLaminar>();
}

fn execute(env: &mut Env<'_>, conn_ptr: jlong, sql: &JString<'_>) -> Result<(), Failure> {
    let handle = conn(conn_ptr as *mut c_void)?;
    let sql = sql.mutf8_chars(env)?.to_string();
    // State check and core call under one guard: dropping and reacquiring
    // creates a close race (Python-binding lesson carried over).
    let guard = handle.0.lock();
    let Some(connection) = guard.as_ref() else {
        return Err(Failure::Api(crate::error::connection_closed()));
    };
    connection.execute(&sql)?;
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_closeConnection<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| close(conn_ptr));
    outcome.resolve::<ThrowLaminar>();
}

fn close(conn_ptr: jlong) -> Result<(), Failure> {
    if conn_ptr == 0 {
        return Ok(());
    }
    // SAFETY: Java nulls its long before any other call, so this runs exactly
    // once per live handle; a second run would first fail the null check.
    let handle = unsafe { Box::from_raw(conn_ptr as *mut ConnHandle) };
    // `Connection::close(self)` consumes the connection: take it out of the
    // interior so a second close finds `None` and is a no-op.
    let Some(connection) = handle.0.lock().take() else {
        return Ok(());
    };
    connection.close()?;
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_version<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> jstring {
    let outcome = unowned_env.with_env(|env| version(env));
    outcome.resolve::<ThrowLaminar>()
}

fn version(env: &mut Env<'_>) -> Result<jstring, Failure> {
    Ok(JString::from_str(env, env!("CARGO_PKG_VERSION"))?.as_raw())
}
