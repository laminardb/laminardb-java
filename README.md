# laminardb-java

Embedded streaming SQL for the JVM — Java bindings for
[LaminarDB](https://github.com/laminardb/laminardb), a streaming database
written in Rust. One dependency, one line to open, Arrow-native data flow.

```xml
<dependency>
  <groupId>io.laminardb</groupId>
  <artifactId>laminardb</artifactId>
  <version>0.30.0-alpha</version>
</dependency>
```

```java
try (LaminarConnection conn = LaminarDB.open()) {
    conn.execute("CREATE TABLE sensors (id BIGINT PRIMARY KEY, reading DOUBLE)");
    conn.execute("INSERT INTO sensors VALUES (1, 20.5), (2, 21.0)");
    try (QueryResult result = conn.query("SELECT * FROM sensors")) {
        result.toMaps(); // [{id=1, reading=20.5}, {id=2, reading=21.0}]
    }
}
```

## What you get

- **Streaming SQL, in-process.** Define sources, derived streams, and
  windowed aggregations in SQL; feed them from Java; advance event time with
  watermarks; read results back as Arrow batches — all without a server.
- **Arrow as the data plane.** Zero-copy batch exchange via the Arrow C Data
  Interface (`VectorSchemaRoot` in, `ArrowBatch` out), with friendly
  `List<Map<String,?>>` conversions when you don't want to touch Arrow.
- **Three ways to consume a stream.** Framed polling (`nextFrame()` with data
  batches and checkpoint barriers), push delivery to a listener on a
  background thread, and bounded-lazy `Stream<ArrowBatch>` adapters.
- **Typed errors.** Every failure arrives as a `LaminarException` subclass
  carrying the engine's numeric code — parse errors, closed connections,
  watermark lag, and so on are distinguishable in a `catch`.
- **Bundled natives.** The jar ships `linux-amd64`, `linux-aarch64`,
  `macos-amd64`, and `macos-aarch64` libraries and picks the right one at
  load time.

## A streaming example

```java
try (LaminarConnection conn = LaminarDB.open()) {
    conn.execute("CREATE SOURCE events (kind VARCHAR, value DOUBLE)");
    conn.execute("CREATE STREAM alerts AS SELECT kind, value FROM events WHERE value > 100");
    conn.start();

    try (Writer writer = conn.writer("events");
         StreamSubscription sub = conn.subscribe("alerts")) {
        writer.write(List.of(
            Map.of("kind", "temp", "value", 42.0),
            Map.of("kind", "temp", "value", 120.0)));   // passes the filter

        Frame frame = sub.nextFrame(Duration.ofSeconds(10));
        if (frame instanceof Frame.Data data) {
            data.batch().toMaps(); // [{kind=temp, value=120.0}]
            data.batch().close();
        }
    }
}
```

Sources can also declare event-time watermarks
(`WATERMARK FOR ts AS ts - INTERVAL '5' SECOND`) and writers advance them
(`writer.watermark(...)`) — windowed joins and aggregations are planned by
the engine at this version, though observing their output through embedded
subscriptions is [still limited](docs/stateful-and-joins.md#a-pin-time-observation-about-watermarkedjoin-emission-v0300).

Push delivery instead of polling:

```java
conn.subscribeStream("counts", new SubscriptionListener() {
    public void onBatch(ArrowBatch batch) { batch.close(); /* consume */ }
    public void onError(LaminarException e) { /* code + message */ }
    public void onClose() { }
});
```

More: [streaming and joins walkthrough](docs/stateful-and-joins.md) ·
[error codes](docs/errors.md) · [threading model](docs/threading.md).

## Requirements

| | |
|---|---|
| Java | 17 or 21 (25 is blocked on an arrow-java upgrade — see [docs/build.md](docs/build.md)) |
| OS | Linux (amd64, aarch64), macOS (amd64, aarch64) |
| JVM flag | `--add-opens java.base/java.nio=ALL-UNNAMED` (arrow-java requirement) |

Status is **alpha** (APIs may change). The engine underneath is the pinned
core [`v0.30.0`](https://github.com/laminardb/laminardb); the binding
version always tracks the core version.

## Building from source

You need Rust stable (rustfmt + clippy), JDK 17+, Maven, and
[`just`](https://github.com/casey/just).

```
just verify   # format + lints + Rust tests + full JUnit suite
just review   # the above plus SpotBugs, JaCoCo coverage floor, Checkstyle
just bench    # JMH benchmark suite
```

Details in [docs/build.md](docs/build.md).

## Project layout

- `src/*.rs` — the JNI native library (Rust cdylib over the core's `api`)
- `src/main/java/io/laminardb` — the public Java API
- `src/main/java/io/laminardb/internal` — the native seam (not public API)
- `docs/` — user guides, plus the implementation plan series and phase
  review records under `docs/plans/` and `docs/reviews/`
- `benchmarks/` — JMH suite

## Contributing

The repository is developed against the plan series in
[docs/plans/](docs/plans/) — each phase shipped with an adversarial review
record in [docs/reviews/](docs/reviews/). PRs must pass `just verify` and
`just review` (CI enforces both). The reviewer prompt lives in
[agents/code-review.md](agents/code-review.md).

## License

Apache-2.0, matching the [core repository](https://github.com/laminardb/laminardb).
