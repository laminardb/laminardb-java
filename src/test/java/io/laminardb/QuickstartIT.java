package io.laminardb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Documentation-as-test (plan 02 §6): executes the README quickstart and the
 * stateful-join walkthrough verbatim, keeping the docs honest — a broken
 * example in docs is a broken test in CI.
 */
class QuickstartIT {

    @AfterAll
    static void allocatorDrained() {
        assertThat(LaminarDB.defaultAllocator().getAllocatedMemory()).isZero();
    }

    @Test
    void readmeQuickstartRunsVerbatim() {
        // BEGIN README QUICKSTART (keep in sync with README.md)
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE TABLE sensors (id BIGINT PRIMARY KEY, reading DOUBLE)");
            conn.execute("INSERT INTO sensors VALUES (1, 20.5), (2, 21.0)");
            try (QueryResult result = conn.query("SELECT * FROM sensors")) {
                assertThat(result.toMaps()).hasSize(2);
            }
        }
        // END README QUICKSTART
    }

    @Test
    void statefulJoinWalkthroughPlansStartsAndListsStreams() {
        // BEGIN docs/stateful-and-joins.md walkthrough (Phase 1 scope: the
        // pipeline compiles and runs; output subscription lands in Phase 2).
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute(
                    """
                CREATE SOURCE trades (
                    trade_id BIGINT NOT NULL, symbol VARCHAR NOT NULL, price DOUBLE NOT NULL,
                    ts TIMESTAMP NOT NULL,
                    WATERMARK FOR ts AS ts - INTERVAL '5' SECOND)""");
            conn.execute(
                    """
                CREATE SOURCE orders (
                    order_id VARCHAR NOT NULL, symbol VARCHAR NOT NULL,
                    side VARCHAR NOT NULL, price DOUBLE NOT NULL, ts TIMESTAMP NOT NULL,
                    WATERMARK FOR ts AS ts - INTERVAL '5' SECOND)""");
            conn.execute(
                    """
                CREATE STREAM matched AS
                SELECT t.trade_id AS trade_id, o.order_id AS order_id,
                       t.price - o.price AS price_diff
                FROM trades t
                INNER JOIN orders o
                ON t.symbol = o.symbol
                AND o.ts BETWEEN t.ts AND t.ts + INTERVAL '10' SECOND""");
            conn.execute(
                    """
                CREATE STREAM totals AS
                SELECT symbol, count(*) AS trade_count
                FROM trades
                GROUP BY symbol, tumble(ts, INTERVAL '10' SECOND)""");
            conn.start();
            try (Writer trades = conn.writer("trades");
                    Writer orders = conn.writer("orders")) {
                trades.write(List.of(Map.of(
                        "trade_id",
                        1L,
                        "symbol",
                        "AAPL",
                        "price",
                        201.5,
                        "ts",
                        java.time.Instant.parse("2026-08-29T10:00:02Z"))));
                orders.write(List.of(Map.of(
                        "order_id",
                        "O-1",
                        "symbol",
                        "AAPL",
                        "side",
                        "BUY",
                        "price",
                        200.0,
                        "ts",
                        java.time.Instant.parse("2026-08-29T10:00:01Z"))));
                trades.watermark(java.time.Instant.parse("2026-08-29T10:00:07Z").toEpochMilli());
                orders.watermark(java.time.Instant.parse("2026-08-29T10:00:07Z").toEpochMilli());
            }
            assertThat(conn.listStreams()).contains("matched", "totals");
        }
        // END walkthrough
    }
}
