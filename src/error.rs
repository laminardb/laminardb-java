//! The single `ApiError` → Java exception mapping point (plan 01 Task 0.2).

use std::sync::OnceLock;

use jni::errors::{Error as JniError, ErrorPolicy};
use jni::objects::{Global, JClass, JMethodID, JThrowable};
use jni::strings::JNIStr;
use jni::sys::jvalue;
use jni::{jni_sig, jni_str, Env};

use laminar_db::api::{codes, ApiError};

/// Every fallible path inside a native method funnels into `Failure`; the
/// [`ThrowLaminar`] policy converts it into a mapped Java exception.
pub(crate) enum Failure {
    Api(ApiError),
    Jni(JniError),
}

impl From<JniError> for Failure {
    fn from(err: JniError) -> Self {
        Self::Jni(err)
    }
}

impl From<ApiError> for Failure {
    fn from(err: ApiError) -> Self {
        Self::Api(err)
    }
}

pub(crate) fn connection_closed() -> ApiError {
    ApiError::Connection {
        code: codes::CONNECTION_CLOSED,
        message: "Connection is closed".to_string(),
    }
}

struct MappedException {
    class: Global<JClass<'static>>,
    ctor: JMethodID,
}

struct MappedExceptions {
    connection: MappedException,
    base: MappedException,
}

/// Cached lazily as global refs; a failed init is retried on the next throw.
static MAPPED: OnceLock<Option<MappedExceptions>> = OnceLock::new();

fn mapped(env: &mut Env<'_>) -> Option<&'static MappedExceptions> {
    MAPPED
        .get_or_init(|| {
            let connection = map_class(env, jni_str!("io/laminardb/LaminarConnectionException"))?;
            let base = map_class(env, jni_str!("io/laminardb/LaminarException"))?;
            Some(MappedExceptions { connection, base })
        })
        .as_ref()
}

fn map_class(env: &mut Env<'_>, name: &JNIStr) -> Option<MappedException> {
    let class = env.find_class(name).ok()?;
    let ctor = env
        .get_method_id(
            &class,
            jni_str!("<init>"),
            jni_sig!("(Ljava/lang/String;I)V"),
        )
        .ok()?;
    let global = env.new_global_ref(&class).ok()?;
    Some(MappedException {
        class: global,
        ctor,
    })
}

/// Throws the mapped exception for `err`: class by code range, message
/// verbatim, code via the `(String, int)` constructor. Secondary JNI errors
/// propagate to the caller — never a panic.
pub(crate) fn throw_api_error(env: &mut Env<'_>, err: &ApiError) -> Result<(), JniError> {
    let Some(mapped) = mapped(env) else {
        return Err(JniError::JniCall(jni::errors::JniError::Unknown));
    };
    if env.exception_check() {
        return Ok(());
    }
    let target = match err.code() {
        100..=199 => &mapped.connection,
        _ => &mapped.base,
    };
    let message = env.new_string(err.message())?;
    let args: [jvalue; 2] = [
        jni::objects::JValue::Object(&message).as_jni(),
        jni::objects::JValue::Int(err.code()).as_jni(),
    ];
    // SAFETY: `ctor` is the cached `(String,int)` constructor of the cached
    // global class reference; the argument kinds match its signature.
    let thrown = unsafe { env.new_object_unchecked(&target.class, target.ctor, &args)? };
    let throwable = env.new_cast_local_ref::<JThrowable>(thrown)?;
    env.throw(throwable)
}

/// Error policy for every native method: maps [`Failure`] onto the exception
/// hierarchy and converts Rust panics into `LaminarException` (code 900)
/// instead of unwinding across the FFI boundary.
pub(crate) struct ThrowLaminar;

impl<T: Default> ErrorPolicy<T, Failure> for ThrowLaminar {
    type Captures<'unowned_env_local: 'native_method, 'native_method> = ();

    fn on_error<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut Self::Captures<'unowned_env_local, 'native_method>,
        err: Failure,
    ) -> Result<T, JniError> {
        let err = match err {
            Failure::Api(err) => err,
            Failure::Jni(err) => ApiError::internal(format!("JNI error: {err}")),
        };
        throw_api_error(env, &err).map(|()| T::default())
    }

    fn on_panic<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut Self::Captures<'unowned_env_local, 'native_method>,
        payload: Box<dyn std::any::Any + Send>,
    ) -> Result<T, JniError> {
        let detail = payload
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| payload.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "unknown panic payload".to_string());
        throw_api_error(env, &ApiError::internal(format!("native panic: {detail}")))
            .map(|()| T::default())
    }
}
