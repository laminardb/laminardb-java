package io.laminardb;

/** Query failures (core error codes 400–499), including SQL parse errors (401) and cancellation (402). */
public class LaminarQueryException extends LaminarException {

    /** Constructs the exception with the core's verbatim message and numeric {@code ApiError} code. */
    public LaminarQueryException(String message, int code) {
        super(message, code);
    }
}
