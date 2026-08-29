//! Connection lifecycle and SQL natives over
//! `Java_io_laminardb_internal_Native` (plan 02 §2).

use std::os::raw::c_void;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong};
use jni::{Env, EnvUnowned};

use crate::config::read_string;
use crate::error::{connection_closed, Failure, ThrowLaminar};
use crate::handle::{conn, ConnHandle, ExecHandle};
use crate::runtime::runtime;

fn open(
    open: impl FnOnce() -> Result<laminar_db::api::Connection, laminar_db::api::ApiError>,
) -> Result<jlong, Failure> {
    let runtime = runtime()?;
    let _guard = runtime.enter();
    let connection = open()?;
    Ok(Box::into_raw(Box::new(ConnHandle::new(connection))) as jlong)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_openDefault<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> jlong {
    let outcome = unowned_env.with_env(|_| open(laminar_db::api::Connection::open));
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_openWithConfig<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    config_ptr: jlong,
) -> jlong {
    // The config handle stays owned by LaminarConfig; the value is cloned so
    // one config can open several connections.
    let outcome = unowned_env.with_env(|_| {
        let cloned = crate::handle::typed::<laminar_db::api::LaminarConfig>(
            config_ptr as *mut c_void,
            "config",
        )?
        .with(|inner| inner.cloned());
        let config = cloned.ok_or_else(|| {
            Failure::Api(laminar_db::api::ApiError::internal(
                "config handle is empty (already closed)".to_string(),
            ))
        })?;
        open(move || laminar_db::api::Connection::open_with_config(config))
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_execute<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
    sql: JString<'caller>,
) -> jlong {
    let outcome = unowned_env.with_env(|env| execute(env, conn_ptr, &sql));
    outcome.resolve::<ThrowLaminar>()
}

fn execute(env: &mut Env<'_>, conn_ptr: jlong, sql: &JString<'_>) -> Result<jlong, Failure> {
    let handle = conn(conn_ptr as *mut c_void)?;
    let sql = read_string(env, sql)?;
    let runtime = runtime()?;
    let _guard = runtime.enter();
    let result = handle.with(|inner| {
        inner
            .ok_or_else(|| Failure::Api(connection_closed()))
            .and_then(|c| c.execute(&sql).map_err(Failure::Api))
    })?;
    Ok(Box::into_raw(Box::new(ExecHandle::new(result))) as jlong)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_close<'caller>(
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
    // once per live handle.
    let handle = unsafe { Box::from_raw(conn_ptr as *mut ConnHandle) };
    // `Connection::close(self)` consumes the connection: take it out of the
    // interior so a second close finds `None` and is a no-op.
    let Some(connection) = handle.take() else {
        return Ok(());
    };
    let runtime = runtime()?;
    let _guard = runtime.enter();
    connection.close()?;
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_isClosed<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
) -> jboolean {
    let outcome = unowned_env.with_env(|_| {
        let closed = conn(conn_ptr as *mut c_void)?.with(|inner| inner.is_none());
        Ok::<_, Failure>(closed)
    });
    outcome.resolve::<ThrowLaminar>()
}

macro_rules! conn_call {
    ($(#[$meta:meta])* $name:ident, $call:expr) => {
        $(#[$meta])*
        #[unsafe(no_mangle)]
        pub extern "system" fn $name<'caller>(
            mut unowned_env: EnvUnowned<'caller>,
            _class: JClass<'caller>,
            conn_ptr: jlong,
        ) {
            let outcome = unowned_env.with_env(|_| {
                let runtime = runtime()?;
                let _guard = runtime.enter();
                conn(conn_ptr as *mut c_void)?.with(|inner| {
                    inner
                        .ok_or_else(|| Failure::Api(connection_closed()))
                        .and_then(|c| $call(c).map_err(Failure::Api))
                })?;
                Ok::<_, Failure>(())
            });
            outcome.resolve::<ThrowLaminar>();
        }
    };
}

conn_call!(
    /// Starts all pipelines; blocking.
    Java_io_laminardb_internal_Native_start,
    |c: &laminar_db::api::Connection| c.start()
);
conn_call!(
    /// Shuts the engine down without consuming the connection handle.
    Java_io_laminardb_internal_Native_shutdown,
    |c: &laminar_db::api::Connection| c.shutdown()
);

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_checkpoint<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
) -> jlong {
    let outcome = unowned_env.with_env(|_| {
        let runtime = runtime()?;
        let _guard = runtime.enter();
        let id = conn(conn_ptr as *mut c_void)?.with(|inner| {
            inner
                .ok_or_else(|| Failure::Api(connection_closed()))
                .and_then(|c| c.checkpoint().map_err(Failure::Api))
        })?;
        Ok::<_, Failure>(id as jlong)
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_isCheckpointEnabled<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
) -> jboolean {
    let outcome = unowned_env.with_env(|_| {
        let enabled = conn(conn_ptr as *mut c_void)?
            .with(|inner| inner.is_some_and(|c| c.is_checkpoint_enabled()));
        Ok::<_, Failure>(enabled)
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_version<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> jni::sys::jstring {
    let outcome = unowned_env.with_env(|env| {
        Ok::<_, Failure>(JString::from_str(env, env!("CARGO_PKG_VERSION"))?.as_raw())
    });
    outcome.resolve::<ThrowLaminar>()
}
