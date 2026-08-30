package io.laminardb;

import io.laminardb.internal.Native;
import java.time.Instant;
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
 * A streaming writer for one source. Single-owner, not thread-safe: calls
 * from multiple threads must be externally serialized.
 *
 * <p>Blocking: every method may block on the engine. {@link #close()} flushes
 * and closes the native writer; after close, {@link #write} throws
 * {@code LaminarIngestionException} code 301.
 */
public final class Writer implements AutoCloseable {

    private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

    private final BufferAllocator allocator;
    private final Object lock = new Object();
    private final WriterBackstop backstop;

    /** Native handle; 0 means closed. Guarded by {@link #lock}. */
    private long handle;

    Writer(long handle, BufferAllocator allocator) {
        this.handle = handle;
        this.allocator = allocator;
        // Leak backstop only (plan 00 §5): explicit close() is the real path.
        // The handle is claimed atomically (getAndSet), so whichever of close
        // or the cleaner runs first frees it and the other becomes a no-op —
        // exactly one free, no use-after-free. close() never calls clean():
        // joining the cleaner thread from user code deadlocks when another
        // backstop is mid-flight.
        this.backstop = new WriterBackstop(handle);
        CLEANER.register(this, backstop);
    }

    private static final class WriterBackstop implements Runnable {
        private final java.util.concurrent.atomic.AtomicLong handle;

        WriterBackstop(long handle) {
            this.handle = new java.util.concurrent.atomic.AtomicLong(handle);
        }

        /** Claims the handle: the claimer is the sole freer. */
        long claim() {
            return handle.getAndSet(0);
        }

        @Override
        public void run() {
            long current = claim();
            if (current != 0) {
                io.laminardb.internal.Native.writerFree(current);
            }
        }
    }

    /** Writes one batch, zero-copy: the root is exported via the C Data Interface. */
    public void write(VectorSchemaRoot root) {
        Objects.requireNonNull(root, "root");
        withWriter(handle -> {
            ArrowArray array = ArrowArray.allocateNew(allocator);
            ArrowSchema schema = ArrowSchema.allocateNew(allocator);
            boolean consumed = false;
            try (CDataDictionaryProvider provider = new CDataDictionaryProvider()) {
                Data.exportVectorSchemaRoot(allocator, root, provider, array, schema);
                Native.writerWrite(handle, array.memoryAddress(), schema.memoryAddress());
                consumed = true;
            } finally {
                if (consumed) {
                    // Ownership of the exported structs moved to Rust; the
                    // Java wrappers must not release them again.
                    array.markReleased();
                    schema.markReleased();
                }
                array.close();
                schema.close();
            }
        });
    }

    /** Writes one already-exported batch, zero-copy. */
    public void write(ArrowBatch batch) {
        Objects.requireNonNull(batch, "batch");
        write(batch.root());
    }

    /**
     * Converts row maps to an Arrow batch against the source's schema and
     * writes it. Value rules: {@code Integer/Long → Int32/Int64}, {@code
     * Double → Float64}, {@code String → Utf8}, {@code Instant} or epoch-ms
     * {@code Long → Timestamp}; nulls honored for nullable fields; anything
     * else throws {@code LaminarIngestionException} 302 naming the field.
     */
    public void write(List<Map<String, ?>> rows) {
        Objects.requireNonNull(rows, "rows");
        withWriter(handle -> {
            try (VectorSchemaRoot root = io.laminardb.internal.RowConverter.toRoot(rows, arrowSchema(), allocator)) {
                write(root);
            }
        });
    }

    /** Advances the writer's event-time watermark to {@code timestampMillis}. */
    public void watermark(long timestampMillis) {
        withWriter(handle -> Native.writerWatermark(handle, timestampMillis));
    }

    /** Advances the writer's event-time watermark to an instant's epoch millis. */
    public void watermark(Instant timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        watermark(timestamp.toEpochMilli());
    }

    /** Returns the writer's current watermark. */
    public long currentWatermark() {
        // The lock is held across the native call so close() cannot free the
        // handle mid-read.
        synchronized (lock) {
            requireOpen();
            return Native.writerCurrentWatermark(handle);
        }
    }

    /** Returns the source's schema as the binding's {@link Schema} view. */
    public Schema schema() {
        return new Schema(arrowSchema());
    }

    private org.apache.arrow.vector.types.pojo.Schema arrowSchema() {
        synchronized (lock) {
            requireOpen();
            return ArrowBatch.importSchema(allocator, addr -> Native.writerSchemaExport(handle, addr));
        }
    }

    /** Flushes the writer's buffers. */
    public void flush() {
        withWriter(Native::writerFlush);
    }

    /**
     * Flushes and closes the native writer, consuming the handle; idempotent.
     */
    @Override
    public void close() {
        long current;
        synchronized (lock) {
            current = handle;
            handle = 0;
        }
        if (current == 0) {
            return;
        }
        // The atomic claim decides the freer exactly once: if the cleaner
        // backstop already claimed (only possible for an abandoned writer,
        // which cannot reach here), it owns the free.
        if (backstop.claim() == current) {
            Native.writerClose(current);
        }
    }

    private interface WriterOp {
        void run(long handle);
    }

    private void withWriter(WriterOp op) {
        // The lock is held across the native call so close() cannot free the
        // handle mid-write (same discipline as LaminarConnection).
        synchronized (lock) {
            if (handle == 0) {
                throw new LaminarIngestionException("Writer is closed", 301);
            }
            op.run(handle);
        }
    }

    private void requireOpen() {
        if (handle == 0) {
            throw new LaminarIngestionException("Writer is closed", 301);
        }
    }
}
