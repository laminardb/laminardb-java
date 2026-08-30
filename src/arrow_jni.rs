//! Arrow C Data Interface crossings shared by every module (mechanics proven
//! by Spike A, plan 01 Task 0.5).

use std::ptr;

use arrow::array::{make_array, Array, AsArray, RecordBatch, StructArray};
use arrow::datatypes::SchemaRef;
use arrow::error::ArrowError;
use arrow::ffi::{from_ffi, to_ffi, FFI_ArrowArray, FFI_ArrowSchema};

use crate::error::Failure;
use laminar_db::api::ApiError;

fn arrow_failure(err: ArrowError) -> Failure {
    Failure::Api(ApiError::internal(format!("arrow ffi: {err}")))
}

/// Writes `batch` into Java-allocated FFI structs at the given addresses.
/// The structs' release callbacks hold Arc clones of the batch's buffers, so
/// the exported data outlives this call and the native-side owner.
pub(crate) fn export_batch(
    array_addr: i64,
    schema_addr: i64,
    batch: &RecordBatch,
) -> Result<(), Failure> {
    let struct_array = StructArray::from(batch.clone());
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

/// Writes `schema` into a Java-allocated FFI struct, encoded as the C Data
/// Interface struct type (`+s` format) — the schema carrier arrow-java's
/// `Data.importSchema` expects.
pub(crate) fn export_schema(schema_addr: i64, schema: &SchemaRef) -> Result<(), Failure> {
    let exported = FFI_ArrowSchema::try_from(schema.as_ref()).map_err(arrow_failure)?;
    // SAFETY: as in `export_batch` — the destination memory is Java-owned and
    // handed over for this call.
    unsafe { ptr::write(schema_addr as *mut FFI_ArrowSchema, exported) };
    Ok(())
}

/// Consumes Java-exported FFI structs into an owned `RecordBatch`, deep-
/// copying the buffers. The copy (not the import) is what the engine holds:
/// arrow-java releases exported memory through a JNI upcall, so a Rust-side
/// Arc that outlives this call would release Java buffers from an arbitrary
/// engine thread. Copying here releases the imported buffers synchronously
/// on the calling JNI thread and gives the engine wholly native memory.
/// (Rust→Java stays zero-copy: that direction's release callbacks are pure
/// native refcount decrements.)
pub(crate) fn import_batch(array_addr: i64, schema_addr: i64) -> Result<RecordBatch, Failure> {
    // SAFETY: Java exported its vectors into these structs and handed over
    // ownership; reading moves them out.
    let (array, schema) = unsafe {
        (
            ptr::read(array_addr as *const FFI_ArrowArray),
            ptr::read(schema_addr as *const FFI_ArrowSchema),
        )
    };
    // SAFETY: single consumption of the just-moved structs.
    let imported = unsafe { from_ffi(array, &schema) }.map_err(arrow_failure)?;
    let batch = RecordBatch::from(make_array(imported).as_struct());
    let copied = arrow::compute::concat_batches(&batch.schema(), std::iter::once(&batch))
        .map_err(arrow_failure)?;
    Ok(copied)
}

#[cfg(test)]
mod tests {
    use super::*;
    use arrow::array::{ArrayRef, Int64Array, StringArray, TimestampMillisecondArray};
    use arrow::datatypes::{DataType, Field, Int64Type, Schema, TimeUnit};
    use std::sync::Arc;

    fn sample_schema() -> SchemaRef {
        Arc::new(Schema::new(vec![
            Field::new("id", DataType::Int64, false),
            Field::new("name", DataType::Utf8, false),
            Field::new("ts", DataType::Timestamp(TimeUnit::Millisecond, None), true),
        ]))
    }

    fn sample_batch(schema: &SchemaRef) -> RecordBatch {
        RecordBatch::try_new(
            schema.clone(),
            vec![
                Arc::new(Int64Array::from(vec![1i64, 2, 3])) as ArrayRef,
                Arc::new(StringArray::from(vec!["alpha", "beta", "gamma"])),
                Arc::new(TimestampMillisecondArray::from(vec![
                    Some(1_000i64),
                    None,
                    Some(3_000),
                ])),
            ],
        )
        .unwrap()
    }

    #[test]
    fn ffi_roundtrip_preserves_values() {
        let schema = sample_schema();
        let batch = sample_batch(&schema);
        let struct_array = StructArray::from(batch.clone());
        let (array, exported) = to_ffi(&struct_array.to_data()).unwrap();
        // SAFETY: fresh structs produced above; this is their single consume.
        let imported = unsafe { from_ffi(array, &exported) }.unwrap();
        let roundtripped = RecordBatch::from(make_array(imported).as_struct());
        assert_eq!(roundtripped.schema(), batch.schema());
        assert_eq!(roundtripped.num_rows(), 3);
        assert!(roundtripped
            .column(0)
            .as_primitive::<Int64Type>()
            .values()
            .iter()
            .copied()
            .eq([1, 2, 3]));
    }

    #[test]
    fn ffi_roundtrip_repeats_without_double_free() {
        // Bounded: 1_000 iterations prove release-callback stability.
        let schema = sample_schema();
        for _ in 0..1_000 {
            let batch = sample_batch(&schema);
            let struct_array = StructArray::from(batch);
            let (array, exported) = to_ffi(&struct_array.to_data()).unwrap();
            // SAFETY: single consume of the freshly exported structs.
            let imported = unsafe { from_ffi(array, &exported) }.unwrap();
            assert_eq!(
                RecordBatch::from(make_array(imported).as_struct()).num_rows(),
                3
            );
        }
    }
}
