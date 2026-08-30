package io.laminardb;

import io.laminardb.internal.Native;

/**
 * The outcome of a DDL/DML statement: a discriminated union. Accessors are
 * valid only for their kind; the wrong accessor throws
 * {@link LaminarInternalException}.
 */
public final class ExecuteResult implements AutoCloseable {

    /** Statement kinds, mirroring the core's {@code ExecuteResult} enum. */
    public enum Kind {
        DDL,
        QUERY,
        ROWS_AFFECTED,
        METADATA
    }

    private final Object lock = new Object();

    /** Native handle; 0 means closed. Guarded by {@link #lock}. */
    private long handle;

    ExecuteResult(long handle) {
        this.handle = handle;
    }

    /** Returns the statement's kind. */
    public Kind kind() {
        // The lock is held across the native call so close() cannot free the
        // handle mid-read.
        synchronized (lock) {
            requireOpen();
            return switch (Native.executeKind(handle)) {
                case 0 -> Kind.DDL;
                case 1 -> Kind.QUERY;
                case 2 -> Kind.ROWS_AFFECTED;
                case 3 -> Kind.METADATA;
                default -> throw new LaminarInternalException("unknown execute kind", 900);
            };
        }
    }

    /** Returns the created/altered object's name; valid only for {@link Kind#DDL}. */
    public String ddlObject() {
        synchronized (lock) {
            requireOpen();
            return Native.executeDdlObject(handle);
        }
    }

    /** Returns the number of affected rows; valid only for {@link Kind#ROWS_AFFECTED}. */
    public long rowsAffected() {
        synchronized (lock) {
            requireOpen();
            return Native.executeRowsAffected(handle);
        }
    }

    /** Frees the native result; idempotent. */
    @Override
    public void close() {
        long current;
        synchronized (lock) {
            current = handle;
            handle = 0;
        }
        if (current != 0) {
            Native.executeFree(current);
        }
    }

    private void requireOpen() {
        if (handle == 0) {
            throw new LaminarInternalException("ExecuteResult is closed", 900);
        }
    }
}
