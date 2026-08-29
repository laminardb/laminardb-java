package io.laminardb;

/**
 * Connection lifecycle failure (core error codes 100–199), including use of a
 * closed connection (code 101).
 */
public class LaminarConnectionException extends LaminarException {

    /** Constructs the exception with the core's verbatim message and numeric {@code ApiError} code. */
    public LaminarConnectionException(String message, int code) {
        super(message, code);
    }
}
