package io.laminardb;

import io.laminardb.internal.Native;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.memory.BufferAllocator;

/**
 * A materialized query result: all batches in native memory, imported into
 * {@link ArrowBatch}es lazily per access.
 *
 * <p>Thread-safe for reads; the caller owns the lifecycle: {@link #close()}
 * frees the native result. Closing does not invalidate already-imported
 * batches (their buffers are reference-counted), but unclosed batches leak
 * allocator memory. All accessors block only on native calls.
 */
public final class QueryResult implements AutoCloseable, Iterable<List<Object>> {

    private final BufferAllocator allocator;
    private final Object lock = new Object();

    /** Native handle; 0 means closed. Guarded by {@link #lock}. */
    private long handle;

    QueryResult(long handle, BufferAllocator allocator) {
        this.handle = handle;
        this.allocator = allocator;
    }

    /** Returns the result's Arrow schema (not the binding's {@link Schema} view). */
    public org.apache.arrow.vector.types.pojo.Schema schema() {
        long current = lockedHandle();
        return ArrowBatch.importSchema(allocator, addr -> Native.resultSchemaExport(current, addr));
    }

    /** Returns the total number of rows across all batches. */
    public long numRows() {
        return Native.resultNumRows(lockedHandle());
    }

    /** Returns the number of batches. */
    public int numBatches() {
        return Native.resultNumBatches(lockedHandle());
    }

    /** Returns batch {@code index}; each access wraps a fresh lazy import. */
    public ArrowBatch batch(int index) {
        long current = lockedHandle();
        ArrowArray array = ArrowArray.allocateNew(allocator);
        ArrowSchema schema = ArrowSchema.allocateNew(allocator);
        Native.resultExportBatch(current, index, array.memoryAddress(), schema.memoryAddress());
        return new ArrowBatch(allocator, array, schema);
    }

    /**
     * Returns a lazy stream over the batches. Closing the stream closes
     * nothing; the caller closes this {@code QueryResult} (and each batch it
     * consumed).
     */
    public Stream<ArrowBatch> batches() {
        return IntStream.range(0, numBatches()).mapToObj(this::batch);
    }

    /** Materializes the whole result as row maps (convenience copy). */
    public List<Map<String, Object>> toMaps() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ArrowBatch batch : batches().toList()) {
            rows.addAll(batch.toMaps());
            batch.close();
        }
        return rows;
    }

    /** Frees the native result; idempotent. */
    @Override
    public void close() {
        long current;
        synchronized (lock) {
            current = handle;
            handle = 0;
        }
        if (current != 0) {
            Native.resultFree(current);
        }
    }

    @Override
    public Iterator<List<Object>> iterator() {
        return new RowIterator();
    }

    private final class RowIterator implements Iterator<List<Object>> {
        private int nextBatch;
        private Iterator<List<Object>> rows = List.<List<Object>>of().iterator();

        @Override
        public boolean hasNext() {
            return rows.hasNext() || nextBatch < numBatches();
        }

        @Override
        public List<Object> next() {
            if (!rows.hasNext()) {
                if (nextBatch >= numBatches()) {
                    throw new NoSuchElementException();
                }
                try (ArrowBatch batch = batch(nextBatch++)) {
                    // Materialize under the batch's lifetime; values survive close.
                    rows = io.laminardb.internal.RowConverter.toRows(batch.root())
                            .iterator();
                }
            }
            return rows.next();
        }
    }

    private long lockedHandle() {
        synchronized (lock) {
            if (handle == 0) {
                throw new LaminarInternalException("QueryResult is closed", 900);
            }
            return handle;
        }
    }
}
