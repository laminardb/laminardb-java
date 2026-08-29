//! Ingestion natives over `Java_io_laminardb_internal_Native` (plan 02 §2).

use std::os::raw::c_void;

use jni::objects::{JClass, JString};
use jni::sys::jlong;
use jni::{Env, EnvUnowned};

use crate::arrow_jni::{export_schema, import_batch};
use crate::config::read_string;
use crate::error::{connection_closed, writer_closed, Failure, ThrowLaminar};
use crate::handle::{conn, typed, WriterHandle};
use crate::runtime::runtime;

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_insert<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
    source: JString<'caller>,
    array_addr: jlong,
    schema_addr: jlong,
) -> jlong {
    let outcome =
        unowned_env.with_env(|env| insert(env, conn_ptr, &source, array_addr, schema_addr));
    outcome.resolve::<ThrowLaminar>()
}

fn insert(
    env: &mut Env<'_>,
    conn_ptr: jlong,
    source: &JString<'_>,
    array_addr: jlong,
    schema_addr: jlong,
) -> Result<jlong, Failure> {
    let handle = conn(conn_ptr as *mut c_void)?;
    let source = read_string(env, source)?;
    // Import before locking: on a schema mismatch the connection stays
    // untouched and the batch memory is still fully consumed.
    let batch = import_batch(array_addr, schema_addr)?;
    let runtime = runtime()?;
    let _guard = runtime.enter();
    let rows = handle.with(|inner| {
        inner
            .ok_or_else(|| Failure::Api(connection_closed()))
            .and_then(|c| c.insert(&source, batch).map_err(Failure::Api))
    })?;
    Ok(rows as jlong)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_writerCreate<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
    source: JString<'caller>,
) -> jlong {
    let outcome = unowned_env.with_env(|env| {
        let handle = conn(conn_ptr as *mut c_void)?;
        let source = read_string(env, &source)?;
        let runtime = runtime()?;
        let _guard = runtime.enter();
        let writer = handle.with(|inner| {
            inner
                .ok_or_else(|| Failure::Api(connection_closed()))
                .and_then(|c| c.writer(&source).map_err(Failure::Api))
        })?;
        Ok::<_, Failure>(Box::into_raw(Box::new(WriterHandle::new(writer))) as jlong)
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_writerWrite<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    writer_ptr: jlong,
    array_addr: jlong,
    schema_addr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        let batch = import_batch(array_addr, schema_addr)?;
        let runtime = runtime()?;
        let _guard = runtime.enter();
        typed::<laminar_db::api::Writer>(writer_ptr as *mut c_void, "writer")?.with_mut(|writer| {
            match writer {
                Some(w) => w.write(batch).map_err(Failure::Api),
                None => Err(Failure::Api(writer_closed())),
            }
        })
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_writerFlush<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    writer_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        let runtime = runtime()?;
        let _guard = runtime.enter();
        typed::<laminar_db::api::Writer>(writer_ptr as *mut c_void, "writer")?.with_mut(|writer| {
            match writer {
                Some(w) => w.flush().map_err(Failure::Api),
                None => Err(Failure::Api(writer_closed())),
            }
        })
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_writerWatermark<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    writer_ptr: jlong,
    timestamp: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        let runtime = runtime()?;
        let _guard = runtime.enter();
        typed::<laminar_db::api::Writer>(writer_ptr as *mut c_void, "writer")?.with_mut(|writer| {
            match writer {
                Some(w) => {
                    w.watermark(timestamp);
                    Ok::<_, Failure>(())
                }
                None => Err(Failure::Api(writer_closed())),
            }
        })
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_writerCurrentWatermark<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    writer_ptr: jlong,
) -> jlong {
    let outcome = unowned_env.with_env(|_| {
        let watermark = typed::<laminar_db::api::Writer>(writer_ptr as *mut c_void, "writer")?
            .with(|inner| inner.map(|w| w.current_watermark()).unwrap_or(i64::MIN));
        Ok::<_, Failure>(watermark)
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_writerSchemaExport<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    writer_ptr: jlong,
    schema_addr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        typed::<laminar_db::api::Writer>(writer_ptr as *mut c_void, "writer")?.with(|inner| {
            match inner {
                Some(w) => export_schema(schema_addr, &w.schema()),
                None => Err(Failure::Api(writer_closed())),
            }
        })
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_writerClose<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    writer_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        if writer_ptr == 0 {
            return Ok::<_, Failure>(());
        }
        // SAFETY: Java nulls its long before any other call, so this runs
        // exactly once per live handle.
        let handle = unsafe { Box::from_raw(writer_ptr as *mut WriterHandle) };
        // `Writer::close(self)` consumes: the Option-take makes a second
        // close a no-op instead of a double-drop.
        if let Some(writer) = handle.take() {
            let runtime = runtime()?;
            let _guard = runtime.enter();
            writer.close().map_err(Failure::Api)?;
        }
        Ok(())
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_writerFree<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    writer_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        if writer_ptr != 0 {
            // Leak backstop only — the public path is writerClose.
            // SAFETY: exactly-once free per live handle (Java nulls first).
            let handle = unsafe { Box::from_raw(writer_ptr as *mut WriterHandle) };
            let _ = handle.take();
        }
        Ok::<_, Failure>(())
    });
    outcome.resolve::<ThrowLaminar>();
}
