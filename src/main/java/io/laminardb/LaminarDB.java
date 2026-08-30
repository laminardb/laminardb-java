package io.laminardb;

import io.laminardb.internal.Native;
import java.util.Objects;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;

/** Entry point for embedded LaminarDB usage. */
public final class LaminarDB {

    /** In-memory routing key, mirroring the Python binding's path sugar. */
    public static final String MEMORY = ":memory:";

    private static final RootAllocator DEFAULT_ALLOCATOR = new RootAllocator(Long.MAX_VALUE);

    private LaminarDB() {}

    /**
     * Opens an in-memory connection with default configuration.
     *
     * <p>Blocking: initializes the engine and its background runtime on the
     * calling thread.
     *
     * @return an open connection, never {@code null}
     * @throws LaminarException if the engine fails to start (code 900 at this
     *         binding; the core maps engine-start failures to internal errors)
     */
    public static LaminarConnection open() {
        return new LaminarConnection(Native.openDefault(), defaultAllocator());
    }

    /**
     * Opens a connection at a path: {@code ":memory:"} for in-memory, or a
     * storage directory for local durability (folded into the config's
     * {@code storage_dir}).
     *
     * <p>Blocking.
     *
     * @param path {@code ":memory:"} or a storage directory path
     * @return an open connection, never {@code null}
     * @throws LaminarException if the engine fails to start
     */
    public static LaminarConnection open(String path) {
        return open(path, LaminarConfig.builder().build());
    }

    /**
     * Opens a connection at a path with explicit configuration. A non-memory
     * path is folded into the config's storage directory (the config is
     * mutated accordingly, per plan 02 §1); the config remains usable for
     * other opens.
     *
     * <p>Blocking.
     *
     * @param path {@code ":memory:"} or a storage directory path
     * @param config explicit configuration
     * @return an open connection, never {@code null}
     * @throws LaminarException if the engine fails to start
     */
    public static LaminarConnection open(String path, LaminarConfig config) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(config, "config");
        if (!MEMORY.equals(path)) {
            Native.configSetStorageDir(config.handle(), path);
        }
        return new LaminarConnection(Native.openWithConfig(config.handle()), defaultAllocator());
    }

    /** Returns the native binding version, which tracks the pinned core version. */
    public static String getVersion() {
        return Native.version();
    }

    /**
     * Returns the process-wide allocator backing lazy Arrow imports. Most
     * apps never close it; long-lived hosting apps may free it via
     * {@link #shutdownDefaultAllocator()} after closing all connections and
     * batches.
     */
    public static BufferAllocator defaultAllocator() {
        return DEFAULT_ALLOCATOR;
    }

    /**
     * Closes the process-wide allocator; allocator-backed objects become
     * unusable. Intended for hosting apps at shutdown; not exercised by the
     * in-repo suite because closing the shared allocator would break every
     * other test in the same JVM (verified instead by the allocator-zero
     * accounting that runs after each suite).
     */
    public static void shutdownDefaultAllocator() {
        DEFAULT_ALLOCATOR.close();
    }
}
