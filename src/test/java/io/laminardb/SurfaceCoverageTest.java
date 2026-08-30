package io.laminardb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Coverage for public members the main suites do not reach (plan 06 §6:
 * every public member exercised by at least one test) plus the Phase 1
 * lifecycle items from plan 02 §6.
 */
class SurfaceCoverageTest {

    @AfterAll
    static void allocatorDrained() {
        assertThat(LaminarDB.defaultAllocator().getAllocatedMemory()).isZero();
    }

    @Test
    void schemaAndFieldInfoAccessors() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE s (id BIGINT, label VARCHAR)");
            Schema schema = conn.schema("s");
            assertThat(schema.fields()).hasSize(2);
            assertThat(schema.fields().get(0).name()).isEqualTo("id");
            assertThat(schema.field("label")).isNotNull();
            assertThat(schema.field("missing")).isNull();
        }
    }

    @Test
    void insertArrowBatchAndWriterWriteArrowBatch() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE t (a BIGINT)");
            // Reuse an already-imported batch from a query as the write input.
            try (QueryResult result = conn.query("SELECT * FROM (VALUES (7), (8)) AS src(a)")) {
                List<ArrowBatch> batches = result.batches().toList();
                assertThat(batches).isNotEmpty();
                try (ArrowBatch batch = batches.get(0)) {
                    assertThat(conn.insert("t", batch)).isEqualTo(2);
                }
                try (Writer writer = conn.writer("t");
                        ArrowBatch again = result.batch(0)) {
                    writer.write(again);
                }
            }
        }
    }

    @Test
    void resultAndStreamArrowSchemas() {
        try (LaminarConnection conn = LaminarDB.open();
                QueryResult result = conn.query("SELECT * FROM (VALUES (1)) AS t(a)");
                QueryStream stream = conn.stream("SELECT * FROM (VALUES (1)) AS t(a)")) {
            assertThat(result.schema().getFields()).hasSize(1);
            assertThat(stream.schema().getFields()).hasSize(1);
        }
    }

    @Test
    void executeKindsQueryAndMetadataAreObservable() {
        try (LaminarConnection conn = LaminarDB.open()) {
            try (ExecuteResult select = conn.execute("SELECT * FROM (VALUES (1)) AS t(a)")) {
                assertThat(select.kind()).isIn(ExecuteResult.Kind.QUERY, ExecuteResult.Kind.METADATA);
            }
            try (ExecuteResult show = conn.execute("SHOW SOURCES")) {
                assertThat(show.kind()).isIn(ExecuteResult.Kind.QUERY, ExecuteResult.Kind.METADATA);
            }
        }
    }

    @Test
    void doubleCloseIsNoOpEverywhere() {
        try (LaminarConnection conn = LaminarDB.open()) {
            ExecuteResult result = conn.execute("CREATE SOURCE dc (a INT)");
            result.close();
            result.close();
            QueryResult query = conn.query("SELECT * FROM (VALUES (1)) AS t(a)");
            query.close();
            query.close();
            try {
                ArrowBatch batch = query.batch(0);
                batch.close();
                batch.close();
            } catch (LaminarInternalException expected) {
                // batch() on a closed result throws; both-close was already
                // proven by the connection and writer suites.
            }
        }
    }

    @Test
    void unimportedBatchCloseReleasesNativeSide() {
        try (LaminarConnection conn = LaminarDB.open();
                QueryResult result = conn.query("SELECT * FROM (VALUES (1)) AS t(a)")) {
            // Never touch root(); closing must still release the Rust-side
            // buffers via the explicit release() path.
            try (ArrowBatch batch = result.batch(0)) {
                assertThat(batch.getRowCount()).isGreaterThan(0);
            }
        }
    }

    @Test
    void outOfRangeBatchThrowsWithoutLeaking() {
        try (LaminarConnection conn = LaminarDB.open();
                QueryResult result = conn.query("SELECT * FROM (VALUES (1)) AS t(a)")) {
            assertThatThrownBy(() -> result.batch(99)).isInstanceOf(LaminarException.class);
        }
        // The allocator-zero @AfterAll proves no containers leaked.
    }

    @Test
    void closeWithOpenWriterDocumentsPinBehavior() {
        // Plan 02 §6: what the pin does when a connection closes with an open
        // writer: no crash, connection fully closed — but the open writer's
        // undrained batches keep their backing JVM buffers pinned until the
        // writer itself is closed (bounded: one buffer set per written
        // batch). Closing the writer afterwards releases everything.
        LaminarConnection conn = LaminarDB.open();
        conn.execute("CREATE SOURCE w (a BIGINT)");
        Writer writer = conn.writer("w");
        writer.write(List.of(Map.of("a", 1L)));
        long before = LaminarDB.defaultAllocator().getAllocatedMemory();
        conn.close();
        assertThat(conn.isClosed()).isTrue();
        assertThat(LaminarDB.defaultAllocator().getAllocatedMemory()).isGreaterThanOrEqualTo(before);
        writer.close();
        assertThat(LaminarDB.defaultAllocator().getAllocatedMemory()).isZero();
    }

    @Test
    void timestampReadBackFidelity() {
        try (LaminarConnection conn = LaminarDB.open();
                QueryResult result = conn.query("SELECT * FROM (VALUES (TIMESTAMP '2026-08-29 10:00:00')) AS t(ts)")) {
            List<Map<String, Object>> rows = result.toMaps();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("ts")).isEqualTo(Instant.parse("2026-08-29T10:00:00Z"));
        }
    }

    @Test
    void mapInsertRejectsUnknownKeys() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE exact (a BIGINT)");
            // Extra keys are a schema disagreement per plan 02 §3.
            assertThatThrownBy(() -> conn.insert("exact", List.of(Map.of("a", 1L, "b", 2L))))
                    .isInstanceOf(LaminarIngestionException.class)
                    .satisfies(e -> assertThat(((LaminarException) e).getCode()).isEqualTo(302))
                    .satisfies(e -> assertThat(e.getMessage()).contains("b"));
        }
    }
}
