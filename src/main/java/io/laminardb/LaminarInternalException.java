package io.laminardb;

/** Engine-internal failure (core error code 900), including native panics translated by the binding. */
public class LaminarInternalException extends LaminarException {

    /** Constructs the exception with the core's verbatim message and numeric {@code ApiError} code. */
    public LaminarInternalException(String message, int code) {
        super(message, code);
    }
}
