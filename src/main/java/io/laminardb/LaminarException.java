package io.laminardb;

/**
 * Base runtime exception for LaminarDB operations, carrying the core's numeric
 * {@code ApiError} code. Every native failure maps to a subclass of this type;
 * raw JNI errors never surface to callers.
 */
public class LaminarException extends RuntimeException {

    private final int code;

    public LaminarException(String message, int code) {
        super(message);
        this.code = code;
    }

    /**
     * Returns the LaminarDB error code, e.g. {@code 101} for a closed
     * connection or {@code 401} for a SQL parse error.
     */
    public int getCode() {
        return code;
    }
}
