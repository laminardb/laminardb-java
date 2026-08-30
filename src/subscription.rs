//! Named-stream subscription natives over `Java_io_laminardb_internal_Native`
//! (plan 03 §1–§2). INVARIANT (core v0.30.0): the blocking `subscribe` and
//! `next_frame` reject being called inside a tokio runtime context — these
//! natives therefore take **no** runtime enter guard (verified in
//! `api/connection/mod.rs` and `api/subscription.rs` at the pin).

use std::os::raw::c_void;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong};
use jni::EnvUnowned;

use laminar_db::api::{ArrowSubscription, ArrowSubscriptionFrame};

use crate::arrow_jni::{export_batch, export_schema};
use crate::config::read_string;
use crate::error::{connection_closed, Failure, ThrowLaminar};
use crate::handle::{conn, typed, NativeHandle};

pub(crate) type SubHandle = NativeHandle<SubscriptionState>;

pub(crate) struct SubscriptionState {
    pub(crate) subscription: ArrowSubscription,
    /// The current frame; each `next_frame` replaces it (the previous frame's
    /// lease releases then). Exported batches are refcount-decoupled from the
    /// frame, so Java's ArrowBatches stay valid across replacement.
    pub(crate) frame: Option<ArrowSubscriptionFrame>,
    /// (sequence, epoch, checkpoint_id, through_sequence) of the last
    /// barrier frame — zeroed by data frames per the poll contract.
    pub(crate) last_barrier: (u64, u64, u64, u64),
}

/// Frame discriminators returned by `subNextFrame`/`subTryNextFrame`.
const FRAME_CLOSED: jint = 0;
const FRAME_DATA: jint = 1;
const FRAME_BARRIER: jint = 2;

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_subscribe<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jlong,
    stream_name: JString<'caller>,
) -> jlong {
    let outcome = unowned_env.with_env(|env| {
        let name = read_string(env, &stream_name)?;
        let subscription = conn(conn_ptr as *mut c_void)?.with(|inner| {
            inner
                .ok_or_else(|| Failure::Api(connection_closed()))
                .and_then(|c| c.subscribe(&name).map_err(Failure::Api))
        })?;
        let state = SubscriptionState {
            subscription,
            frame: None,
            last_barrier: (0, 0, 0, 0),
        };
        Ok::<_, Failure>(Box::into_raw(Box::new(SubHandle::new(state))) as jlong)
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_subSchemaExport<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    sub_ptr: jlong,
    schema_addr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        typed::<SubscriptionState>(sub_ptr as *mut c_void, "subscription")?.with(
            |inner| match inner {
                Some(state) => export_schema(schema_addr, &state.subscription.schema()),
                None => Err(Failure::Api(crate::error::wrong_kind("a subscription"))),
            },
        )
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_subNextFrame<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    sub_ptr: jlong,
    array_addr: jlong,
    schema_addr: jlong,
) -> jint {
    let outcome = unowned_env.with_env(|_| next(sub_ptr, array_addr, schema_addr, false));
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_subTryNextFrame<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    sub_ptr: jlong,
    array_addr: jlong,
    schema_addr: jlong,
) -> jint {
    let outcome = unowned_env.with_env(|_| next(sub_ptr, array_addr, schema_addr, true));
    outcome.resolve::<ThrowLaminar>()
}

/// Returns 1 (data exported at the addresses), 2 (barrier; metadata via the
/// accessors), or 0 (closed / nothing ready — the blocking variant reports 0
/// only at end; the poll variant also when idle).
fn next(
    sub_ptr: jlong,
    array_addr: jlong,
    schema_addr: jlong,
    non_blocking: bool,
) -> Result<jint, Failure> {
    typed::<SubscriptionState>(sub_ptr as *mut c_void, "subscription")?.with_mut(|inner| {
        let Some(state) = inner else {
            return Ok(FRAME_CLOSED);
        };
        let frame = if non_blocking {
            state.subscription.try_next_frame()?
        } else {
            state.subscription.next_frame()?
        };
        let Some(frame) = frame else {
            state.frame = None;
            return Ok(FRAME_CLOSED);
        };
        // Replacing `frame` drops the previous frame and releases its lease.
        state.frame = Some(frame);
        let tag = match state.frame.as_ref().expect("frame just stored") {
            ArrowSubscriptionFrame::Batch { batch, .. } => {
                export_batch(array_addr, schema_addr, batch)?;
                FRAME_DATA
            }
            ArrowSubscriptionFrame::Barrier { .. } => FRAME_BARRIER,
        };
        if tag == FRAME_BARRIER {
            if let ArrowSubscriptionFrame::Barrier {
                sequence,
                epoch,
                checkpoint_id,
                through_sequence,
            } = state.frame.as_ref().expect("frame just stored")
            {
                state.last_barrier = (*sequence, *epoch, *checkpoint_id, *through_sequence);
            }
        }
        Ok(tag)
    })
}

/// Barrier metadata accessors read the values recorded by the last
/// `subNextFrame`/`subTryNextFrame` barrier frame.
macro_rules! barrier_accessor {
    ($(#[$meta:meta])* $name:ident, $field:tt) => {
        $(#[$meta])*
        #[unsafe(no_mangle)]
        pub extern "system" fn $name<'caller>(
            mut unowned_env: EnvUnowned<'caller>,
            _class: JClass<'caller>,
            sub_ptr: jlong,
        ) -> jlong {
            let outcome = unowned_env.with_env(|_| {
                let value = typed::<SubscriptionState>(
                    sub_ptr as *mut c_void,
                    "subscription",
                )?
                .with(|inner| inner.map(|s| s.last_barrier.$field as jlong).unwrap_or(0));
                Ok::<_, Failure>(value)
            });
            outcome.resolve::<ThrowLaminar>()
        }
    };
}

barrier_accessor!(Java_io_laminardb_internal_Native_subFrameEpoch, 1);
barrier_accessor!(Java_io_laminardb_internal_Native_subFrameCheckpointId, 2);
barrier_accessor!(Java_io_laminardb_internal_Native_subFrameThroughSequence, 3);
barrier_accessor!(Java_io_laminardb_internal_Native_subFrameSequence, 0);

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_subIsActive<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    sub_ptr: jlong,
) -> jboolean {
    let outcome = unowned_env.with_env(|_| {
        let active = typed::<SubscriptionState>(sub_ptr as *mut c_void, "subscription")?
            .with(|inner| inner.is_some_and(|s| s.subscription.is_active()))
            as jboolean;
        Ok::<_, Failure>(active)
    });
    outcome.resolve::<ThrowLaminar>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_subCancel<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    sub_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        typed::<SubscriptionState>(sub_ptr as *mut c_void, "subscription")?.with_mut(|inner| {
            if let Some(state) = inner {
                state.subscription.cancel();
                state.frame = None;
            }
        });
        Ok::<_, Failure>(())
    });
    outcome.resolve::<ThrowLaminar>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_subFree<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    sub_ptr: jlong,
) {
    let outcome = unowned_env.with_env(|_| {
        if sub_ptr != 0 {
            // SAFETY: exactly-once free per live handle (Java nulls first).
            let handle = unsafe { Box::from_raw(sub_ptr as *mut SubHandle) };
            let _ = handle.take();
        }
        Ok::<_, Failure>(())
    });
    outcome.resolve::<ThrowLaminar>();
}
