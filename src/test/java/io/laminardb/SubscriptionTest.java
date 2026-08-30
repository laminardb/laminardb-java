package io.laminardb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Named-stream and callback subscriptions (plan 03 §1, §2, §4). */
class SubscriptionTest {

    @AfterAll
    static void allocatorDrained() {
        assertThat(LaminarDB.defaultAllocator().getAllocatedMemory()).isZero();
    }

    private static final String PIPELINE_DDL =
            """
        CREATE SOURCE trades (trade_id BIGINT, symbol VARCHAR);
        CREATE STREAM matched AS SELECT trade_id, symbol FROM trades""";

    @Test
    void framedSubscriptionDeliversDerivedStreamOutput() throws Exception {
        try (LaminarConnection conn = LaminarDB.open()) {
            for (String ddl : PIPELINE_DDL.split(";\\s*\\n")) {
                if (!ddl.isBlank()) {
                    conn.execute(ddl.strip());
                }
            }
            conn.start();
            try (StreamSubscription sub = conn.subscribe("matched")) {
                assertThat(sub.schema().getFields()).hasSize(2);
                try (Writer trades = conn.writer("trades")) {
                    trades.write(List.of(Map.of("trade_id", 7L, "symbol", "AAPL")));
                }
                // Bounded: a data frame must arrive within the deadline.
                long deadline = System.nanoTime() + 20_000_000_000L;
                Frame frame = null;
                while (System.nanoTime() < deadline) {
                    frame = sub.nextFrame(Duration.ofSeconds(1));
                    if (frame instanceof Frame.Data) {
                        break;
                    }
                }
                assertThat(frame).isInstanceOf(Frame.Data.class);
                try (ArrowBatch batch = ((Frame.Data) frame).batch()) {
                    assertThat(batch.toMaps().toString()).contains("AAPL");
                    assertThat(batch.toMaps().toString()).contains("7");
                }
                sub.cancel();
                assertThat(sub.isActive()).isFalse();
            }
        }
    }

    @Test
    void tryNextFrameIsNullWhenIdleAndTimeoutBounded() throws Exception {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE s (a BIGINT)");
            conn.execute("CREATE STREAM out AS SELECT a FROM s");
            conn.start();
            try (StreamSubscription sub = conn.subscribe("out")) {
                assertThat(sub.tryNextFrame()).isNull();
                long start = System.nanoTime();
                Frame frame = sub.nextFrame(Duration.ofMillis(200));
                long elapsed = System.nanoTime() - start;
                assertThat(frame).isNull();
                assertThat(elapsed).isGreaterThan(Duration.ofMillis(100).toNanos());
            }
        }
    }

    @Test
    void callbackOverQueryDeliversBatchesAndStops() throws Exception {
        try (LaminarConnection conn = LaminarDB.open()) {
            CountDownLatch closed = new CountDownLatch(1);
            ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<LaminarException> errors = new ConcurrentLinkedQueue<>();
            try (CallbackSubscription sub =
                    conn.subscribe("SELECT * FROM (VALUES (1), (2), (3)) AS t(a)", new SubscriptionListener() {
                        @Override
                        public void onBatch(ArrowBatch batch) {
                            try (batch) {
                                seen.add(String.valueOf(batch.toMaps().size()));
                            }
                        }

                        @Override
                        public void onError(LaminarException error) {
                            errors.add(error);
                        }

                        @Override
                        public void onClose() {
                            closed.countDown();
                        }
                    })) {
                // Bounded: batches arrive promptly.
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (seen.isEmpty() && System.nanoTime() < deadline) {
                    TimeUnit.MILLISECONDS.sleep(20);
                }
                assertThat(seen).isNotEmpty();
                assertThat(errors).isEmpty();
                sub.cancel();
                assertThat(sub.awaitStopped(Duration.ofSeconds(5))).isTrue();
            }
        }
    }

    @Test
    void callbackListenerThrowDeliversOnErrorOnceAndStops() throws Exception {
        try (LaminarConnection conn = LaminarDB.open()) {
            CountDownLatch closed = new CountDownLatch(1);
            ConcurrentLinkedQueue<LaminarException> errors = new ConcurrentLinkedQueue<>();
            try (CallbackSubscription sub =
                    conn.subscribe("SELECT * FROM (VALUES (1)) AS t(a)", new SubscriptionListener() {
                        @Override
                        public void onBatch(ArrowBatch batch) {
                            batch.close();
                            throw new IllegalStateException("listener boom");
                        }

                        @Override
                        public void onError(LaminarException error) {
                            errors.add(error);
                        }

                        @Override
                        public void onClose() {
                            closed.countDown();
                        }
                    })) {
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (errors.isEmpty() && System.nanoTime() < deadline) {
                    TimeUnit.MILLISECONDS.sleep(20);
                }
                assertThat(errors).hasSize(1);
                assertThat(errors.peek().getMessage()).contains("listener boom");
                long dumpPid = Long.parseLong(System.getProperty("laminardb.test.pid", "0"));
                if (dumpPid == 0) {
                    dumpPid = ProcessHandle.current().pid();
                }
                try {
                    new ProcessBuilder("sample", String.valueOf(dumpPid), "3", "-file", "/tmp/sample.txt")
                            .start()
                            .waitFor();
                } catch (Exception e) {
                    // sample unavailable; fall through
                }
                assertThat(sub.awaitStopped(Duration.ofSeconds(5))).isTrue();
                assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void callbackOverNamedStreamDeliversWrites() throws Exception {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE s (a BIGINT)");
            conn.execute("CREATE STREAM out AS SELECT a FROM s");
            conn.start();
            ConcurrentLinkedQueue<Long> rows = new ConcurrentLinkedQueue<>();
            CountDownLatch gotData = new CountDownLatch(1);
            try (CallbackSubscription sub = conn.subscribeStream("out", new SubscriptionListener() {
                @Override
                public void onBatch(ArrowBatch batch) {
                    try (batch) {
                        batch.toMaps().forEach(m -> rows.add((Long) m.get("a")));
                        gotData.countDown();
                    }
                }

                @Override
                public void onError(LaminarException error) {
                    // no failure expected in this test
                }

                @Override
                public void onClose() {
                    // cancel-driven close
                }
            })) {
                try (Writer writer = conn.writer("s")) {
                    writer.write(List.of(Map.of("a", 42L)));
                    writer.watermark(1_800_000_000_000L);
                }
                // Bounded wait for delivery.
                assertThat(gotData.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(rows).contains(42L);
                sub.cancel();
                assertThat(sub.awaitStopped(Duration.ofSeconds(5))).isTrue();
            }
        }
    }
}
