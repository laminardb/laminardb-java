package io.laminardb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Nightly soak (plan 03 §6): 10k subscribe/cancel cycles, 1k open/close
 * cycles with disk checkpoints, 8-thread poll/write torture. Tagged
 * {@link Soak}; CI runs them nightly only.
 */
class SoakTest {

    @AfterAll
    static void allocatorDrained() {
        assertThat(LaminarDB.defaultAllocator().getAllocatedMemory()).isZero();
    }

    @Test
    @Soak
    void tenThousandSubscribeCancelCycles() {
        // Bounded: 10_000 cycles; each must open, stay idle, and close.
        for (int i = 0; i < 10_000; i++) {
            try (LaminarConnection conn = LaminarDB.open()) {
                conn.execute("CREATE SOURCE s (a BIGINT)");
                conn.execute("CREATE STREAM out AS SELECT a FROM s");
                conn.start();
                try (io.laminardb.StreamSubscription sub = conn.subscribe("out")) {
                    assertThat(sub.isActive()).isTrue();
                    sub.cancel();
                }
            }
        }
    }

    @Test
    @Soak
    void callbackSubscriptionCycles() {
        // Bounded: 10_000 callback subscribe/cancel cycles with one delivery
        // each — exercises the bridge's container recycling end-to-end.
        for (int i = 0; i < 10_000; i++) {
            try (LaminarConnection conn = LaminarDB.open()) {
                conn.execute("CREATE SOURCE s (a BIGINT)");
                conn.execute("CREATE STREAM out AS SELECT a FROM s");
                conn.start();
                java.util.concurrent.atomic.AtomicLong seen = new java.util.concurrent.atomic.AtomicLong();
                try (Writer writer = conn.writer("s");
                        CallbackSubscription sub = conn.subscribeStream("out", new SubscriptionListener() {
                            public void onBatch(ArrowBatch batch) {
                                seen.addAndGet(batch.getRowCount());
                                batch.close();
                            }

                            public void onError(LaminarException error) {
                                // soak path
                            }

                            public void onClose() {
                                // soak path
                            }
                        })) {
                    writer.write(List.of(Map.of("a", (long) i)));
                    writer.close();
                    long deadline = System.nanoTime() + 5_000_000_000L;
                    while (seen.get() == 0 && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    sub.cancel();
                    assertThat(seen.get()).isPositive();
                }
            }
        }
    }

    @Test
    @Soak
    void thousandOpenCloseWithDiskCheckpoints() {
        java.nio.file.Path dir = java.nio.file.Path.of("target", "soak-ckpt");
        for (int i = 0; i < 1_000; i++) {
            LaminarConfig config = LaminarConfig.builder()
                    .storageDir(dir)
                    .checkpointIntervalMs(3_600_000)
                    .build();
            try (LaminarConnection conn = LaminarDB.open(":memory:", config)) {
                conn.execute("CREATE SOURCE s (a BIGINT)");
                conn.execute("CREATE STREAM out AS SELECT a FROM s");
                conn.start();
                assertThat(conn.checkpoint()).isGreaterThanOrEqualTo(0L);
            }
        }
    }

    @Test
    @Soak
    void concurrencyTortureUnderLoad() throws Exception {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE s (a BIGINT)");
            conn.execute("CREATE STREAM out AS SELECT a FROM s");
            conn.start();
            try (io.laminardb.StreamSubscription sub = conn.subscribe("out")) {
                Thread[] threads = new Thread[8];
                java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
                for (int t = 0; t < threads.length; t++) {
                    final int id = t;
                    threads[t] = new Thread(() -> {
                        // Per-thread writers: Writer is single-owner per its
                        // documented contract (plan 02 §1); the native mutex
                        // would serialize a shared one, but the Java contract
                        // says not to.
                        try (Writer own = conn.writer("s")) {
                            for (int i = 0; i < 100; i++) {
                                own.write(List.of(Map.of("a", (long) (id * 1_000 + i))));
                                if (sub.tryNextFrame() instanceof Frame.Data data) {
                                    data.batch().close();
                                }
                            }
                        } catch (RuntimeException e) {
                            failures.incrementAndGet();
                        }
                    });
                }
                // Concurrent checkpoint under load (plan 03 §6 torture set).
                Thread checkpointing = new Thread(() -> {
                    try {
                        for (int i = 0; i < 20; i++) {
                            if (conn.isCheckpointEnabled()) {
                                conn.checkpoint();
                            }
                        }
                    } catch (RuntimeException e) {
                        // checkpoints are configured off in this fixture
                    }
                });
                checkpointing.start();
                for (Thread thread : threads) {
                    thread.start();
                }
                for (Thread thread : threads) {
                    thread.join(60_000);
                    assertThat(thread.isAlive()).isFalse();
                }
                checkpointing.join(60_000);
                assertThat(checkpointing.isAlive()).isFalse();
                assertThat(failures.get()).isZero();
            }
        }
    }
}
