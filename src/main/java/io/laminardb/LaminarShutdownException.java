package io.laminardb;

/** Operation refused because the database is shut down (core error code 901). */
public class LaminarShutdownException extends LaminarException {

    /** Constructs the exception with the core's verbatim message and numeric {@code ApiError} code. */
    public LaminarShutdownException(String message, int code) {
        super(message, code);
    }
}
