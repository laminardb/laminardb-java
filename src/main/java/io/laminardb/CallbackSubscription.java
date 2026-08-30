package io.laminardb;

import io.laminardb.internal.CallbackBridge;
import io.laminardb.internal.Native;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A push subscription delivering batches to a {@link SubscriptionListener}
 * on a dedicated worker thread (plan 03 §4).
 *
 * <p>Single-owner. {@link #cancel()} stops the worker with a bounded join
 * (5 s); {@link #awaitStopped(Duration)} observes the listener's
 * {@code onClose}. The listener owns every delivered {@link ArrowBatch}
 * (close each one after reading — or hold it; its buffers are
 * reference-counted and stay valid).
 *
 * <p>Lifecycle note (core v0.30.0): for query-backed subscriptions the
 * engine reports no exhaustion signal — {@code onClose} fires on
 * {@link #cancel()}, on error, or when a listener throws; an idle worker
 * keeps polling until cancelled. Calling {@code cancel()} from inside
 * {@code onBatch} stalls that worker up to the 5 s join bound, then stops
 * it. At most 64 concurrent callback subscriptions exist per process
 * (error 500 beyond).
 */
public final class CallbackSubscription implements AutoCloseable {

    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private final long subscriptionId;
    private final Object lock = new Object();

    /** Native worker handle; 0 means closed. Guarded by {@link #lock}. */
    private long handle;

    private final CountDownLatch closed = new CountDownLatch(1);

    private CallbackSubscription(long handle, long subscriptionId) {
        this.handle = handle;
        this.subscriptionId = subscriptionId;
    }

    /** Starts a callback subscription over a SQL query. */
    static CallbackSubscription overQuery(long connHandle, String sql, SubscriptionListener listener) {
        return start(listener, id -> Native.subscribeCallbackQuery(connHandle, id, sql));
    }

    /** Starts a callback subscription over a named stream (barriers are skipped). */
    static CallbackSubscription overStream(long connHandle, String streamName, SubscriptionListener listener) {
        return start(listener, id -> Native.subscribeCallbackStream(connHandle, id, streamName));
    }

    private static CallbackSubscription start(
            SubscriptionListener listener, java.util.function.LongUnaryOperator nativeStart) {
        long id = NEXT_ID.getAndIncrement();
        // Register before the native starts so the worker's first delivery
        // finds its listener, and arm the close latch hook (the bridge's
        // onClose path fires it at most once, from the worker).
        CallbackBridge.register(id, listener);
        CallbackSubscription subscription = new CallbackSubscription(0, id);
        CallbackBridge.onClose(id, subscription::markClosed);
        try {
            subscription.handle = nativeStart.applyAsLong(id);
        } catch (RuntimeException e) {
            CallbackBridge.unregister(id);
            subscription.markClosed();
            throw e;
        }
        CallbackBridge.attach(id, subscription.handle);
        return subscription;
    }

    /** Returns whether the worker is still running. */
    public boolean isActive() {
        synchronized (lock) {
            return handle != 0 && Native.callbackIsActive(handle);
        }
    }

    /**
     * Requests stop and joins the worker (bounded by 5 s). The listener's
     * {@code onClose} fires from the worker before it exits.
     */
    public void cancel() {
        long current;
        synchronized (lock) {
            current = handle;
        }
        if (current != 0) {
            Native.callbackJoin(current);
        }
    }

    /** Waits up to {@code timeout} for {@code onClose}; returns whether it fired. */
    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void markClosed() {
        closed.countDown();
    }

    /** Cancels and frees the worker; idempotent. */
    @Override
    public void close() {
        long current;
        synchronized (lock) {
            current = handle;
            handle = 0;
        }
        if (current != 0) {
            Native.callbackFree(current);
        }
        CallbackBridge.unregister(subscriptionId);
        closed.countDown();
    }
}
