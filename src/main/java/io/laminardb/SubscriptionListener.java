package io.laminardb;

/**
 * Listener contract for callback (push) subscriptions (plan 03 §4).
 *
 * <p>Implementations are invoked on the subscription's dedicated worker
 * thread — one invocation per batch, never per row. Throwing from
 * {@link #onBatch} terminates the subscription: {@link #onError} is
 * delivered once and the worker stops.
 */
public interface SubscriptionListener {

    /** Called once per data batch; the caller owns the batch's lifecycle. */
    void onBatch(ArrowBatch batch);

    /** Called exactly once on failure (engine error or listener throw), then the worker stops. */
    void onError(LaminarException error);

    /** Called exactly once when the subscription stops, for any reason. */
    void onClose();
}
