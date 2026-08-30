package io.laminardb.internal;

/**
 * The native surface (plan 02 §2). Referenced only by {@code io.laminardb},
 * never by user code. Handles are peer pointers; every free is idempotent.
 * Arrow crossings take the {@code memoryAddress()} of Java-allocated
 * ArrowArray/ArrowSchema structs per the plan 01 Spike A mechanics.
 */
public final class Native {

    static {
        NativeLoader.load();
    }

    private Native() {}

    // ---- lifecycle ----

    /** Opens a default in-memory connection; returns the native handle. */
    public static native long openDefault();

    /** Opens a connection from a native config handle (value is cloned, not consumed). */
    public static native long openWithConfig(long config);

    /** Closes the connection, consuming the handle; idempotent and null-tolerant. */
    public static native void close(long conn);

    /** Starts all pipelines on the connection. */
    public static native void start(long conn);

    /** Runs a checkpoint; returns the checkpoint id. */
    public static native long checkpoint(long conn);

    /** Returns whether checkpointing is configured on this connection. */
    public static native boolean isCheckpointEnabled(long conn);

    /** Shuts the engine down without consuming the connection handle. */
    public static native void shutdown(long conn);

    /** Returns the native binding version. */
    public static native String version();

    // ---- config builder ----

    /** Creates a native config with core defaults; returns the config handle. */
    public static native long configNew();

    /** Sets {@code default_buffer_size}. */
    public static native void configSetBufferSize(long config, long value);

    /** Sets {@code storage_dir}; a null path clears it (in-memory only). */
    public static native void configSetStorageDir(long config, String path);

    /** Sets the checkpoint interval; 0 disables checkpointing. */
    public static native void configSetCheckpointIntervalMs(long config, long intervalMs);

    /** Sets {@code incremental_emit}. */
    public static native void configSetIncrementalEmit(long config, boolean value);

    /** Sets {@code object_store_url}; null clears it. */
    public static native void configSetObjectStoreUrl(long config, String url);

    /** Sets one object-store option; a null value removes the key. */
    public static native void configSetObjectStoreOption(long config, String key, String value);

    /** Frees the native config; idempotent and null-tolerant. */
    public static native void configDrop(long config);

    // ---- sql ----

    /** Executes a statement; returns an ExecuteResult handle. */
    public static native long execute(long conn, String sql);

    /** Returns the kind ordinal: 0 DDL, 1 QUERY, 2 ROWS_AFFECTED, 3 METADATA. */
    public static native int executeKind(long exec);

    /** Returns the DDL object name; valid only for kind DDL. */
    public static native String executeDdlObject(long exec);

    /** Returns the affected row count; valid only for kind ROWS_AFFECTED. */
    public static native long executeRowsAffected(long exec);

    /** Frees the ExecuteResult handle; idempotent. */
    public static native void executeFree(long exec);

    /** Runs a materialized query; returns a QueryResult handle. */
    public static native long query(long conn, String sql);

    /** Runs a streaming query; returns a QueryStream handle. */
    public static native long queryStream(long conn, String sql);

    // ---- QueryResult / QueryStream to Arrow ----

    /** Writes the result schema's FFI struct at {@code schemaAddr}. */
    public static native void resultSchemaExport(long result, long schemaAddr);

    /** Returns the total row count across all batches. */
    public static native long resultNumRows(long result);

    /** Returns the number of batches held by the result. */
    public static native int resultNumBatches(long result);

    /** Writes batch {@code index}'s FFI structs at the given addresses. */
    public static native void resultExportBatch(long result, int index, long arrayAddr, long schemaAddr);

    /** Frees the QueryResult handle; idempotent. */
    public static native void resultFree(long result);

    /** Writes the query stream's schema's FFI struct at {@code schemaAddr}. */
    public static native void streamSchemaExport(long stream, long schemaAddr);

    /** Blocking next: writes a batch's FFI structs and returns 1, or returns 0 at end of stream. */
    public static native int streamNext(long stream, long arrayAddr, long schemaAddr);

    /** Non-blocking next: 1 data, 0 nothing ready or end (distinguish via {@link #streamIsActive}). */
    public static native int streamTryNext(long stream, long arrayAddr, long schemaAddr);

    /** Returns whether the query stream is still active. */
    public static native boolean streamIsActive(long stream);

    /** Cancels the query stream. */
    public static native void streamCancel(long stream);

    /** Frees the QueryStream handle; idempotent. */
    public static native void streamFree(long stream);

    // ---- ingestion ----

    /** Imports the batch at the given FFI addresses and inserts it; returns the row count. */
    public static native long insert(long conn, String source, long arrayAddr, long schemaAddr);

    /** Creates a Writer for the source; returns the writer handle. */
    public static native long writerCreate(long conn, String source);

    /** Imports the batch at the given FFI addresses and writes it through the writer. */
    public static native void writerWrite(long writer, long arrayAddr, long schemaAddr);

    /** Flushes the writer's buffers. */
    public static native void writerFlush(long writer);

    /** Advances the writer's event-time watermark. */
    public static native void writerWatermark(long writer, long timestamp);

    /** Returns the writer's current watermark. */
    public static native long writerCurrentWatermark(long writer);

    /** Writes the writer's source schema's FFI struct at {@code schemaAddr}. */
    public static native void writerSchemaExport(long writer, long schemaAddr);

    /** Closes the writer (flush + native close), consuming the handle; idempotent. */
    public static native void writerClose(long writer);

    /** Leak-backstop free for unclosed writers; never a substitute for {@link #writerClose}. */
    public static native void writerFree(long writer);

    // ---- catalog ----

    /** Lists source names. */
    public static native String[] listSources(long conn);

    /** Lists stream names. */
    public static native String[] listStreams(long conn);

    /** Lists sink names. */
    public static native String[] listSinks(long conn);

    /** Writes the named source's schema's FFI struct at {@code schemaAddr}. */
    public static native void connSchemaExport(long conn, String name, long schemaAddr);
}
