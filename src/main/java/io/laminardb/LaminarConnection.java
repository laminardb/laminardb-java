package io.laminardb;

import io.laminardb.internal.Native;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.CDataDictionaryProvider;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;

/**
 * An embedded LaminarDB connection.
 *
 * <p>Thread-safe: an internal lock guards the native handle's lifetime and
 * serializes core calls (the same contract as the Python binding). All
 * methods are blocking and may be long-running; on virtual threads they pin
 * the carrier for the duration of the native call.
 */
public final class LaminarConnection implements AutoCloseable {

    private final BufferAllocator allocator;
    private final Object lock = new Object();

    /** Native peer pointer; {@code 0} means closed. Guarded by {@link #lock}. */
    private long handle;

    LaminarConnection(long handle, BufferAllocator allocator) {
        this.handle = handle;
        this.allocator = allocator;
    }

    /**
     * Executes a DDL/DML statement.
     *
     * <p>Blocking. The caller owns the returned result.
     *
     * @param sql the statement to execute
     * @return the statement outcome (kind, DDL object, or row count)
     * @throws LaminarConnectionException if this connection is closed (code 101)
     * @throws LaminarException on any statement failure, with the core's error code
     */
    public ExecuteResult execute(String sql) {
        Objects.requireNonNull(sql, "sql");
        long result = withConnection(h -> Native.execute(h, sql));
        return new ExecuteResult(result);
    }

    /**
     * Runs a query and materializes all batches.
     *
     * <p>Blocking. The caller closes the returned result.
     */
    public QueryResult query(String sql) {
        Objects.requireNonNull(sql, "sql");
        return new QueryResult(withConnection(h -> Native.query(h, sql)), allocator);
    }

    /**
     * Runs a query and streams its batches as they are produced.
     *
     * <p>Blocking per batch. The caller closes the returned stream.
     */
    public QueryStream stream(String sql) {
        Objects.requireNonNull(sql, "sql");
        return new QueryStream(withConnection(h -> Native.queryStream(h, sql)), allocator);
    }

    /**
     * Inserts row maps into a source, converting against the source's schema
     * (see {@link Writer#write(List)} for the value rules).
     *
     * <p>Blocking.
     *
     * @return the number of rows inserted
     */
    public long insert(String source, List<Map<String, ?>> rows) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(rows, "rows");
        return withConnection(h -> {
            org.apache.arrow.vector.types.pojo.Schema schema = arrowSchema(h, source);
            try (VectorSchemaRoot root = io.laminardb.internal.RowConverter.toRoot(rows, schema, allocator)) {
                return exportAndInsert(h, source, root);
            }
        });
    }

    /** Inserts an Arrow batch, zero-copy. Blocking.
     *
     * @return the number of rows inserted
     */
    public long insert(String source, ArrowBatch batch) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(batch, "batch");
        return insert(source, batch.root());
    }

    /** Inserts an exported VectorSchemaRoot, zero-copy. Blocking.
     *
     * @return the number of rows inserted
     */
    public long insert(String source, VectorSchemaRoot root) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(root, "root");
        return withConnection(h -> exportAndInsert(h, source, root));
    }

    private long exportAndInsert(long h, String source, VectorSchemaRoot root) {
        ArrowArray array = ArrowArray.allocateNew(allocator);
        ArrowSchema schema = ArrowSchema.allocateNew(allocator);
        boolean consumed = false;
        try (CDataDictionaryProvider provider = new CDataDictionaryProvider()) {
            Data.exportVectorSchemaRoot(allocator, root, provider, array, schema);
            long rows = Native.insert(h, source, array.memoryAddress(), schema.memoryAddress());
            consumed = true;
            return rows;
        } finally {
            if (consumed) {
                array.markReleased();
                schema.markReleased();
            }
            array.close();
            schema.close();
        }
    }

    /**
     * Creates a streaming writer for a source.
     *
     * <p>Blocking.
     *
     * @return a single-owner writer; the caller closes it
     */
    public Writer writer(String source) {
        Objects.requireNonNull(source, "source");
        return new Writer(withConnection(h -> Native.writerCreate(h, source)), allocator);
    }

    /** Starts all pipelines. Blocking. */
    public void start() {
        withConnection(h -> {
            Native.start(h);
            return null;
        });
    }

    /** Runs a checkpoint and returns its id. Blocking. */
    public long checkpoint() {
        return withConnection(Native::checkpoint);
    }

    /** Returns whether checkpointing is configured on this connection. */
    public boolean isCheckpointEnabled() {
        return withConnection(Native::isCheckpointEnabled);
    }

    /** Shuts the engine down; the connection handle stays valid for close(). Blocking. */
    public void shutdown() {
        withConnection(h -> {
            Native.shutdown(h);
            return null;
        });
    }

    /** Returns the named source's schema as the binding's {@link Schema} view. */
    public Schema schema(String name) {
        Objects.requireNonNull(name, "name");
        return new Schema(withConnection(h -> arrowSchema(h, name)));
    }

    private org.apache.arrow.vector.types.pojo.Schema arrowSchema(long h, String name) {
        return ArrowBatch.importSchema(allocator, addr -> Native.connSchemaExport(h, name, addr));
    }

    /** Lists source names. Blocking. */
    public List<String> listSources() {
        return List.of(withConnection(Native::listSources));
    }

    /** Lists stream names. Blocking. */
    public List<String> listStreams() {
        return List.of(withConnection(Native::listStreams));
    }

    /** Lists sink names. Blocking. */
    public List<String> listSinks() {
        return List.of(withConnection(Native::listSinks));
    }

    /** Returns whether this connection has been closed. */
    public boolean isClosed() {
        synchronized (lock) {
            return handle == 0;
        }
    }

    /**
     * Closes the connection and releases its native resources. Idempotent:
     * subsequent calls are no-ops. Blocking.
     */
    @Override
    public void close() {
        long current;
        synchronized (lock) {
            current = handle;
            handle = 0;
        }
        if (current != 0) {
            Native.close(current);
        }
    }

    private <R> R withConnection(ConnectionOp<R> op) {
        // The lock is held across the native call so close() cannot free the
        // handle mid-call (use-after-close is unreachable from this class).
        synchronized (lock) {
            if (handle == 0) {
                throw new LaminarConnectionException("Connection is closed", 101);
            }
            return op.run(handle);
        }
    }

    @FunctionalInterface
    private interface ConnectionOp<R> {
        R run(long handle);
    }
}
