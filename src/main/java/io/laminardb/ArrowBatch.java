package io.laminardb;

import io.laminardb.internal.NativeLoader;
import java.util.List;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.CDataDictionaryProvider;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * One Arrow record batch handed over from the native side. The batch is
 * imported into a {@link VectorSchemaRoot} lazily on first {@link #root()}
 * access, so query paths that never read data pay no import cost.
 *
 * <p>Lifecycle: the exported buffers are reference-counted by the C Data
 * Interface, so closing the native-side owner (a {@code QueryResult},
 * {@code QueryStream}, or {@code Writer}) never invalidates this batch or an
 * already-imported root — but skipping {@link #close()} leaks allocator
 * memory until the allocator closes. Use try-with-resources.
 */
public final class ArrowBatch implements AutoCloseable {

    static {
        NativeLoader.load();
    }

    private final BufferAllocator allocator;
    private final ArrowArray array;
    private final ArrowSchema schema;
    private VectorSchemaRoot root;

    ArrowBatch(BufferAllocator allocator, ArrowArray array, ArrowSchema schema) {
        this.allocator = allocator;
        this.array = array;
        this.schema = schema;
    }

    /** Returns the batch as a {@link VectorSchemaRoot}, importing it on first access. */
    public VectorSchemaRoot root() {
        if (root == null) {
            try (CDataDictionaryProvider provider = new CDataDictionaryProvider()) {
                root = Data.importVectorSchemaRoot(allocator, array, schema, provider);
            }
        }
        return root;
    }

    /** Returns the batch's Arrow schema (forces the lazy import). */
    public Schema schema() {
        return root().getSchema();
    }

    /** Returns the number of rows in this batch. */
    public int getRowCount() {
        return root().getRowCount();
    }

    /** Closes the batch, releasing its native memory; idempotent. */
    @Override
    public void close() {
        if (root != null) {
            // The importer owns the FFI structs after import; these closes are
            // no-ops for them and release the imported buffers via the root.
            root.close();
            root = null;
        } else {
            // Never imported: the release callbacks arrow-rs installed fire
            // only from the consumer side, so release explicitly before
            // freeing the Java containers (close() alone would leak the
            // Rust-side Arc/buffer clones).
            array.release();
            schema.release();
        }
        array.close();
        schema.close();
    }

    /** Materializes this batch as a list of row maps (convenience copy). */
    List<java.util.Map<String, Object>> toMaps() {
        return io.laminardb.internal.RowConverter.toMaps(root());
    }

    /** Imports an FFI schema exported by the native side into the canonical Arrow schema. */
    static org.apache.arrow.vector.types.pojo.Schema importSchema(BufferAllocator allocator, FfiSchemaExport exporter) {
        ArrowSchema schema = ArrowSchema.allocateNew(allocator);
        try (CDataDictionaryProvider provider = new CDataDictionaryProvider()) {
            exporter.into(schema.memoryAddress());
            return Data.importSchema(allocator, schema, provider);
        } finally {
            schema.close();
        }
    }

    @FunctionalInterface
    interface FfiSchemaExport {
        void into(long schemaAddr);
    }
}
