//! Query and execute-result natives over `Java_io_laminardb_internal_Native`
//! (plan 02 §2).

use std::os::raw::c_void;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong};
use jni::{Env, EnvUnowned};

use laminar_db::api::ExecuteResult;

use crate::arrow_jni::{export_batch, export_schema};
use crate::config::read_string;
use crate::error::{connection_closed, wrong_kind, Failure, ThrowLaminar};
use crate::handle::{conn, typed, ExecHandle, QueryResultHandle, QueryStreamHandle};
use crate::runtime::runtime;

// ---- execute result handle ----

/// Kind ordinals, mirrored by `ExecuteResult.Kind` on the Java side.
const KIND_DDL: jint = 0;
const KIND_QUERY: jint = 1;
const KIND_ROWS_AFFECTED: jint = 2;
const KIND_METADATA: jint = 3;

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_executeKind<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    exec_ptr: jlong,
) -> jint {
    let outcome = unowned_env.with_env(|_| {
        let kind =
            typed::<ExecuteResult>(exec_ptr as *mut c_void, "execute result")?.with(|inner| {
                inner.map(|result| match result {
                    ExecuteResult::Ddl(_) => KIND_DDL,
                    ExecuteResult::Query(_) => KIND_QUERY,
                    ExecuteResult::RowsAffected(_) => KIND_ROWS_AFFECTED,
                    ExecuteResult::Metadata(_) => KIND_METADATA,
                })
            });
        kind.ok_or_else(|| Failure::Api(wrong_kind("a result")))
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_executeDdlObject<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    exec_ptr: jlong,
) -> jni::sys::jstring {
    let outcome = unowned_env.with_env(|env| {
        let name =
            typed::<ExecuteResult>(exec_ptr as *mut c_void, "execute result")?.with(|inner| {
                match inner {
                    Some(ExecuteResult::Ddl(info)) => Ok(info.object_name.clone()),
                    Some(_) => Err(Failure::Api(wrong_kind("a DDL result"))),
                    None => Err(Failure::Api(wrong_kind("a result"))),
                }
            })?;
        Ok::<_, Failure>(JString::from_str(env, &name)?.as_raw())
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_executeRowsAffected<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    exec_ptr: jlong,
) -> jlong {
    let outcome = unowned_env.with_env(|_| {
        typed::<ExecuteResult>(exec_ptr as *mut c_void, "execute result")?.with(|inner| match inner
        {
            Some(ExecuteResult::RowsAffected(n)) => Ok::<_, Failure>(*n as jlong),
            Some(_) => Err(Failure::Api(wrong_kind("a rows-affected result"))),
            None => Err(Failure::Api(wrong_kind("a result"))),
        })
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_executeFree<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    exec_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        if exec_ptr != 0 {
            // SAFETY: Java nulls its long before any other call, so this runs
            // exactly once per live handle.
            let handle = unsafe { Box::from_raw(exec_ptr as *mut ExecHandle) };
            let _ = handle.take();
        }
        Ok::<_, Failure>(())
    });
    outcome.resolve::<ThrowLaminar>();
}

// ---- query / query stream construction ----

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_query<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
    sql: JString<'caller>,
) -> jlong {
    let outcome =
        unowned_env.with_env(|env| construct(env, conn_ptr, &sql, QueryStyle::Materialized));
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_queryStream<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
    sql: JString<'caller>,
) -> jlong {
    let outcome = unowned_env.with_env(|env| construct(env, conn_ptr, &sql, QueryStyle::Streaming));
    outcome.resolve::<ThrowLaminar>()
}

enum QueryStyle {
    Materialized,
    Streaming,
}

fn construct(
    env: &mut Env<'_>,
    conn_ptr: jlong,
    sql: &JString<'_>,
    style: QueryStyle,
) -> Result<jlong, Failure> {
    let handle = conn(conn_ptr as *mut c_void)?;
    let sql = read_string(env, sql)?;
    let runtime = runtime()?;
    let _guard = runtime.enter();
    match style {
        QueryStyle::Materialized => {
            // The core's `Connection::query` collects via non-blocking
            // `try_next`, which can miss batches that are not yet ready at
            // poll time (verified at the pin). Materialize with blocking
            // `next` instead so bounded queries are complete.
            let result = handle.with(|inner| {
                let c = inner.ok_or_else(|| Failure::Api(connection_closed()))?;
                let executed = c.execute(&sql)?;
                match executed {
                    laminar_db::api::ExecuteResult::Query(mut stream) => {
                        let schema = stream.schema();
                        let mut batches = Vec::new();
                        while let Some(batch) = stream.next()? {
                            batches.push(batch);
                        }
                        Ok(laminar_db::api::QueryResult::from_batches(schema, batches))
                    }
                    laminar_db::api::ExecuteResult::Metadata(batch) => {
                        Ok(laminar_db::api::QueryResult::from_batch(batch))
                    }
                    other => Err(Failure::Api(laminar_db::api::ApiError::query(format!(
                        "expected a query result, got {other:?}"
                    )))),
                }
            })?;
            Ok(Box::into_raw(Box::new(QueryResultHandle::new(result))) as jlong)
        }
        QueryStyle::Streaming => {
            let stream = handle.with(|inner| {
                inner
                    .ok_or_else(|| Failure::Api(connection_closed()))
                    .and_then(|c| c.query_stream(&sql).map_err(Failure::Api))
            })?;
            Ok(Box::into_raw(Box::new(QueryStreamHandle::new(stream))) as jlong)
        }
    }
}

// ---- QueryResult → Arrow ----

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_resultSchemaExport<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    result_ptr: jlong,
    schema_addr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        typed::<laminar_db::api::QueryResult>(result_ptr as *mut c_void, "query result")?.with(
            |inner| match inner {
                Some(result) => export_schema(schema_addr, &result.schema()),
                None => Err(Failure::Api(wrong_kind("a query result"))),
            },
        )
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_resultNumRows<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    result_ptr: jlong,
) -> jlong {
    let outcome = unowned_env.with_env(|_| {
        typed::<laminar_db::api::QueryResult>(result_ptr as *mut c_void, "query result")?.with(
            |inner| match inner {
                Some(result) => Ok::<_, Failure>(result.num_rows() as jlong),
                None => Err(Failure::Api(wrong_kind("a query result"))),
            },
        )
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_resultNumBatches<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    result_ptr: jlong,
) -> jint {
    let outcome = unowned_env.with_env(|_| {
        typed::<laminar_db::api::QueryResult>(result_ptr as *mut c_void, "query result")?.with(
            |inner| match inner {
                Some(result) => Ok::<_, Failure>(result.num_batches() as jint),
                None => Err(Failure::Api(wrong_kind("a query result"))),
            },
        )
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_resultExportBatch<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    result_ptr: jlong,
    index: jint,
    array_addr: jlong,
    schema_addr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        typed::<laminar_db::api::QueryResult>(result_ptr as *mut c_void, "query result")?.with(
            |inner| match inner {
                Some(result) => match result.batch(index as usize) {
                    Some(batch) => export_batch(array_addr, schema_addr, batch),
                    None => Err(Failure::Api(laminar_db::api::ApiError::internal(format!(
                        "batch index {index} out of bounds"
                    )))),
                },
                None => Err(Failure::Api(wrong_kind("a query result"))),
            },
        )
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_resultFree<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    result_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        if result_ptr != 0 {
            // SAFETY: exactly-once free per live handle (Java nulls first).
            let handle = unsafe { Box::from_raw(result_ptr as *mut QueryResultHandle) };
            let _ = handle.take();
        }
        Ok::<_, Failure>(())
    });
    outcome.resolve::<ThrowLaminar>();
}

// ---- QueryStream ----

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_streamSchemaExport<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    stream_ptr: jlong,
    schema_addr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        typed::<laminar_db::api::QueryStream>(stream_ptr as *mut c_void, "query stream")?.with(
            |inner| match inner {
                Some(stream) => export_schema(schema_addr, &stream.schema()),
                None => Err(Failure::Api(wrong_kind("a query stream"))),
            },
        )
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_streamNext<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    stream_ptr: jlong,
    array_addr: jlong,
    schema_addr: jlong,
) -> jint {
    let outcome = unowned_env.with_env(|_| next(stream_ptr, array_addr, schema_addr, false));
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_streamTryNext<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    stream_ptr: jlong,
    array_addr: jlong,
    schema_addr: jlong,
) -> jint {
    let outcome = unowned_env.with_env(|_| next(stream_ptr, array_addr, schema_addr, true));
    outcome.resolve::<ThrowLaminar>()
}

/// Returns 1 when a data batch was exported into the Java-allocated structs,
/// 0 when no batch is available (blocking variant: end of stream; poll
/// variant: nothing ready — Java distinguishes via `streamIsActive`).
/// Failures throw; the return value is then meaningless.
fn next(
    stream_ptr: jlong,
    array_addr: jlong,
    schema_addr: jlong,
    non_blocking: bool,
) -> Result<jint, Failure> {
    let handle = typed::<laminar_db::api::QueryStream>(stream_ptr as *mut c_void, "query stream")?;
    handle.with_mut(|stream| -> Result<jint, Failure> {
        // A taken interior means the Java side already closed this stream:
        // report end-of-stream.
        let Some(stream) = stream else {
            return Ok(0);
        };
        let batch = if non_blocking {
            stream.try_next()?
        } else {
            stream.next()?
        };
        match batch {
            Some(batch) => {
                export_batch(array_addr, schema_addr, &batch)?;
                Ok(1)
            }
            None => Ok(0),
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_streamIsActive<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    stream_ptr: jlong,
) -> jboolean {
    let outcome = unowned_env.with_env(|_| {
        let active =
            typed::<laminar_db::api::QueryStream>(stream_ptr as *mut c_void, "query stream")?
                .with(|inner| inner.is_some_and(|s| s.is_active()));
        Ok::<_, Failure>(active)
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_streamCancel<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    stream_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        typed::<laminar_db::api::QueryStream>(stream_ptr as *mut c_void, "query stream")?.with_mut(
            |stream| {
                if let Some(s) = stream {
                    s.cancel();
                }
            },
        );
        Ok::<_, Failure>(())
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_streamFree<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    stream_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        if stream_ptr != 0 {
            // SAFETY: exactly-once free per live handle (Java nulls first).
            let handle = unsafe { Box::from_raw(stream_ptr as *mut QueryStreamHandle) };
            let _ = handle.take();
        }
        Ok::<_, Failure>(())
    });
    outcome.resolve::<ThrowLaminar>();
}
