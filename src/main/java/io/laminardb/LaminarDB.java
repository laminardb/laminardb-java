package io.laminardb;

import io.laminardb.internal.Native;

/** Entry point for embedded LaminarDB usage. */
public final class LaminarDB {

    private LaminarDB() {}

    /**
     * Opens an in-memory connection with default configuration.
     *
     * <p>Blocking: initializes the engine and its background runtime on the
     * calling thread.
     *
     * @return an open connection, never {@code null}
     * @throws LaminarConnectionException if the engine fails to start
     */
    public static LaminarConnection open() {
        return new LaminarConnection(Native.openDefault());
    }

    /**
     * Returns the native binding version, which tracks the pinned core
     * version (binding {@code 0.30.0} ships core {@code v0.30.0}).
     */
    public static String getVersion() {
        return Native.version();
    }
}
