package io.laminardb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Phase 0 smoke tests through the public API (plan 01 Task 0.7). */
class LaminarConnectionTest {

    @Test
    void openExecuteCloseLifecycle() {
        try (LaminarConnection conn = LaminarDB.open()) {
            assertThat(conn.isClosed()).isFalse();
            conn.execute("CREATE SOURCE sensors (ts TIMESTAMP, device VARCHAR, value DOUBLE)");
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
                    .isInstanceOf(LaminarException.class)
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
                    .isInstanceOf(LaminarException.class)
                    .satisfies(e -> {
                        assertThat(((LaminarException) e).getCode()).isEqualTo(401);
                        assertThat(e.getMessage()).isNotEmpty();
                    });
        }
    }

    @Test
    void openCloseLoopDoesNotCrash() {
        // Bounded soak: 200 open/close cycles; leak accounting lands in Phase 2.
        for (int i = 0; i < 200; i++) {
            try (LaminarConnection conn = LaminarDB.open()) {
                assertThat(conn.isClosed()).isFalse();
            }
        }
    }

    @Test
    void versionTracksThePinnedCore() {
        assertThat(LaminarDB.getVersion()).matches("\\d+\\.\\d+\\.\\d+");
    }
}
