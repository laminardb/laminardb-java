package io.laminardb;

/** Subscription failures (core error codes 500–599). Exercised from Phase 2 (plan 03). */
public class LaminarSubscriptionException extends LaminarException {

    /** Constructs the exception with the core's verbatim message and numeric {@code ApiError} code. */
    public LaminarSubscriptionException(String message, int code) {
        super(message, code);
    }
}
