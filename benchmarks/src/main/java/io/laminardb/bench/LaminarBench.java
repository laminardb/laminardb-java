package io.laminardb.bench;

import io.laminardb.ArrowBatch;
import io.laminardb.LaminarConnection;
import io.laminardb.LaminarDB;
import io.laminardb.QueryResult;
import io.laminardb.SubscriptionListener;
import io.laminardb.Writer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * The plan 03 §5 suite: insert throughput, query roundtrip, poll- and
 * callback-subscription latency, map conversion cost, open/close cycles.
 * Run via `just bench`; results are appended to docs/benchmarks.md with the
 * environment noted.
 */
@State(Scope.Benchmark)
public class LaminarBench {

    private LaminarConnection conn;

    private List<Map<String, ?>> rows(int n) {
        java.util.ArrayList<Map<String, ?>> list = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(Map.of("id", (long) i, "label", "row-" + i));
        }
        return list;
    }

    @Setup(Level.Trial)
    public void open() {
        conn = LaminarDB.open();
        conn.execute("CREATE SOURCE bench (id BIGINT, label VARCHAR)");
        conn.execute("CREATE SOURCE bench_source (id BIGINT, label VARCHAR)");
        conn.execute("CREATE STREAM bench_stream AS SELECT id, label FROM bench_source");
        conn.start();
    }

    @TearDown(Level.Trial)
    public void close() {
        conn.close();
    }

    private long idBase;

    @Benchmark
    public long mapInsertThroughput() {
        // Unique ids across invocations: the bench table has a primary key.
        long base = idBase;
        idBase += 10_000;
        java.util.ArrayList<Map<String, ?>> list = new java.util.ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            list.add(Map.of("id", base + i, "label", "row-" + i));
        }
        return conn.insert("bench", list);
    }

    @Benchmark
    public long queryRoundtrip() throws Exception {
        try (QueryResult result = conn.query("SELECT count(*) AS n FROM bench")) {
            return result.numRows();
        }
    }

    @Benchmark
    public int pollSubscriptionLatency() throws Exception {
        // Per-batch poll cost over a bounded query stream.
        try (var stream = conn.stream("SELECT * FROM (VALUES (1)) AS t(a)")) {
            int seen = 0;
            ArrowBatch batch;
            while ((batch = stream.next()) != null) {
                seen += batch.getRowCount();
                batch.close();
            }
            return seen;
        }
    }

    @Benchmark
    public long callbackDeliveryOverhead() throws Exception {
        // One subscription per invocation: measure deliveries, then cancel.
        AtomicLong delivered = new AtomicLong();
        try (var sub = conn.subscribeStream(
                "bench_stream",
                new SubscriptionListener() {
                    public void onBatch(ArrowBatch batch) {
                        delivered.addAndGet(batch.getRowCount());
                        batch.close();
                    }

                    public void onError(io.laminardb.LaminarException error) {
                        // benchmark path
                    }

                    public void onClose() {
                        // benchmark path
                    }
                });
                Writer writer = conn.writer("bench_source")) {
            writer.write(rows(1_000));
            writer.close();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (delivered.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            sub.cancel();
            return delivered.get();
        }
    }

    private long tmpSeq;

    @Benchmark
    public long openCloseCycle() {
        try (LaminarConnection fresh = LaminarDB.open()) {
            fresh.execute("CREATE TABLE tmp" + (tmpSeq++) + " (a INT PRIMARY KEY)");
            return 1;
        }
    }
}
