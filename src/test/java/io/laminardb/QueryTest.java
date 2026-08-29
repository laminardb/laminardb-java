package io.laminardb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Query roundtrips and value fidelity (plan 02 §6). At this pin, ad-hoc
 * queries read TABLEs and inline VALUES; SOURCE/STREAM data is consumed via
 * subscriptions, which land in Phase 2 (verified against core v0.30.0 and the
 * Python binding's own test suite, which tests queries the same way).
 */
class QueryTest {

    @AfterAll
    static void allocatorDrained() {
        assertThat(LaminarDB.defaultAllocator().getAllocatedMemory()).isZero();
    }

    private static final String SAMPLE =
            """
        SELECT * FROM (VALUES
            (1, 'sensor_a', 42.0),
            (2, 'sensor_b', 43.5),
            (3, NULL, 44.1)
        ) AS t(id, device, value)
        """;

    @Test
    void valuesQueryRoundtripsValuesAndTypes() {
        try (LaminarConnection conn = LaminarDB.open();
                QueryResult result = conn.query(SAMPLE)) {
            List<Map<String, Object>> rows = result.toMaps();
            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).get("id")).isEqualTo(1L);
            assertThat(rows.get(1).get("device")).isEqualTo("sensor_b");
            assertThat(rows.get(2).get("value")).isEqualTo(44.1);
            assertThat(rows.get(2).get("device")).isNull();
        }
    }

    @Test
    void tableInsertAndQueryRoundtrip() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE TABLE metrics (id BIGINT PRIMARY KEY, device VARCHAR, value DOUBLE)");
            try (ExecuteResult insert = conn.execute("INSERT INTO metrics VALUES (1, 'a', 1.5), (2, 'b', -2.25)")) {
                assertThat(insert.kind()).isEqualTo(ExecuteResult.Kind.ROWS_AFFECTED);
                assertThat(insert.rowsAffected()).isEqualTo(2);
            }
            try (QueryResult result = conn.query("SELECT * FROM metrics")) {
                List<Map<String, Object>> rows = result.toMaps();
                assertThat(rows).hasSize(2);
                assertThat(rows.get(0).get("id")).isEqualTo(1L);
                assertThat(rows.get(0).get("device")).isEqualTo("a");
                assertThat(rows.get(1).get("value")).isEqualTo(-2.25);
            }
        }
    }

    @Test
    void schemaReportsCanonicalTypeNames() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute(
                    "CREATE SOURCE events (id BIGINT, label VARCHAR, score DOUBLE," + " active BOOLEAN, ts TIMESTAMP)");
            Schema schema = conn.schema("events");
            assertThat(schema.field("id").typeName()).isEqualTo("Int64");
            assertThat(schema.field("label").typeName()).isEqualTo("Utf8");
            assertThat(schema.field("score").typeName()).isEqualTo("Float64");
            assertThat(schema.field("active").typeName()).isEqualTo("Boolean");
            // The pin maps SQL TIMESTAMP to Arrow Timestamp(µs).
            assertThat(schema.field("ts").typeName()).isEqualTo("Timestamp(us)");
            assertThat(schema.field("id").nullable()).isTrue();
        }
    }

    @Test
    void resultExposesBatchStructureAndArrowSchema() {
        try (LaminarConnection conn = LaminarDB.open();
                QueryResult result = conn.query(SAMPLE)) {
            assertThat(result.numBatches()).isGreaterThanOrEqualTo(1);
            assertThat(result.numRows()).isEqualTo(3);
            try (ArrowBatch batch = result.batch(0)) {
                assertThat(batch.getRowCount()).isEqualTo(3);
                assertThat(batch.schema().getFields()).hasSize(3);
            }
        }
    }

    @Test
    void rowsIteratePositionally() {
        try (LaminarConnection conn = LaminarDB.open();
                QueryResult result = conn.query("SELECT id FROM (VALUES (4), (5)) AS t(id)")) {
            List<List<Object>> rows = new ArrayList<>();
            for (List<Object> row : result) {
                rows.add(row);
            }
            assertThat(rows).containsExactly(List.of(4L), List.of(5L));
        }
    }

    @Test
    void streamOverBoundedQueryDrainsToEof() {
        // At the pin, exhaustion is signalled by next() returning null; the
        // handle stays active until cancelled (verified against v0.30.0).
        try (LaminarConnection conn = LaminarDB.open()) {
            try (QueryStream stream = conn.stream(SAMPLE)) {
                assertThat(stream.isActive()).isTrue();
                int seen = 0;
                ArrowBatch batch;
                while ((batch = stream.next()) != null) {
                    seen += batch.getRowCount();
                    batch.close();
                }
                assertThat(seen).isEqualTo(3);
                stream.cancel();
                assertThat(stream.isActive()).isFalse();
            }
        }
    }

    @Test
    void tryNextReturnsNullWhenIdleAndDrainsWithoutBlocking() {
        try (LaminarConnection conn = LaminarDB.open();
                QueryStream stream = conn.stream(SAMPLE)) {
            int seen = 0;
            // Bounded deadline: poll until the blocking next() reports end.
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (seen < 3 && System.nanoTime() < deadline) {
                try (ArrowBatch batch = stream.tryNext()) {
                    if (batch != null) {
                        seen += batch.getRowCount();
                    }
                }
            }
            assertThat(seen).isEqualTo(3);
            assertThat(stream.tryNext()).isNull();
            assertThat(stream.next()).isNull();
        }
    }

    @Test
    void wrongAccessorThrowsInternal() {
        try (LaminarConnection conn = LaminarDB.open()) {
            try (ExecuteResult result = conn.execute("CREATE SOURCE u (a INT)")) {
                assertThatThrownBy(result::rowsAffected).isInstanceOf(LaminarInternalException.class);
            }
        }
    }
}
