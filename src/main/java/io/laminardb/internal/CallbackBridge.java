package io.laminardb.internal;

import io.laminardb.ArrowBatch;
import io.laminardb.LaminarException;
import io.laminardb.LaminarInternalException;
import io.laminardb.SubscriptionListener;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.memory.RootAllocator;

/**
 * Delivery seam for callback subscriptions (plan 03 §4): the native worker
 * thread calls the statics here, never the listener directly. Each delivery
 * hands the listener an {@link ArrowBatch} owning the exported containers
 * (caller-owned: the listener closes it) and recycles fresh containers whose
 * addresses go back to the worker — one JNI crossing per batch.
 */
public final class CallbackBridge {

    /** Distinct allocator for callback containers (bounded, engine-side lifecycle). */
    static final RootAllocator ALLOCATOR = new RootAllocator(Long.MAX_VALUE);

    private static final Map<Long, Registration> REGISTRY = new ConcurrentHashMap<>();

    private CallbackBridge() {}

    static final class Registration {
        final SubscriptionListener listener;
        volatile Consumer<Void> onCloseHook;
        volatile long nativeHandle;
        // The containers the native worker will export the next batch into.
        ArrowArray array;
        ArrowSchema schema;
        volatile boolean stopped;

        Registration(SubscriptionListener listener) {
            this.listener = listener;
            this.array = ArrowArray.allocateNew(ALLOCATOR);
            this.schema = ArrowSchema.allocateNew(ALLOCATOR);
        }

        long[] addresses() {
            return new long[] {array.memoryAddress(), schema.memoryAddress()};
        }

        long[] deliver() {
            try {
                ArrowBatch batch = ArrowBatch.delivered(ALLOCATOR, array, schema);
                listener.onBatch(batch);
            } catch (Throwable failure) {
                stopped = true;
                LaminarException error = failure instanceof LaminarException laminar
                        ? laminar
                        : new LaminarInternalException("listener threw: " + failure, 900);
                deliverSafely(() -> listener.onError(error));
                requestStop();
            }
            return recycle();
        }

        /** A listener that threw ends its subscription (plan 03 §4). */
        private void requestStop() {
            long handle = nativeHandle;
            if (handle != 0) {
                Native.callbackRequestStop(handle);
            }
        }

        /** Fresh containers for the next delivery; the old ones now belong to the batch. */
        private long[] recycle() {
            array = ArrowArray.allocateNew(ALLOCATOR);
            schema = ArrowSchema.allocateNew(ALLOCATOR);
            return addresses();
        }

        /** Closes any pending (undelivered) containers; delivered ones belong to their batch. */
        void release() {
            array.close();
            schema.close();
        }
    }

    /** Registers a listener; the native worker then acquires the addresses itself. */
    public static void register(long subscriptionId, SubscriptionListener listener) {
        REGISTRY.put(subscriptionId, new Registration(listener));
    }

    /** Attaches the latch hook fired (at most once) when the worker closes. */
    public static void onClose(long subscriptionId, Runnable hook) {
        Registration registration = REGISTRY.get(subscriptionId);
        if (registration != null) {
            registration.onCloseHook = ignored -> hook.run();
        }
    }

    /** Native entry: the worker's initial container acquisition. */
    public static long[] acquire(long subscriptionId) {
        Registration registration = REGISTRY.get(subscriptionId);
        if (registration == null) {
            throw new IllegalStateException("unregistered callback subscription " + subscriptionId);
        }
        return registration.addresses();
    }

    /**
     * Native entry: hands over one exported batch; returns the next
     * addresses. Refuses (throws) once stopped or unregistered — the worker
     * treats the refusal as terminal without a second error delivery, so
     * null addresses never cross.
     */
    public static long[] deliverData(long subscriptionId) {
        Registration registration = REGISTRY.get(subscriptionId);
        if (registration == null || registration.stopped) {
            throw new IllegalStateException("subscription stopping");
        }
        return registration.deliver();
    }

    /** Native entry: reports a failure, mapped per the code table (plan 02 §5). */
    public static void deliverError(long subscriptionId, String message, int code) {
        Registration registration = REGISTRY.get(subscriptionId);
        if (registration == null) {
            return;
        }
        registration.stopped = true;
        LaminarException error = LaminarErrors.forCode(message, code);
        deliverSafely(() -> registration.listener.onError(error));
        unregister(subscriptionId);
    }

    /** Native entry: reports closure. */
    public static void deliverClose(long subscriptionId) {
        Registration registration = REGISTRY.get(subscriptionId);
        if (registration == null) {
            return;
        }
        registration.stopped = true;
        deliverSafely(registration.listener::onClose);
        if (registration.onCloseHook != null) {
            deliverSafely(() -> registration.onCloseHook.accept(null));
        }
        unregister(subscriptionId);
    }

    /** Attaches the native worker handle so failures can request stop. */
    public static void attach(long subscriptionId, long nativeHandle) {
        Registration registration = REGISTRY.get(subscriptionId);
        if (registration != null) {
            registration.nativeHandle = nativeHandle;
        }
    }

    /** Single teardown path: removes the entry and closes its containers. */
    public static void unregister(long subscriptionId) {
        Registration registration = REGISTRY.remove(subscriptionId);
        if (registration != null) {
            registration.release();
        }
    }

    /** Mirrors src/error.rs `category_for_code` (plan 02 §5). */
    static final class LaminarErrors {
        private LaminarErrors() {}

        static LaminarException forCode(String message, int code) {
            if (code >= 100 && code <= 199) {
                return new io.laminardb.LaminarConnectionException(message, code);
            }
            if (code >= 200 && code <= 299) {
                return new io.laminardb.LaminarSchemaException(message, code);
            }
            if (code >= 300 && code <= 399) {
                return new io.laminardb.LaminarIngestionException(message, code);
            }
            if (code >= 400 && code <= 499) {
                return new io.laminardb.LaminarQueryException(message, code);
            }
            if (code >= 500 && code <= 599) {
                return new io.laminardb.LaminarSubscriptionException(message, code);
            }
            if (code == 900) {
                return new io.laminardb.LaminarInternalException(message, code);
            }
            if (code == 901) {
                return new io.laminardb.LaminarShutdownException(message, code);
            }
            return new io.laminardb.LaminarException(message, code);
        }
    }

    private static void deliverSafely(Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException ignored) {
            // A listener that throws from onError/onClose has already ended
            // its subscription; nothing further can be delivered.
        }
    }
}
