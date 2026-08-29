package io.laminardb.internal;

/** The Phase 0 native surface. Referenced only by {@code io.laminardb}, never by user code. */
public final class Native {

    static {
        NativeLoader.load();
    }

    private Native() {}

    /** Opens a default in-memory connection; returns the native handle. */
    public static native long openDefault();

    /** Executes a SQL statement on the connection's native handle. */
    public static native void executeSql(long conn, String sql);

    /** Frees the connection handle; idempotent and null-tolerant. */
    public static native void closeConnection(long conn);

    /** Returns the native binding version. */
    public static native String version();
}
