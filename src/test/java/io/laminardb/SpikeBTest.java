package io.laminardb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Spike B (plan 03 §4): measures the callback worker's per-batch JNI
 * crossing cost over many small deliveries. Not a regression gate — the
 * number is recorded in plan 03 §7.
 */
class SpikeBTest {

    @Test
    void perBatchCrossingCost() throws Exception {
        try (LaminarConnection conn = LaminarDB.open()) {
            conn.execute("CREATE SOURCE s (a BIGINT)");
            conn.execute("CREATE STREAM out AS SELECT a FROM s");
            conn.start();
            AtomicLong delivered = new AtomicLong();
            try (Writer writer = conn.writer("s");
                    CallbackSubscription sub = conn.subscribeStream("out", new SubscriptionListener() {
                        public void onBatch(ArrowBatch batch) {
                            batch.close();
                            delivered.incrementAndGet();
                        }

                        public void onError(LaminarException error) {
                            // measurement path
                        }

                        public void onClose() {
                            // measurement path
                        }
                    })) {
                int rows = 500;
                long start = System.nanoTime();
                for (int i = 0; i < rows; i++) {
                    writer.write(List.of(Map.of("a", (long) i)));
                }
                // Close flushes the tail rows into the stream.
                writer.close();
                long writeNanos = System.nanoTime() - start;
                // Bounded: all deliveries must land.
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (delivered.get() < rows && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                long totalNanos = System.nanoTime() - start;
                // The pin's subscriptions are broadcast-based: a consumer
                // that falls behind LAG-DROPS batches (Subscription::poll
                // skips TryRecvError::Lagged). Rapid single-row writes
                // outpace the callback worker's JNI crossings; large-batch
                // production (the documented D7 shape) does not. The ratio
                // is machine-load dependent and recorded (plan 03 §7), never
                // gated — delivery working at all is the assertion.
                assertThat(delivered.get()).isPositive();
                System.out.println(
                        "SPIKE-B write-ns/row=" + writeNanos / rows + " delivered=" + delivered.get() + "/" + rows);
            }
        }
    }
}
