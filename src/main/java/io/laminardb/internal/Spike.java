package io.laminardb.internal;

/**
 * Arrow C Data Interface spike natives (plan 01 Task 0.5). Backs the spike
 * test only; Phase 1 folds these crossings into the production export/import
 * natives (plan 02 §2).
 */
public final class Spike {

    static {
        NativeLoader.load();
    }

    private Spike() {}

    /** Writes the Rust-side sample batch into the FFI structs at the given addresses. */
    public static native void exportSampleBatch(long arrayAddr, long schemaAddr);

    /**
     * Consumes the Java-exported FFI structs at the given addresses, verifies
     * their contents in Rust, and returns the row count.
     */
    public static native int importBatch(long arrayAddr, long schemaAddr);
}
