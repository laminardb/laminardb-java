package io.laminardb;

/** Schema and catalog failures (core error codes 200–299), e.g. a missing source (200) or a duplicate (201). */
public class LaminarSchemaException extends LaminarException {

    /** Constructs the exception with the core's verbatim message and numeric {@code ApiError} code. */
    public LaminarSchemaException(String message, int code) {
        super(message, code);
    }
}
