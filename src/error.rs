//! The single `ApiError` → Java exception mapping point (plan 02 §5).

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

pub(crate) fn writer_closed() -> ApiError {
    ApiError::Ingestion {
        code: codes::WRITER_CLOSED,
        message: "Writer is closed".to_string(),
    }
}

pub(crate) fn wrong_kind(expected: &str) -> ApiError {
    ApiError::internal(format!("result handle does not hold {expected}"))
}

struct MappedException {
    class: Global<JClass<'static>>,
    ctor: JMethodID,
}

struct MappedExceptions {
    connection: MappedException,
    schema: MappedException,
    ingestion: MappedException,
    query: MappedException,
    subscription: MappedException,
    internal: MappedException,
    shutdown: MappedException,
    base: MappedException,
}

/// Cached lazily as global refs. A failed init leaves the `OnceLock` empty, so
/// the next throw retries — a process-lifetime failure would silently swallow
/// every future error. Racing initializers are harmless: the loser's global
/// refs are deleted on this already-attached JNI thread.
static MAPPED: OnceLock<MappedExceptions> = OnceLock::new();

fn mapped(env: &mut Env<'_>) -> Option<&'static MappedExceptions> {
    if let Some(cached) = MAPPED.get() {
        return Some(cached);
    }
    let all = MappedExceptions {
        connection: map_class(env, jni_str!("io/laminardb/LaminarConnectionException"))?,
        schema: map_class(env, jni_str!("io/laminardb/LaminarSchemaException"))?,
        ingestion: map_class(env, jni_str!("io/laminardb/LaminarIngestionException"))?,
        query: map_class(env, jni_str!("io/laminardb/LaminarQueryException"))?,
        subscription: map_class(env, jni_str!("io/laminardb/LaminarSubscriptionException"))?,
        internal: map_class(env, jni_str!("io/laminardb/LaminarInternalException"))?,
        shutdown: map_class(env, jni_str!("io/laminardb/LaminarShutdownException"))?,
        base: map_class(env, jni_str!("io/laminardb/LaminarException"))?,
    };
    Some(MAPPED.get_or_init(|| all))
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

/// The exception category a numeric code maps to — the single source of the
/// plan 02 §5 table on the Rust side. Unknown codes inside a known range map
/// to that range's class; codes outside every range map to the base class.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub(crate) enum Category {
    Connection,
    Schema,
    Ingestion,
    Query,
    Subscription,
    Internal,
    Shutdown,
    Base,
}

fn category_for_code(code: i32) -> Category {
    match code {
        100..=199 => Category::Connection,
        200..=299 => Category::Schema,
        300..=399 => Category::Ingestion,
        400..=499 => Category::Query,
        500..=599 => Category::Subscription,
        900 => Category::Internal,
        901 => Category::Shutdown,
        _ => Category::Base,
    }
}

fn class_for(category: Category, mapped: &MappedExceptions) -> &MappedException {
    match category {
        Category::Connection => &mapped.connection,
        Category::Schema => &mapped.schema,
        Category::Ingestion => &mapped.ingestion,
        Category::Query => &mapped.query,
        Category::Subscription => &mapped.subscription,
        Category::Internal => &mapped.internal,
        Category::Shutdown => &mapped.shutdown,
        Category::Base => &mapped.base,
    }
}

/// Throws the mapped exception for `err`: class per `category_for_code`,
/// message verbatim, code via the `(String, int)` constructor. Secondary JNI
/// errors propagate to the caller — never a panic.
pub(crate) fn throw_api_error(env: &mut Env<'_>, err: &ApiError) -> Result<(), JniError> {
    let Some(mapped) = mapped(env) else {
        return Err(JniError::JniCall(jni::errors::JniError::Unknown));
    };
    if env.exception_check() {
        return Ok(());
    }
    let target = class_for(category_for_code(err.code()), mapped);
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
/// hierarchy and converts Rust panics into `LaminarInternalException` (900)
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

#[cfg(test)]
mod tests {
    use super::*;

    /// The plan 02 §5 coverage gate: every constant in the pinned core's
    /// `codes` module must map to its documented category. When the core adds
    /// a code, this table needs a conscious update (the count assertion below
    /// fails first).
    #[test]
    fn every_pinned_code_maps_to_its_documented_category() {
        let expectations: &[(i32, Category)] = &[
            (codes::CONNECTION_FAILED, Category::Connection),
            (codes::CONNECTION_CLOSED, Category::Connection),
            (codes::CONNECTION_IN_USE, Category::Connection),
            (codes::TABLE_NOT_FOUND, Category::Schema),
            (codes::TABLE_EXISTS, Category::Schema),
            (codes::SCHEMA_MISMATCH, Category::Schema),
            (codes::INVALID_SCHEMA, Category::Schema),
            (codes::INGESTION_FAILED, Category::Ingestion),
            (codes::WRITER_CLOSED, Category::Ingestion),
            (codes::BATCH_SCHEMA_MISMATCH, Category::Ingestion),
            (codes::QUERY_FAILED, Category::Query),
            (codes::SQL_PARSE_ERROR, Category::Query),
            (codes::QUERY_CANCELLED, Category::Query),
            (codes::SUBSCRIPTION_FAILED, Category::Subscription),
            (codes::SUBSCRIPTION_CLOSED, Category::Subscription),
            (codes::SUBSCRIPTION_TIMEOUT, Category::Subscription),
            (codes::INTERNAL_ERROR, Category::Internal),
            (codes::SHUTDOWN, Category::Shutdown),
        ];
        assert_eq!(expectations.len(), 18, "pin v0.30.0 codes count");
        for &(code, expected) in expectations {
            assert_eq!(category_for_code(code), expected, "code {code}");
        }
    }

    #[test]
    fn unknown_codes_map_to_the_base_class_never_panic() {
        assert_eq!(category_for_code(999), Category::Base);
        assert_eq!(category_for_code(0), Category::Base);
        // A future code inside a known range maps to that range's class.
        assert_eq!(category_for_code(103), Category::Connection);
        assert_eq!(category_for_code(403), Category::Query);
    }
}
