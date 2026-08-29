package io.laminardb.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.CDataDictionaryProvider;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

/**
 * Spike A (plan 01 Task 0.5): proves the Arrow C Data Interface crossing in
 * both directions. The exact arrow-java sequence that worked is recorded in
 * plan 01 §Spike results.
 */
class ArrowCSpikeTest {

    private static final int ROWS = 3;

    @Test
    void rustToJavaExportRoundtrip() {
        try (BufferAllocator allocator = new RootAllocator()) {
            try (ArrowArray array = ArrowArray.allocateNew(allocator);
                    ArrowSchema schema = ArrowSchema.allocateNew(allocator)) {
                Spike.exportSampleBatch(array.memoryAddress(), schema.memoryAddress());
                try (VectorSchemaRoot root =
                        Data.importVectorSchemaRoot(allocator, array, schema, new CDataDictionaryProvider())) {
                    assertThat(root.getRowCount()).isEqualTo(ROWS);
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    VarCharVector names = (VarCharVector) root.getVector("name");
                    assertThat(ids.get(0)).isEqualTo(1L);
                    assertThat(ids.get(2)).isEqualTo(3L);
                    assertThat(new String(names.get(1))).isEqualTo("beta");
                }
            }
            assertThat(allocator.getAllocatedMemory()).isZero();
        }
    }

    @Test
    void javaToRustImportIsVerifiedInRust() {
        try (BufferAllocator allocator = new RootAllocator();
                VectorSchemaRoot root = sampleRoot(allocator);
                ArrowArray array = ArrowArray.allocateNew(allocator);
                ArrowSchema schema = ArrowSchema.allocateNew(allocator)) {
            Data.exportVectorSchemaRoot(allocator, root, new CDataDictionaryProvider(), array, schema);
            // Ownership of the exported structs moves to Rust on import; the
            // Java wrappers must not release them again.
            assertThat(Spike.importBatch(array.memoryAddress(), schema.memoryAddress()))
                    .isEqualTo(ROWS);
            array.markReleased();
            schema.markReleased();
        }
    }

    @Test
    void tenThousandRoundtripsKeepAllocatorAccountingStable() {
        // Bounded: 10_000 iterations; proves no leak and no double-free across
        // repeated crossings.
        try (BufferAllocator allocator = new RootAllocator()) {
            for (int i = 0; i < 10_000; i++) {
                try (ArrowArray array = ArrowArray.allocateNew(allocator);
                        ArrowSchema schema = ArrowSchema.allocateNew(allocator)) {
                    Spike.exportSampleBatch(array.memoryAddress(), schema.memoryAddress());
                    try (VectorSchemaRoot imported =
                            Data.importVectorSchemaRoot(allocator, array, schema, new CDataDictionaryProvider())) {
                        assertThat(imported.getRowCount()).isEqualTo(ROWS);
                    }
                }
            }
            assertThat(allocator.getAllocatedMemory()).isZero();
        }
    }

    private static VectorSchemaRoot sampleRoot(BufferAllocator allocator) {
        List<Field> fields = List.of(
                new Field("id", new FieldType(false, new ArrowType.Int(64, true), null), null),
                new Field("name", new FieldType(false, new ArrowType.Utf8(), null), null));
        VectorSchemaRoot root = VectorSchemaRoot.create(new Schema(fields), allocator);
        root.allocateNew();
        root.setRowCount(ROWS);
        BigIntVector ids = (BigIntVector) root.getVector("id");
        VarCharVector names = (VarCharVector) root.getVector("name");
        ids.setSafe(0, 1L);
        ids.setSafe(1, 2L);
        ids.setSafe(2, 3L);
        names.setSafe(0, "alpha".getBytes());
        names.setSafe(1, "beta".getBytes());
        names.setSafe(2, "gamma".getBytes());
        return root;
    }
}
