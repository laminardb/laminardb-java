package io.laminardb;

import io.laminardb.internal.Native;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Connection configuration, backed by a native config handle so the Java
 * side can never drift from the core's field set. Build via {@link #builder()}.
 *
 * <p>The config owns its native handle until {@link #close()} (or until the
 * process ends); one config may open several connections — the value is
 * cloned at open, not consumed.
 */
public final class LaminarConfig implements AutoCloseable {

    private final Object lock = new Object();

    /** Native handle; 0 means closed. Guarded by {@link #lock}. */
    private long handle;

    private LaminarConfig(long handle) {
        this.handle = handle;
    }

    /** Returns a new builder with core defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Frees the native config; idempotent. */
    @Override
    public void close() {
        long current;
        synchronized (lock) {
            current = handle;
            handle = 0;
        }
        if (current != 0) {
            Native.configDrop(current);
        }
    }

    long handle() {
        // Callers hold the returned value only to pass it straight into a
        // native call on this thread; the value is immutable while open.
        synchronized (lock) {
            if (handle == 0) {
                throw new LaminarInternalException("LaminarConfig is closed", 900);
            }
            return handle;
        }
    }

    /** Builder over the native config; one setter per mapped field (plan 02 §1). */
    public static final class Builder {

        private final LaminarConfig config = new LaminarConfig(Native.configNew());

        private Builder() {}

        /** Sets the streaming channel buffer size ({@code default_buffer_size}). */
        public Builder bufferSize(long size) {
            Native.configSetBufferSize(config.handle(), size);
            return this;
        }

        /** Sets the storage directory for local durability; null = in-memory only. */
        public Builder storageDir(Path dir) {
            Native.configSetStorageDir(config.handle(), dir == null ? null : dir.toString());
            return this;
        }

        /** Sets the checkpoint interval; 0 or absent disables checkpointing. */
        public Builder checkpointIntervalMs(long intervalMs) {
            Native.configSetCheckpointIntervalMs(config.handle(), intervalMs);
            return this;
        }

        /** Sets incremental emission for keyed aggregate materialized views. */
        public Builder incrementalEmit(boolean value) {
            Native.configSetIncrementalEmit(config.handle(), value);
            return this;
        }

        /** Sets the cloud checkpoint URL, e.g. {@code s3://bucket/prefix}. */
        public Builder objectStoreUrl(String url) {
            Native.configSetObjectStoreUrl(config.handle(), url);
            return this;
        }

        /** Sets one object-store credential/config option. */
        public Builder objectStoreOption(String key, String value) {
            Objects.requireNonNull(key, "key");
            Native.configSetObjectStoreOption(config.handle(), key, value);
            return this;
        }

        /** Returns the built config. */
        public LaminarConfig build() {
            return config;
        }
    }
}
