package io.laminardb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Writer lifecycle, watermark advancement, and conversion errors (plan 02 §6). */
class WriterTest {

    @AfterAll
    static void allocatorDrained() {
        assertThat(LaminarDB.defaultAllocator().getAllocatedMemory()).isZero();
    }

    @Test
    void writerHappyPathAdvancesWatermark() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE events (kind VARCHAR, ts TIMESTAMP)");
            try (Writer writer = conn.writer("events")) {
                assertThat(writer.schema().field("kind").typeName()).isEqualTo("Utf8");
                Map<String, Object> nullKind = new java.util.HashMap<>();
                nullKind.put("kind", null);
                nullKind.put("ts", Instant.parse("2026-08-29T10:00:01Z"));
                writer.write(List.of(Map.of("kind", "open", "ts", Instant.parse("2026-08-29T10:00:00Z")), nullKind));
                writer.flush();
                writer.watermark(Instant.parse("2026-08-29T10:00:05Z"));
                assertThat(writer.currentWatermark())
                        .isEqualTo(Instant.parse("2026-08-29T10:00:05Z").toEpochMilli());
            }
            conn.start();
        }
    }

    @Test
    void writeAfterCloseThrowsWriterClosed() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE t (a INT)");
            Writer writer = conn.writer("t");
            writer.close();
            writer.close();
            assertThatThrownBy(() -> writer.write(List.of(Map.of("a", 1))))
                    .isInstanceOf(LaminarIngestionException.class)
                    .satisfies(e -> assertThat(((LaminarException) e).getCode()).isEqualTo(301));
        }
    }

    @Test
    void wrongValueTypeThrowsBatchSchemaMismatch() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE t (a BIGINT)");
            // A String for a BIGINT column cannot convert; the core reports the
            // schema mismatch (302) or the binding's converter does.
            assertThatThrownBy(() -> conn.insert("t", List.of(Map.of("a", "not a number"))))
                    .isInstanceOf(LaminarIngestionException.class)
                    .satisfies(e -> assertThat(((LaminarException) e).getCode()).isEqualTo(302))
                    .satisfies(e -> assertThat(e.getMessage()).contains("a"));
        }
    }

    @Test
    void missingNullableFieldFillsNullAndMissingRequiredFieldThrows() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE opt (a BIGINT, b VARCHAR)");
            assertThat(conn.insert("opt", List.of(Map.of("a", 1L)))).isEqualTo(1);
            conn.execute("CREATE SOURCE req (a BIGINT, b VARCHAR NOT NULL)");
            assertThatThrownBy(() -> conn.insert("req", List.of(Map.of("a", 1L))))
                    .isInstanceOf(LaminarIngestionException.class)
                    .satisfies(e -> assertThat(((LaminarException) e).getCode()).isEqualTo(302))
                    .satisfies(e -> assertThat(e.getMessage()).contains("b"));
        }
    }

    @Test
    void mapInsertTenThousandRows() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE bulk (id BIGINT, label VARCHAR)");
            List<Map<String, ?>> rows = new java.util.ArrayList<>(10_000);
            for (int i = 0; i < 10_000; i++) {
                rows.add(Map.of("id", (long) i, "label", "row-" + i));
            }
            assertThat(conn.insert("bulk", rows)).isEqualTo(10_000);
        }
    }

    @Test
    void zeroCopyVectorSchemaRootInsertOneHundredThousandRows() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE fast (id BIGINT, label VARCHAR)");
            org.apache.arrow.vector.types.pojo.Schema schema =
                    new org.apache.arrow.vector.types.pojo.Schema(java.util.List.of(
                            new org.apache.arrow.vector.types.pojo.Field(
                                    "id",
                                    new org.apache.arrow.vector.types.pojo.FieldType(
                                            true, new org.apache.arrow.vector.types.pojo.ArrowType.Int(64, true), null),
                                    null),
                            new org.apache.arrow.vector.types.pojo.Field(
                                    "label",
                                    new org.apache.arrow.vector.types.pojo.FieldType(
                                            true, new org.apache.arrow.vector.types.pojo.ArrowType.Utf8(), null),
                                    null)));
            try (org.apache.arrow.vector.VectorSchemaRoot root =
                    org.apache.arrow.vector.VectorSchemaRoot.create(schema, LaminarDB.defaultAllocator())) {
                root.allocateNew();
                org.apache.arrow.vector.BigIntVector ids = (org.apache.arrow.vector.BigIntVector) root.getVector("id");
                org.apache.arrow.vector.VarCharVector labels =
                        (org.apache.arrow.vector.VarCharVector) root.getVector("label");
                for (int i = 0; i < 100_000; i++) {
                    ids.setSafe(i, i);
                    labels.setSafe(i, ("v" + i).getBytes());
                }
                root.setRowCount(100_000);
                assertThat(conn.insert("fast", root)).isEqualTo(100_000);
            }
        }
    }
}
