package io.laminardb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Connection lifecycle and error mapping through the public API (plan 02 §6). */
class LaminarConnectionTest {

    @AfterAll
    static void allocatorDrained() {
        assertThat(LaminarDB.defaultAllocator().getAllocatedMemory()).isZero();
    }

    @Test
    void openExecuteCloseLifecycle() {
        try (LaminarConnection conn = LaminarDB.open()) {
            assertThat(conn.isClosed()).isFalse();
            try (ExecuteResult result =
                    conn.execute("CREATE SOURCE sensors (ts TIMESTAMP, device VARCHAR, value DOUBLE)")) {
                assertThat(result.kind()).isEqualTo(ExecuteResult.Kind.DDL);
                assertThat(result.ddlObject()).isEqualTo("sensors");
            }
        }
    }

    @Test
    void doubleCloseIsNoOp() {
        LaminarConnection conn = LaminarDB.open();
        conn.close();
        assertThatCode(conn::close).doesNotThrowAnyException();
        assertThat(conn.isClosed()).isTrue();
    }

    @Test
    void executeAfterCloseThrowsConnectionClosed() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.close();
            assertThatThrownBy(() -> conn.execute("CREATE SOURCE t (a INT)"))
                    .isInstanceOf(LaminarConnectionException.class)
                    .satisfies(e -> assertThat(((LaminarException) e).getCode()).isEqualTo(101));
        }
    }

    @Test
    void createSourceTwiceThrowsQueryFailedWithAlreadyExistsMessage() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE t (a INT)");
            // The core's SQL path maps duplicate CREATE SOURCE to QUERY_FAILED
            // (400), not TABLE_EXISTS — verified against the pinned v0.30.0.
            assertThatThrownBy(() -> conn.execute("CREATE SOURCE t (a INT)"))
                    .isInstanceOf(LaminarQueryException.class)
                    .satisfies(e -> {
                        assertThat(((LaminarException) e).getCode()).isEqualTo(400);
                        assertThat(e.getMessage()).contains("already exists");
                    });
        }
    }

    @Test
    void invalidSqlThrowsParseErrorWithMessage() {
        try (LaminarConnection conn = LaminarDB.open()) {
            assertThatThrownBy(() -> conn.execute("THIS IS NOT SQL"))
                    .isInstanceOf(LaminarQueryException.class)
                    .satisfies(e -> {
                        assertThat(((LaminarException) e).getCode()).isEqualTo(401);
                        assertThat(e.getMessage()).isNotEmpty();
                    });
        }
    }

    @Test
    void insertIntoMissingSourceThrowsTableNotFound() {
        try (LaminarConnection conn = LaminarDB.open()) {
            assertThatThrownBy(() -> conn.insert("nope", java.util.List.of(java.util.Map.of("a", 1))))
                    .isInstanceOf(LaminarException.class)
                    .satisfies(e -> assertThat(((LaminarException) e).getCode()).isEqualTo(200));
        }
    }

    @Test
    void openCloseLoopDoesNotCrash() {
        // Bounded soak: 500 open/close cycles with allocator accounting.
        for (int i = 0; i < 500; i++) {
            try (LaminarConnection conn = LaminarDB.open()) {
                assertThat(conn.isClosed()).isFalse();
            }
        }
    }

    @Test
    void operationAfterShutdownThrowsShutdownException() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE t (a INT)");
            conn.start();
            conn.shutdown();
            assertThatThrownBy(() -> conn.execute("CREATE SOURCE u (a INT)"))
                    .isInstanceOf(LaminarShutdownException.class)
                    .satisfies(e -> assertThat(((LaminarException) e).getCode()).isEqualTo(901));
        }
    }

    @Test
    void versionIsSemver() {
        assertThat(LaminarDB.getVersion()).matches("\\d+\\.\\d+\\.\\d+(-[a-z0-9]+)?");
    }

    @Test
    void concurrentExecuteSerializesWithoutDeadlock() {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE t (a INT)");
            // Bounded: 8 threads × 25 calls, all under a 30 s deadline.
            Thread[] threads = new Thread[8];
            java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
            for (int t = 0; t < threads.length; t++) {
                final int id = t;
                threads[t] = new Thread(() -> {
                    try {
                        for (int i = 0; i < 25; i++) {
                            conn.execute("CREATE SOURCE t" + id + "_" + i + " (a INT)");
                        }
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    }
                });
            }
            for (Thread thread : threads) {
                thread.start();
            }
            try {
                for (Thread thread : threads) {
                    thread.join(30_000);
                    assertThat(thread.isAlive()).isFalse();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
            assertThat(failures.get()).isZero();
        }
    }
}
