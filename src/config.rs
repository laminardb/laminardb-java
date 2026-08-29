//! Native config builder (plan 02 §2): `configNew` → setters →
//! `openWithConfig` → `configDrop`. The Java side never mirrors the struct.

use std::os::raw::c_void;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong};
use jni::{Env, EnvUnowned};

use laminar_core::streaming::StreamCheckpointConfig;

use crate::error::{Failure, ThrowLaminar};
use crate::handle::{typed, ConfigHandle};

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_configNew<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> jlong {
    let outcome = unowned_env.with_env(|_| {
        Ok::<_, Failure>(Box::into_raw(Box::new(ConfigHandle::new(
            laminar_db::api::LaminarConfig::default(),
        ))) as jlong)
    });
    outcome.resolve::<ThrowLaminar>()
}

fn config<'a>(ptr: jlong) -> Result<&'a ConfigHandle, Failure> {
    typed(ptr as *mut c_void, "config")
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_configSetBufferSize<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    config_ptr: jlong,
    value: jlong,
) {
    let outcome =
        unowned_env.with_env(|_| set(config_ptr, |c| c.default_buffer_size = value as usize));
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_configSetStorageDir<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    config_ptr: jlong,
    path: JString<'caller>,
) {
    let outcome = unowned_env.with_env(|env| {
        let path = read_optional_string(env, &path)?;
        set(config_ptr, |c| c.storage_dir = path.map(Into::into))
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_configSetCheckpointIntervalMs<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    config_ptr: jlong,
    interval_ms: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        // 0 disables checkpointing — the documented Phase 1 convention.
        set(config_ptr, |c| {
            c.checkpoint = if interval_ms == 0 {
                None
            } else {
                Some(StreamCheckpointConfig {
                    interval_ms: Some(interval_ms as u64),
                    ..StreamCheckpointConfig::default()
                })
            };
        })
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_configSetIncrementalEmit<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    config_ptr: jlong,
    value: jboolean,
) {
    let outcome =
        // jni 0.22 maps jboolean to bool directly.
        unowned_env.with_env(|_| set(config_ptr, |c| c.incremental_emit = value));
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_configSetObjectStoreUrl<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    config_ptr: jlong,
    url: JString<'caller>,
) {
    let outcome = unowned_env.with_env(|env| {
        let url = read_optional_string(env, &url)?;
        set(config_ptr, |c| c.object_store_url = url)
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_configSetObjectStoreOption<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    config_ptr: jlong,
    key: JString<'caller>,
    value: JString<'caller>,
) {
    let outcome = unowned_env.with_env(|env| {
        let key = read_string(env, &key)?;
        let value = read_optional_string(env, &value)?;
        set(config_ptr, |c| match value {
            // A null value removes the key — the documented convention.
            Some(v) => {
                c.object_store_options.insert(key, v);
            }
            None => {
                c.object_store_options.remove(&key);
            }
        })
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_configDrop<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    config_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        if config_ptr != 0 {
            // SAFETY: Java nulls its long before any other call, so this runs
            // exactly once per live handle.
            let handle = unsafe { Box::from_raw(config_ptr as *mut ConfigHandle) };
            let _ = handle.take();
        }
        Ok::<_, Failure>(())
    });
    outcome.resolve::<ThrowLaminar>();
}

fn set(
    config_ptr: jlong,
    f: impl FnOnce(&mut laminar_db::api::LaminarConfig),
) -> Result<(), Failure> {
    config(config_ptr)?.with_mut(|inner| {
        if let Some(config) = inner {
            f(config);
        }
    });
    Ok(())
}

pub(crate) fn read_string(env: &mut Env<'_>, value: &JString<'_>) -> Result<String, Failure> {
    Ok(value.mutf8_chars(env)?.to_string())
}

pub(crate) fn read_optional_string(
    env: &mut Env<'_>,
    value: &JString<'_>,
) -> Result<Option<String>, Failure> {
    if value.is_null() {
        return Ok(None);
    }
    Ok(Some(read_string(env, value)?))
}
