package io.laminardb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Configured open, durability, and checkpoint behavior (plan 02 §6). */
class ConfigTest {

    @AfterAll
    static void allocatorDrained() {
        assertThat(LaminarDB.defaultAllocator().getAllocatedMemory()).isZero();
    }

    @Test
    void memoryPathOpens() {
        try (LaminarConnection conn = LaminarDB.open(":memory:")) {
            assertThat(conn.isClosed()).isFalse();
        }
    }

    @Test
    void storageDirOpenRunsAndCheckpointsAdvance(@TempDir Path dir) {
        // At the pin, reopen-time catalog recovery is not a guaranteed api
        // behavior (the Python binding tests no reopen either); what is
        // observable is a storage-dir engine running checkpoints with
        // increasing ids.
        Path db = dir.resolve("db");
        LaminarConfig config = LaminarConfig.builder()
                .storageDir(db)
                .checkpointIntervalMs(60_000)
                .build();
        try (LaminarConnection conn = LaminarDB.open(":memory:", config)) {
            conn.execute("CREATE SOURCE durable (a INT)");
            conn.execute("CREATE STREAM s AS SELECT a FROM durable");
            conn.start();
            long first = conn.checkpoint();
            long second = conn.checkpoint();
            assertThat(second).isGreaterThan(first);
        }
    }

    @Test
    void checkpointWithoutConfigThrowsDocumentedBehavior() {
        // Default in-memory config has checkpointing disabled: the pin refuses
        // checkpoints rather than silently no-oping (observed: query error).
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE t (a INT)");
            conn.start();
            assertThatThrownBy(conn::checkpoint)
                    .isInstanceOf(LaminarInternalException.class)
                    .satisfies(e -> assertThat(((LaminarException) e).getCode()).isEqualTo(900))
                    .satisfies(e -> assertThat(e.getMessage()).contains("not enabled"));
            assertThat(conn.isCheckpointEnabled()).isFalse();
        }
    }

    @Test
    void checkpointOnConfiguredPipelineReturnsAnId() {
        // Under target/ rather than @TempDir: the engine's checkpoint files
        // can outlive the test, and JUnit's temp-dir cleanup would fail on
        // the held paths.
        Path db = Path.of("target", "config-test-ckpt");
        LaminarConfig config = LaminarConfig.builder()
                .storageDir(db)
                .checkpointIntervalMs(60_000)
                .build();
        try (LaminarConnection conn = LaminarDB.open(":memory:", config);
                LaminarConfig owned = config) {
            conn.execute("CREATE SOURCE t (a INT, ts TIMESTAMP)");
            // Source-only pipelines run no checkpoint coordinator at the pin;
            // a derived stream gives the cut something to fence (verified
            // against core v0.30.0).
            conn.execute("CREATE STREAM s AS SELECT a FROM t");
            conn.start();
            assertThat(conn.isCheckpointEnabled()).isTrue();
            long id = conn.checkpoint();
            assertThat(id).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void builderMapsBufferAndEmitFlags() {
        try (LaminarConfig config = LaminarConfig.builder()
                .bufferSize(1024)
                .incrementalEmit(false)
                .checkpointIntervalMs(0)
                .build()) {
            try (LaminarConnection conn = LaminarDB.open(":memory:", config)) {
                assertThat(conn.isCheckpointEnabled()).isFalse();
            }
        }
    }

    @Test
    void catalogListsSourcesAndStreams() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE base (a INT)");
            conn.execute("CREATE STREAM derived AS SELECT a FROM base");
            assertThat(conn.listSources()).contains("base");
            assertThat(conn.listStreams()).contains("derived");
            assertThat(conn.listSinks()).isEmpty();
        }
    }
}
