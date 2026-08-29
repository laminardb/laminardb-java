//! Catalog natives over `Java_io_laminardb_internal_Native` (plan 02 §2).

use std::os::raw::c_void;

use jni::objects::{JClass, JString};
use jni::{Env, EnvUnowned};

use crate::arrow_jni::export_schema;
use crate::config::read_string;
use crate::error::{Failure, ThrowLaminar};
use crate::handle::conn;

macro_rules! list_native {
    ($(#[$meta:meta])* $name:ident, $list:expr) => {
        $(#[$meta])*
        #[unsafe(no_mangle)]
        pub extern "system" fn $name<'caller>(
            mut unowned_env: EnvUnowned<'caller>,
            _class: JClass<'caller>,
            conn_ptr: jni::sys::jlong,
        ) -> jni::sys::jobjectArray {
            let outcome = unowned_env.with_env(|env| {
                let names = conn(conn_ptr as *mut c_void)?.with(|inner| {
                    inner.map(|c| $list(c)).ok_or_else(|| {
                        Failure::Api(crate::error::connection_closed())
                    })
                })?;
                string_array(env, &names)
            });
            outcome.resolve::<ThrowLaminar>()
        }
    };
}

list_native!(
    Java_io_laminardb_internal_Native_listSources,
    |c: &laminar_db::api::Connection| c.list_sources()
);
list_native!(
    Java_io_laminardb_internal_Native_listStreams,
    |c: &laminar_db::api::Connection| c.list_streams()
);
list_native!(
    Java_io_laminardb_internal_Native_listSinks,
    |c: &laminar_db::api::Connection| c.list_sinks()
);

/// Exports a source's schema into the Java-allocated FFI struct at
/// `schema_addr`; errors surface as e.g. 200 `TABLE_NOT_FOUND`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Native_connSchemaExport<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    conn_ptr: jni::sys::jlong,
    name: JString<'caller>,
    schema_addr: jni::sys::jlong,
) {
    let outcome = unowned_env.with_env(|env| {
        let name = read_string(env, &name)?;
        let schema = conn(conn_ptr as *mut c_void)?.with(|inner| {
            inner
                .ok_or_else(|| Failure::Api(crate::error::connection_closed()))
                .and_then(|c| c.get_schema(&name).map_err(Failure::Api))
        })?;
        export_schema(schema_addr, &schema)
    });
    outcome.resolve::<ThrowLaminar>();
}

fn string_array(env: &mut Env<'_>, names: &[String]) -> Result<jni::sys::jobjectArray, Failure> {
    let class = env.find_class(jni::jni_str!("java/lang/String"))?;
    let array = env.new_object_array(names.len() as i32, &class, jni::objects::JObject::null())?;
    for (index, name) in names.iter().enumerate() {
        let value = jni::objects::JString::from_str(env, name)?;
        array.set_element(env, index, value)?;
    }
    Ok(array.as_raw())
}
