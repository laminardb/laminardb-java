package io.laminardb;

import io.laminardb.internal.Native;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.memory.BufferAllocator;

/**
 * A streaming query result: batches arrive as the pipeline produces them.
 *
 * <p>Single-owner, not thread-safe. {@link #next()} blocks until a batch is
 * available and returns null at end of stream; {@link #tryNext()} returns
 * null when nothing is ready right now — distinguish end-of-stream via
 * {@link #isActive()}. The caller closes each returned {@link ArrowBatch}.
 */
public final class QueryStream implements AutoCloseable {

    private final BufferAllocator allocator;
    private final Object lock = new Object();

    /** Native handle; 0 means closed. Guarded by {@link #lock}. */
    private long handle;

    QueryStream(long handle, BufferAllocator allocator) {
        this.handle = handle;
        this.allocator = allocator;
    }

    /** Returns the stream's Arrow schema. */
    public org.apache.arrow.vector.types.pojo.Schema schema() {
        long current = lockedHandle();
        return ArrowBatch.importSchema(allocator, addr -> Native.streamSchemaExport(current, addr));
    }

    /** Blocking next; returns null at end of stream. */
    public ArrowBatch next() {
        return pull(true);
    }

    /** Non-blocking next; returns null when no batch is ready right now. */
    public ArrowBatch tryNext() {
        return pull(false);
    }

    private ArrowBatch pull(boolean blocking) {
        long current = lockedHandle();
        ArrowArray array = ArrowArray.allocateNew(allocator);
        ArrowSchema schema = ArrowSchema.allocateNew(allocator);
        int got = blocking
                ? Native.streamNext(current, array.memoryAddress(), schema.memoryAddress())
                : Native.streamTryNext(current, array.memoryAddress(), schema.memoryAddress());
        if (got != 1) {
            array.close();
            schema.close();
            return null;
        }
        return new ArrowBatch(allocator, array, schema);
    }

    /** Returns whether the stream is still active. */
    public boolean isActive() {
        return Native.streamIsActive(lockedHandle());
    }

    /** Cancels the stream; idempotent. */
    public void cancel() {
        Native.streamCancel(lockedHandle());
    }

    /** Frees the native stream; idempotent. */
    @Override
    public void close() {
        long current;
        synchronized (lock) {
            current = handle;
            handle = 0;
        }
        if (current != 0) {
            Native.streamFree(current);
        }
    }

    private long lockedHandle() {
        synchronized (lock) {
            if (handle == 0) {
                throw new LaminarInternalException("QueryStream is closed", 900);
            }
            return handle;
        }
    }
}
