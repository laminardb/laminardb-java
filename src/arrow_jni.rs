//! Arrow C Data Interface crossings — Spike A (plan 01 Task 0.5). The entry
//! points back `io.laminardb.internal.Spike`; Phase 1 evolves them into the
//! export/import natives of plan 02 §2.

use std::ptr;
use std::sync::Arc;

use arrow::array::{
    make_array, Array, ArrayRef, AsArray, Int64Array, RecordBatch, StringArray, StructArray,
};
use arrow::datatypes::{DataType, Field, Int64Type, Schema};
use arrow::error::ArrowError;
use arrow::ffi::{from_ffi, to_ffi, FFI_ArrowArray, FFI_ArrowSchema};
use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::EnvUnowned;
use laminar_db::api::ApiError;

use crate::error::{Failure, ThrowLaminar};

fn arrow_failure(err: ArrowError) -> Failure {
    Failure::Api(ApiError::internal(format!("arrow ffi: {err}")))
}

fn sample_batch() -> Result<RecordBatch, ArrowError> {
    let schema = Arc::new(Schema::new(vec![
        Field::new("id", DataType::Int64, false),
        Field::new("name", DataType::Utf8, false),
    ]));
    let ids = Int64Array::from(vec![1i64, 2, 3]);
    let names = StringArray::from(vec!["alpha", "beta", "gamma"]);
    RecordBatch::try_new(schema, vec![Arc::new(ids) as ArrayRef, Arc::new(names)])
}

fn verify_batch(batch: &RecordBatch) -> bool {
    batch.num_rows() == 3
        && batch
            .column(0)
            .as_primitive::<Int64Type>()
            .values()
            .iter()
            .copied()
            .eq([1, 2, 3])
        && batch.column(1).as_string::<i32>().value(1) == "beta"
}

/// Writes the sample batch into Java-allocated FFI structs (export direction:
/// Rust → Java).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Spike_exportSampleBatch<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    array_addr: jlong,
    schema_addr: jlong,
) {
    let outcome = unowned_env.with_env(|_| export(array_addr, schema_addr));
    outcome.resolve::<ThrowLaminar>();
}

fn export(array_addr: jlong, schema_addr: jlong) -> Result<(), Failure> {
    let batch = sample_batch().map_err(arrow_failure)?;
    let struct_array = StructArray::from(batch);
    let (array, schema) = to_ffi(&struct_array.to_data()).map_err(arrow_failure)?;
    // SAFETY: Java allocated both FFI structs for this call and handed their
    // memory over; writing moves Rust's structs in, transferring ownership of
    // the release callbacks to the Java side.
    unsafe {
        ptr::write(schema_addr as *mut FFI_ArrowSchema, schema);
        ptr::write(array_addr as *mut FFI_ArrowArray, array);
    }
    Ok(())
}

/// Consumes Java-exported FFI structs and verifies their contents in Rust
/// (import direction: Java → Rust). Returns the row count.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_laminardb_internal_Spike_importBatch<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    array_addr: jlong,
    schema_addr: jlong,
) -> jint {
    let outcome = unowned_env.with_env(|_| import(array_addr, schema_addr));
    outcome.resolve::<ThrowLaminar>()
}

fn import(array_addr: jlong, schema_addr: jlong) -> Result<jint, Failure> {
    // SAFETY: Java exported its vectors into these structs and handed over
    // ownership; reading moves them out, so the Java-side wrappers are dead
    // afterwards per the release-callback contract.
    let (array, schema) = unsafe {
        (
            ptr::read(array_addr as *const FFI_ArrowArray),
            ptr::read(schema_addr as *const FFI_ArrowSchema),
        )
    };
    // SAFETY: the structs were just moved out of Java-owned memory; this is
    // their single consumption.
    let imported = unsafe { from_ffi(array, &schema) }.map_err(arrow_failure)?;
    let batch = RecordBatch::from(make_array(imported).as_struct());
    if !verify_batch(&batch) {
        return Err(Failure::Api(ApiError::internal(
            "arrow spike: roundtrip contents mismatch".to_string(),
        )));
    }
    Ok(batch.num_rows() as jint)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ffi_roundtrip_preserves_values() {
        let batch = sample_batch().unwrap();
        let struct_array = StructArray::from(batch.clone());
        let (array, schema) = to_ffi(&struct_array.to_data()).unwrap();
        // SAFETY: fresh structs produced above; this is their single consume.
        let imported = unsafe { from_ffi(array, &schema) }.unwrap();
        let roundtripped = RecordBatch::from(make_array(imported).as_struct());
        assert!(verify_batch(&roundtripped));
    }

    #[test]
    fn ffi_roundtrip_repeats_without_double_free() {
        // Bounded: 1_000 iterations prove release-callback stability.
        for _ in 0..1_000 {
            let batch = sample_batch().unwrap();
            let struct_array = StructArray::from(batch);
            let (array, schema) = to_ffi(&struct_array.to_data()).unwrap();
            // SAFETY: single consume of the freshly exported structs.
            let imported = unsafe { from_ffi(array, &schema) }.unwrap();
            assert!(verify_batch(&RecordBatch::from(
                make_array(imported).as_struct()
            )));
        }
    }
}
