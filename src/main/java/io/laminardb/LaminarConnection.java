package io.laminardb;

import io.laminardb.internal.Native;
import java.util.Objects;

/**
 * An embedded LaminarDB connection.
 *
 * <p>Thread-safe: an internal lock guards the native handle's lifetime and
 * serializes core calls. All methods are blocking and may be long-running;
 * on virtual threads they pin the carrier for the duration of the native call.
 */
public final class LaminarConnection implements AutoCloseable {

    private final Object lock = new Object();

    /** Native peer pointer; {@code 0} means closed. Guarded by {@link #lock}. */
    private long handle;

    LaminarConnection(long handle) {
        this.handle = handle;
    }

    /**
     * Executes a SQL statement.
     *
     * <p>Blocking.
     *
     * @param sql the statement to execute
     * @throws LaminarConnectionException if this connection is closed (code 101)
     * @throws LaminarException on any statement failure, with the core's error code
     */
    public void execute(String sql) {
        Objects.requireNonNull(sql, "sql");
        synchronized (lock) {
            if (handle == 0) {
                throw new LaminarConnectionException("Connection is closed", 101);
            }
            Native.executeSql(handle, sql);
        }
    }

    /** Returns whether this connection has been closed. */
    public boolean isClosed() {
        synchronized (lock) {
            return handle == 0;
        }
    }

    /**
     * Closes the connection and releases its native resources. Idempotent:
     * subsequent calls are no-ops. Blocking.
     */
    @Override
    public void close() {
        long current;
        synchronized (lock) {
            current = handle;
            handle = 0;
        }
        if (current != 0) {
            Native.closeConnection(current);
        }
    }
}
