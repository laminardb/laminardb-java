package io.laminardb;

import io.laminardb.internal.Native;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.memory.BufferAllocator;

/**
 * A framed subscription to a named stream, surfacing both data batches and
 * durable checkpoint barriers (plan 03 §2).
 *
 * <p>Single-owner, not thread-safe. The frame lease covers only the native
 * frame; exported batches are reference-count-decoupled, so an {@link
 * ArrowBatch} from an earlier {@link Frame.Data} stays valid after the next
 * {@code nextFrame} call — no eager import required (decision recorded in
 * plan 03 §7). All methods block only on native calls; the with-timeout
 * variants bound the wait.
 */
public final class StreamSubscription implements AutoCloseable {

    private final BufferAllocator allocator;
    private final Object lock = new Object();

    /** Native handle; 0 means closed. Guarded by {@link #lock}. */
    private long handle;

    StreamSubscription(long handle, BufferAllocator allocator) {
        this.handle = handle;
        this.allocator = allocator;
    }

    /** Returns the subscription's Arrow schema. */
    public org.apache.arrow.vector.types.pojo.Schema schema() {
        synchronized (lock) {
            requireOpen();
            return ArrowBatch.importSchema(allocator, addr -> Native.subSchemaExport(handle, addr));
        }
    }

    /** Blocking next frame; null when the subscription has closed. */
    public Frame nextFrame() {
        return pull(true);
    }

    /** Next frame bounded by {@code timeout}; null on timeout or close. */
    public Frame nextFrame(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        return pullBounded(timeout);
    }

    /** Non-blocking frame; null when nothing is ready right now. */
    public Frame tryNextFrame() {
        return pull(false);
    }

    /** Convenience: the next data batch, skipping barriers; null on close. */
    public ArrowBatch nextBatch() {
        Frame frame = nextFrame();
        while (frame instanceof Frame.Barrier) {
            frame = nextFrame();
        }
        return frame == null ? null : ((Frame.Data) frame).batch();
    }

    /** Data batch bounded by {@code timeout}, skipping barriers; null on timeout or close. */
    public ArrowBatch nextBatch(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        Frame frame = nextFrame(timeout);
        while (frame instanceof Frame.Barrier) {
            Duration remaining = durationUntil(deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                return null;
            }
            frame = nextFrame(remaining);
        }
        return frame == null ? null : ((Frame.Data) frame).batch();
    }

    /** Non-blocking data batch, skipping barriers; null when nothing is ready. */
    public ArrowBatch tryNextBatch() {
        Frame frame = tryNextFrame();
        while (frame instanceof Frame.Barrier) {
            frame = tryNextFrame();
        }
        return frame == null ? null : ((Frame.Data) frame).batch();
    }

    private Frame pull(boolean blocking) {
        ArrowArray array = ArrowArray.allocateNew(allocator);
        ArrowSchema schema = ArrowSchema.allocateNew(allocator);
        try {
            int tag;
            // Lock held across the native call so close() cannot free the
            // handle mid-pull.
            synchronized (lock) {
                requireOpen();
                tag = blocking
                        ? Native.subNextFrame(handle, array.memoryAddress(), schema.memoryAddress())
                        : Native.subTryNextFrame(handle, array.memoryAddress(), schema.memoryAddress());
            }
            return frameFor(tag, array, schema);
        } catch (RuntimeException e) {
            array.close();
            schema.close();
            throw e;
        }
    }

    private Frame pullBounded(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        // Bounded spin: the core offers no timed frame wait; poll with
        // exponentially growing pauses capped at 5 ms (plan 03 §4 backoff
        // band), deadline-bounded.
        long pauseNanos = 500_000;
        while (true) {
            Frame frame = tryNextFrame();
            if (frame != null) {
                return frame;
            }
            if (!isActive()) {
                return null;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }
            long sleepNanos = Math.min(pauseNanos, remaining);
            pauseNanos = Math.min(pauseNanos * 2, 5_000_000);
            try {
                TimeUnit.NANOSECONDS.sleep(sleepNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private Frame frameFor(int tag, ArrowArray array, ArrowSchema schema) {
        switch (tag) {
            case 1:
                return new Frame.Data(new ArrowBatch(allocator, array, schema));
            case 2:
                array.close();
                schema.close();
                synchronized (lock) {
                    return new Frame.Barrier(
                            Native.subFrameSequence(handle),
                            Native.subFrameEpoch(handle),
                            Native.subFrameCheckpointId(handle),
                            Native.subFrameThroughSequence(handle));
                }
            default:
                array.close();
                schema.close();
                return null;
        }
    }

    private static Duration durationUntil(long deadlineNanos) {
        return Duration.ofNanos(deadlineNanos - System.nanoTime());
    }

    /** Returns whether the subscription is still active. */
    public boolean isActive() {
        synchronized (lock) {
            requireOpen();
            return Native.subIsActive(handle);
        }
    }

    /** Cancels the subscription; idempotent. */
    public void cancel() {
        synchronized (lock) {
            requireOpen();
            Native.subCancel(handle);
        }
    }

    /** Frees the subscription; idempotent. */
    @Override
    public void close() {
        long current;
        synchronized (lock) {
            current = handle;
            handle = 0;
        }
        if (current != 0) {
            Native.subFree(current);
        }
    }

    private void requireOpen() {
        if (handle == 0) {
            throw new LaminarSubscriptionException("StreamSubscription is closed", 501);
        }
    }
}
