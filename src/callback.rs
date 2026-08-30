//! Callback (push) subscriptions (plan 03 §4). One dedicated OS thread per
//! subscription polls its source with adaptive backoff (0.5–5 ms band) and
//! delivers whole batches through the `CallbackBridge` static upcalls — one
//! JNI crossing per batch, never per row (D7). The Java side owns the
//! listener (reachable from the bridge's registry), so no Rust-held global
//! object refs are needed beyond the bridge class itself.

use std::os::raw::c_void;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock};
use std::thread::JoinHandle;
use std::time::Duration;

use jni::objects::{Global, JClass, JStaticMethodID, JString};
use jni::sys::{jboolean, jlong};
use jni::{jni_sig, jni_str, Env, EnvUnowned, JavaVM};

use arrow::array::RecordBatch;

use laminar_db::api::QueryStream;

use crate::arrow_jni::export_batch;
use crate::config::read_string;
use crate::error::{connection_closed, Failure, ThrowLaminar};
use crate::handle::{conn, typed, NativeHandle};

/// Bounded concurrent callback subscriptions (plan 03 §4).
const MAX_CALLBACK_SUBSCRIPTIONS: usize = 64;
static ACTIVE: AtomicUsize = AtomicUsize::new(0);

/// Backoff band for idle polling (plan 03 §7 records the chosen parameters).
const BACKOFF_FLOOR: Duration = Duration::from_micros(500);
const BACKOFF_CEILING: Duration = Duration::from_millis(5);

pub(crate) enum Source {
    QueryStream(NativeHandle<QueryStream>),
    NamedStream(crate::subscription::SubHandle),
}

type ExitSignal = Arc<(std::sync::Mutex<bool>, std::sync::Condvar)>;

pub(crate) struct CallbackState {
    stop: Arc<AtomicBool>,
    /// Worker liveness, waited on WITHOUT the handle lock (the worker needs
    /// that lock to poll; a raw join blocks forever if the worker is parked
    /// in a listener upcall — bounded waits turn any miss into a report).
    exited: ExitSignal,
    _worker: Option<JoinHandle<()>>,
}

fn new_exit_signal() -> ExitSignal {
    Arc::new((std::sync::Mutex::new(false), std::sync::Condvar::new()))
}

struct Bridge {
    vm: JavaVM,
    class: Global<JClass<'static>>,
    deliver_data: JStaticMethodID,
    deliver_error: JStaticMethodID,
    deliver_close: JStaticMethodID,
}

static BRIDGE: OnceLock<Bridge> = OnceLock::new();

/// Caches the bridge class and its static method IDs. Called from a JNI
/// thread (subscribeCallback); retried on failure.
fn bridge(env: &mut Env<'_>) -> Result<&'static Bridge, Failure> {
    if let Some(cached) = BRIDGE.get() {
        return Ok(cached);
    }
    let vm = env.get_java_vm().map_err(Failure::Jni)?;
    let class = env
        .find_class(jni_str!("io/laminardb/internal/CallbackBridge"))
        .map_err(Failure::Jni)?;
    let deliver_data = env
        .get_static_method_id(&class, jni_str!("deliverData"), jni_sig!("(J)[J"))
        .map_err(Failure::Jni)?;
    let deliver_error = env
        .get_static_method_id(
            &class,
            jni_str!("deliverError"),
            jni_sig!("(JLjava/lang/String;I)V"),
        )
        .map_err(Failure::Jni)?;
    let deliver_close = env
        .get_static_method_id(&class, jni_str!("deliverClose"), jni_sig!("(J)V"))
        .map_err(Failure::Jni)?;
    let global = env.new_global_ref(&class).map_err(Failure::Jni)?;
    let built = Bridge {
        vm,
        class: global,
        deliver_data,
        deliver_error,
        deliver_close,
    };
    Ok(BRIDGE.get_or_init(|| built))
}

/// The worker loop's delivery step: hands one exported batch to Java and
/// receives the next container addresses in the return value.
fn deliver_batch(
    bridge: &Bridge,
    subscription_id: jlong,
    batch: &RecordBatch,
    array_addr: jlong,
    schema_addr: jlong,
) -> Result<(jlong, jlong), String> {
    export_batch(array_addr, schema_addr, batch).map_err(|e| format!("{e:?}"))?;
    bridge
        .vm
        .attach_current_thread(|env| -> Result<(jlong, jlong), jni::errors::Error> {
            let args = [jni::objects::JValue::Long(subscription_id).as_jni()];
            // SAFETY: cached static method with matching signature
            // `(J)[J`; the returned array is read immediately.
            let returned = unsafe {
                env.call_static_method_unchecked(
                    &bridge.class,
                    bridge.deliver_data,
                    jni::signature::ReturnType::Array,
                    &args,
                )
            }?
            .l()?;
            // SAFETY: the returned object is a long[] from the bridge.
            let array = unsafe {
                jni::objects::JPrimitiveArray::<jni::sys::jlong>::from_raw(env, returned.as_raw())
            };
            // SAFETY: the elements are read before any other JNI call.
            let elements = unsafe { array.get_elements(env, jni::objects::ReleaseMode::CopyBack)? };
            Ok((elements[0], elements[1]))
        })
        .map_err(|e: jni::errors::Error| e.to_string())
}

fn deliver_error(bridge: &Bridge, subscription_id: jlong, message: &str, code: i32) {
    let _ = bridge
        .vm
        .attach_current_thread(|env| -> Result<(), jni::errors::Error> {
            let message = env.new_string(message)?;
            let args = [
                jni::objects::JValue::Long(subscription_id).as_jni(),
                jni::objects::JValue::Object(&message).as_jni(),
                jni::objects::JValue::Int(code).as_jni(),
            ];
            // SAFETY: cached static method with matching signature.
            unsafe {
                env.call_static_method_unchecked(
                    &bridge.class,
                    bridge.deliver_error,
                    jni::signature::ReturnType::Primitive(jni::signature::Primitive::Void),
                    &args,
                )
            }?;
            Ok(())
        });
}

fn deliver_close(bridge: &Bridge, subscription_id: jlong) {
    let result = bridge
        .vm
        .attach_current_thread(|env| -> Result<(), jni::errors::Error> {
            let args = [jni::objects::JValue::Long(subscription_id).as_jni()];
            // SAFETY: cached static method with matching signature.
            unsafe {
                env.call_static_method_unchecked(
                    &bridge.class,
                    bridge.deliver_close,
                    jni::signature::ReturnType::Primitive(jni::signature::Primitive::Void),
                    &args,
                )
            }?;
            Ok(())
        });
    let _ = result;
}

/// Polls `source` until stopped, exhausted, or failed. Every batch crosses
/// the JNI boundary exactly once.
fn worker_loop(
    bridge: &'static Bridge,
    subscription_id: jlong,
    source: Source,
    stop: Arc<AtomicBool>,
    mut containers: (jlong, jlong),
) {
    let mut backoff = BACKOFF_FLOOR;

    loop {
        if stop.load(Ordering::Relaxed) {
            deliver_close(bridge, subscription_id);

            return;
        }
        let outcome = poll_once(&source);
        eprintln!(
            "laminardb-callback worker {subscription_id} polled data={}",
            matches!(outcome, Poll::Data(_))
        );
        match outcome {
            Poll::Data(batch) => {
                match deliver_batch(bridge, subscription_id, &batch, containers.0, containers.1) {
                    Ok(next) => {
                        containers = next;
                        backoff = BACKOFF_FLOOR;
                    }
                    Err(delivery) => {
                        // Delivery refusals mean the bridge already reported
                        // the failure to the listener (listener throw →
                        // onError delivered bridge-side; cancellation → stop
                        // set): never deliver a second error.
                        deliver_close(bridge, subscription_id);
                        let _ = delivery;
                        return;
                    }
                }
            }
            Poll::Idle => {
                if !active(&source) {
                    deliver_close(bridge, subscription_id);
                    return;
                }
                std::thread::sleep(backoff);
                backoff = std::cmp::min(backoff * 2, BACKOFF_CEILING);
            }
            Poll::Failed(err) => {
                deliver_error(bridge, subscription_id, err.message(), err.code());
                deliver_close(bridge, subscription_id);
                return;
            }
        }
    }
}

enum Poll {
    Data(RecordBatch),
    Idle,
    Failed(laminar_db::api::ApiError),
}

fn poll_once(source: &Source) -> Poll {
    match source {
        Source::QueryStream(handle) => handle.with_mut(|inner| match inner {
            Some(stream) => match stream.try_next() {
                Ok(Some(batch)) => Poll::Data(batch),
                Ok(None) => Poll::Idle,
                Err(err) => Poll::Failed(err),
            },
            None => Poll::Idle,
        }),
        Source::NamedStream(handle) => handle.with_mut(|inner| {
            let Some(state) = inner else {
                return Poll::Idle;
            };
            match state.subscription.try_next_frame() {
                Ok(Some(frame)) => match frame {
                    laminar_db::api::ArrowSubscriptionFrame::Batch { batch, .. } => {
                        Poll::Data(batch)
                    }
                    // Barriers are skipped in the callback contract (plan 03
                    // §4): the framed poll API surfaces them.
                    laminar_db::api::ArrowSubscriptionFrame::Barrier { .. } => Poll::Idle,
                },
                Ok(None) => Poll::Idle,
                Err(err) => Poll::Failed(err),
            }
        }),
    }
}

fn active(source: &Source) -> bool {
    match source {
        Source::QueryStream(handle) => handle.with(|inner| inner.is_some_and(|s| s.is_active())),
        Source::NamedStream(handle) => {
            handle.with(|inner| inner.is_some_and(|s| s.subscription.is_active()))
        }
    }
}

fn start_callback(
    env: &mut Env<'_>,
    subscription_id: jlong,
    source: Source,
) -> Result<jlong, Failure> {
    if ACTIVE.load(Ordering::Relaxed) >= MAX_CALLBACK_SUBSCRIPTIONS {
        return Err(Failure::Api(laminar_db::api::ApiError::subscription(
            "callback subscription limit reached (64)",
        )));
    }
    let bridge: &'static Bridge = bridge(env)?;
    // Seed the container addresses through the bridge's acquire upcall on
    // this already-attached thread (the Java side registered the listener
    // under this id before calling in).
    let containers = acquire_containers(env, bridge, subscription_id)?;
    let stop = Arc::new(AtomicBool::new(false));
    let worker_stop = Arc::clone(&stop);
    let exited = new_exit_signal();
    let worker_exit = Arc::clone(&exited);
    let worker = std::thread::Builder::new()
        .name(format!("laminardb-callback-{subscription_id}"))
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
                worker_loop(bridge, subscription_id, source, worker_stop, containers)
            }));
            if result.is_err() {
                // A panicked worker cannot deliver more batches; the exit
                // signal below still releases any joiner.
            }
            ACTIVE.fetch_sub(1, Ordering::Relaxed);
            let (lock, cvar) = &*worker_exit;
            let mut done = lock.lock().unwrap();
            *done = true;
            cvar.notify_all();
        })
        .map_err(|e| {
            Failure::Api(laminar_db::api::ApiError::internal(format!(
                "failed to spawn callback worker: {e}"
            )))
        })?;
    ACTIVE.fetch_add(1, Ordering::Relaxed);
    let state = CallbackState {
        stop,
        exited,
        _worker: Some(worker),
    };
    Ok(Box::into_raw(Box::new(NativeHandle::new(state))) as jlong)
}

fn acquire_containers(
    env: &mut Env<'_>,
    bridge: &Bridge,
    subscription_id: jlong,
) -> Result<(jlong, jlong), Failure> {
    let acquire = env
        .get_static_method_id(&bridge.class, jni_str!("acquire"), jni_sig!("(J)[J"))
        .map_err(Failure::Jni)?;
    let args = [jni::objects::JValue::Long(subscription_id).as_jni()];
    // SAFETY: static method just looked up with matching signature.
    let returned = unsafe {
        env.call_static_method_unchecked(
            &bridge.class,
            acquire,
            jni::signature::ReturnType::Array,
            &args,
        )
    }
    .map_err(Failure::Jni)?
    .l()
    .map_err(Failure::Jni)?;
    // SAFETY: the returned object is a long[] from the bridge.
    let array = unsafe {
        jni::objects::JPrimitiveArray::<jni::sys::jlong>::from_raw(env, returned.as_raw())
    };
    // SAFETY: the elements are read before any other JNI call.
    let elements = unsafe { array.get_elements(env, jni::objects::ReleaseMode::CopyBack) }
        .map_err(Failure::Jni)?;
    Ok((elements[0], elements[1]))
}

// ---- natives ----

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_subscribeCallbackQuery<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
    subscription_id: jlong,
    sql: JString<'caller>,
) -> jlong {
    let outcome = unowned_env.with_env(|env| {
        let sql = read_string(env, &sql)?;
        let runtime = crate::runtime::runtime()?;
        let _guard = runtime.enter();
        let stream = conn(conn_ptr as *mut c_void)?.with(|inner| {
            inner
                .ok_or_else(|| Failure::Api(connection_closed()))
                .and_then(|c| c.query_stream(&sql).map_err(Failure::Api))
        })?;
        drop(_guard);
        start_callback(
            env,
            subscription_id,
            Source::QueryStream(NativeHandle::new(stream)),
        )
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_subscribeCallbackStream<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
    subscription_id: jlong,
    stream_name: JString<'caller>,
) -> jlong {
    let outcome = unowned_env.with_env(|env| {
        let name = read_string(env, &stream_name)?;
        let subscription = conn(conn_ptr as *mut c_void)?.with(|inner| {
            inner
                .ok_or_else(|| Failure::Api(connection_closed()))
                .and_then(|c| c.subscribe(&name).map_err(Failure::Api))
        })?;
        let state = crate::subscription::SubscriptionState {
            subscription,
            frame: None,
            last_barrier: (0, 0, 0, 0),
        };
        start_callback(
            env,
            subscription_id,
            Source::NamedStream(crate::subscription::SubHandle::new(state)),
        )
    });
    outcome.resolve::<ThrowLaminar>()
}

/// Stop request from the Java side (listener threw): sets the stop flag so
/// the worker exits at its next loop check without joining.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_callbackRequestStop<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    callback_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        typed::<CallbackState>(callback_ptr as *mut c_void, "callback subscription")?.with_mut(
            |inner| {
                if let Some(state) = inner {
                    state.stop.store(true, Ordering::Relaxed);
                }
            },
        );
        Ok::<_, Failure>(())
    });
    outcome.resolve::<ThrowLaminar>();
}

/// Returns whether the worker is still running (no side effects).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_callbackIsActive<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    callback_ptr: jlong,
) -> jboolean {
    let outcome = unowned_env.with_env(|_| {
        let active = typed::<CallbackState>(callback_ptr as *mut c_void, "callback subscription")?
            .with(|inner| inner.is_some_and(|s| !s.stop.load(Ordering::Relaxed)))
            as jboolean;
        Ok::<_, Failure>(active)
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_callbackJoin<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    callback_ptr: jlong,
) -> jboolean {
    let outcome = unowned_env.with_env(|_| {
        // Stop under the lock, then wait (bounded 5 s) for the worker's exit
        // signal. Never join the raw handle: the worker may legitimately be
        // parked in a listener upcall contending a Java monitor this thread
        // holds — a bounded wait reports the miss instead of deadlocking.
        let exited_signal =
            typed::<CallbackState>(callback_ptr as *mut c_void, "callback subscription")?.with_mut(
                |inner| {
                    let state = inner?;
                    state.stop.store(true, Ordering::Relaxed);
                    Some(Arc::clone(&state.exited))
                },
            );
        let Some(exited_signal) = exited_signal else {
            return Ok(true as jboolean);
        };
        // Wait outside the handle lock: the worker needs it to poll.
        let (lock, cvar) = &*exited_signal;
        let done = lock.lock().unwrap();
        let exited = if *done {
            true
        } else {
            let (guard, timeout) = cvar.wait_timeout(done, Duration::from_secs(5)).unwrap();
            *guard || !timeout.timed_out()
        };
        Ok(exited as jboolean)
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_callbackFree<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    callback_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        if callback_ptr != 0 {
            // SAFETY: exactly-once free per live handle (Java nulls first).
            let handle = unsafe { Box::from_raw(callback_ptr as *mut NativeHandle<CallbackState>) };
            if let Some(state) = handle.take() {
                state.stop.store(true, Ordering::Relaxed);
                // Bounded: a stuck worker is reported by callbackJoin; here
                // we only avoid blocking shutdown indefinitely.
                let (lock, cvar) = &*state.exited;
                let done = lock.lock().unwrap();
                if !*done {
                    let _ = cvar.wait_timeout(done, Duration::from_secs(1));
                }
            }
        }
        Ok::<_, Failure>(())
    });
    outcome.resolve::<ThrowLaminar>();
}
