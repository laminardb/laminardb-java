# Plan 02 — Phase 1: embedded MVP

Status: **Implemented (2026-08-29, code-complete; Maven Central publish blocked on maintainer credentials — see acceptance checklist)** · Prerequisite: Phase 0 exit (plan 01)
Exit: `io.laminardb:laminardb:<version>-alpha` on Maven Central with bundled natives for
Linux x86_64/aarch64 + macOS aarch64/x86_64; the README quickstart runs from a bare
Maven project with that single dependency.

## Goal

The full embedded surface minus subscriptions: open (default + configured), execute,
query (materialized + streaming), insert (friendly + Arrow zero-copy), Writer,
start/checkpoint/shutdown, schema/catalog helpers, complete exception hierarchy, and
the dev-friendly Java layer. Publishing mechanics themselves are specified in plan 04;
this plan builds everything they package.

## 1 — Public API specification

All classes in `io.laminardb` unless noted. Javadoc on every public member (required by
repo convention); every blocking method documents that it blocks.

### `LaminarDB` (entry point)

```java
public final class LaminarDB {
    public static LaminarConnection open();                                  // ":memory:", defaults
    public static LaminarConnection open(String path);                       // ":memory:" or storage dir (documented: durability via LaminarConfig.storage_dir; path is API-compat sugar like the Python binding)
    public static LaminarConnection open(String path, LaminarConfig config);
    public static String getVersion();                                       // native version string
    public static BufferAllocator defaultAllocator();                        // see §4 — shared RootAllocator, lifecycle documented
}
```

### `LaminarConnection implements AutoCloseable`

Thread-safe (internal mutex serializes core calls — same contract as the Python
binding). Methods: `execute(String sql) → ExecuteResult`, `query(String sql) →
QueryResult`, `stream(String sql) → QueryStream`, `insert(String source, …) → long`,
`writer(String source) → Writer`, `start()`, `checkpoint() → long`,
`isCheckpointEnabled()`, `shutdown()`, `close()` (idempotent), `isClosed()`,
`schema(String name) → Schema`, `listSources/listStreams/listSinks() → List<String>`.
Catalog/metrics accessors (`SourceInfo` etc.) are stretch tasks — implement only the
three list methods in Phase 1; the rest follow the same recipe in Phase 2.

### `LaminarConfig` — builder mapped to the native struct

```java
LaminarConfig.builder()
    .bufferSize(1024)                 // default_buffer_size
    .storageDir(Path.of("ckpt"))      // storage_dir; null = in-memory only
    .checkpointIntervalMs(1000)        // convenience → StreamCheckpointConfig (verify shape at pin)
    .incrementalEmit(true)
    .objectStoreUrl("s3://bucket/pfx")   // + objectStoreOptions(Map)
    .build()
```

Phase 1 maps exactly these; the `pipeline_*` tuning knobs are added on demand (each one
is a trivial setter→native-setter pair — resist adding unused surface). The builder
constructs a **native config handle** (`Native.configNew()` … `configDrop()`) rather
than mirroring the struct in Java, so the Java side can never drift from the native
field set. `open(path, config)` folds `path` into `storageDir` when non-`:memory:`.

### `ExecuteResult`, `QueryResult`, `QueryStream`

```java
public final class ExecuteResult {           // discriminated union, exhaustive switch
    public enum Kind { DDL, ROWS_AFFECTED, METADATA, QUERY }   // verify variants at pin
    public Kind kind(); public String ddlObject(); public long rowsAffected();
}

public final class QueryResult implements AutoCloseable, Iterable<List<Object>> {
    public Schema schema();
    public int numRows(); public int numBatches();
    public ArrowBatch batch(int i);                  // lazy import per access (see §4)
    public Stream<ArrowBatch> batches();             // closes nothing; caller closes QueryResult
    public List<Map<String,Object>> toMaps();        // convenience copy
    public void close();                             // frees native QueryResult; idempotent
}

public final class QueryStream implements AutoCloseable {
    public Schema schema();
    public ArrowBatch next();                        // blocks; null = end
    public ArrowBatch tryNext();                     // null = no data *right now*; exception on error; distinguish EOF via is-active
    public boolean isActive(); public void cancel(); public void close();
}
```

### `Writer implements AutoCloseable` — single-owner, not thread-safe

`write(ArrowBatch)`, `write(List<Map<String,?>> rows)` (converts per §3),
`watermark(long tsMillis)`, `currentWatermark()`, `schema()`, `flush()`, `close()`
(flush + native close, idempotent; after close, `write` throws code 301
`WRITER_CLOSED`).

### `Schema`

Minimal value type: `List<FieldInfo>` with `name()` and `typeName()` (canonical Arrow
type string — **not** the Rust `Debug` string the C FFI leaks; normalize on the Rust
side of the JNI boundary).

### Exceptions — see §5.

## 2 — JNI native surface (contract for `src/*.rs`)

All methods on `io.laminardb.internal.Native`, package-private. Handles are `long`.
Failure ⇒ a `LaminarException` subclass is thrown (never a raw `RuntimeException` from
the JVM's JNI machinery — every fallible path routes through `error.rs`). Where noted,
Arrow addresses are the `memoryAddress()` of Java-allocated `ArrowArray`/`ArrowSchema`
per Spike A mechanics (plan 01 §0.5).

```java
// lifecycle
static native long   openDefault();
static native long   openWithConfig(long configHandle);          // consumes? no — config stays owned by LaminarConfig until closed
static native void   close(long conn);                            // idempotent, consumes the handle exactly once
static native boolean isClosed(long conn);
static native void   start(long conn);
static native long   checkpoint(long conn);                       // → checkpoint id
static native boolean isCheckpointEnabled(long conn);
static native void   shutdown(long conn);
static native String version();

// config builder (native struct ownership: configNew → setters → openWithConfig → configDrop)
static native long   configNew();
static native void   configSetBufferSize(long c, long v);
static native void   configSetStorageDir(long c, String path);   // null clears
static native void   configSetCheckpointIntervalMs(long c, long ms);  // 0 disables
static native void   configSetIncrementalEmit(long c, boolean v);
static native void   configSetObjectStoreUrl(long c, String url);
static native void   configSetObjectStoreOption(long c, String k, String v);
static native void   configDrop(long c);                          // idempotent

// sql
static native long   execute(long conn, String sql);              // → ExecuteResult handle
static native int    executeKind(long exec);                      // Kind ordinal
static native String executeDdlObject(long exec);
static native long   executeRowsAffected(long exec);
static native void   executeFree(long exec);
static native long   query(long conn, String sql);                // → QueryResult handle
static native long   queryStream(long conn, String sql);          // → QueryStream handle

// QueryResult / QueryStream → Arrow (Java-allocated out-structs)
static native void   resultSchemaExport(long result, long schemaAddr);
static native int    resultNumBatches(long result);
static native void   resultExportBatch(long result, int index, long arrayAddr, long schemaAddr);
static native void   resultFree(long result);
static native int    streamNext(long stream, long arrayAddr, long schemaAddr);   // 1 data, 0 EOF
static native int    streamTryNext(long stream, long arrayAddr, long schemaAddr); // 1 data, 0 empty, -1 EOF — or split into two calls if cleaner
static native boolean streamIsActive(long stream);
static native void   streamCancel(long stream);
static native void   streamFree(long stream);

// ingestion (Java exports into Java-allocated structs; Rust imports — ownership moves to Rust)
static native long   insert(long conn, String source, long arrayAddr, long schemaAddr);  // → rows
static native long   writerCreate(long conn, String source);
static native void   writerWrite(long writer, long arrayAddr, long schemaAddr);
static native void   writerFlush(long writer);
static native void   writerWatermark(long writer, long ts);
static native long   writerCurrentWatermark(long writer);
static native void   writerSchemaExport(long writer, long schemaAddr);
static native void   writerClose(long writer);                    // consumes; idempotent via Option-take
static native void   writerFree(long writer);                     // safety net for unclosed writers (leak backstop only)

// catalog
static native String[] listSources(long conn);
static native String[] listStreams(long conn);
static native String[] listSinks(long conn);
static native void    connSchemaExport(long conn, String name, long schemaAddr);  // throws 200 TABLE_NOT_FOUND
```

Rust-side module layout mirrors this: `connection.rs`, `config.rs`, `query.rs`,
`writer.rs`, `catalog.rs`, `arrow_jni.rs` (shared export/import helpers used by all),
`error.rs`, `runtime.rs`, `handle.rs`. Keep every module under the size discipline
(plan 00 §5).

Ownership invariants (INVARIANT — encode as doc comments + tests):

- Connection/writer/stream/result handles are `Box::into_raw` peers freed exactly once;
  Java wrappers null their `long` under a lock on close so double-free is unreachable
  from public API.
- `writerClose`/`resultFree`/… take `Option::take` out of the box so a second call is a
  no-op, not a double-drop.
- Arrow structs follow the C Data Interface ownership contract from Spike A: exported
  buffers are reference-counted by arrow-rs (Java may keep its imported copy alive
  after the native handle is freed); imported structs are consumed (Java-side export
  structs are dead after the call and must not be reused).

## 3 — Friendly insert conversions (Java layer)

`insert(String source, X)` accepts, in this documented priority (mirroring
`laminardb-python/src/conversion.rs` ordering):

1. `ArrowBatch` (zero-copy — the only path with no conversion cost).
2. `org.apache.arrow.vector.VectorSchemaRoot` (zero-copy export).
3. `List<Map<String,?>>` — row-wise convenience; columnarized in Java against
   `schema(source)` (or the first row's key union, with a schema-mismatch error 302 on
   disagreement), then built as an Arrow batch and exported.
4. `String json` via `insertJson(source, json)` — parse with a test-scope-only parser?
   No: JSON support needs a compile-scope parser. Decision: **defer JSON/CSV insert to
   Phase 2** unless a zero-dependency parser under ~200 lines suffices (Jackson is not
   acceptable as a forced transitive dep; revisit in Phase 2 with a `laminardb-json`
   optional module).

Type coercion rules for maps: Java `Integer/Long → Int32/Int64`, `Double → Float64`,
`String → Utf8`, `java.time.Instant/long-epoch-ms → Timestamp(ms)`, nulls honored;
anything else → `LaminarIngestionException` code 302 with the offending field name.

## 4 — `ArrowBatch` and the allocator story

```java
public final class ArrowBatch implements AutoCloseable {
    public VectorSchemaRoot root();        // lazy import on first access, cached
    public Schema schema();
    public void close();                   // closes the imported root; idempotent
}
```

- An `ArrowBatch` wraps the **addresses exported by Rust** and imports them into a
  `VectorSchemaRoot` on first `root()` access (import cost paid only when the user
  reads data — matching the Python binding's lazy pattern).
- Allocator: `LaminarDB.defaultAllocator()` returns a process-wide `RootAllocator`
  (documented: closed via `LaminarDB.shutdownDefaultAllocator()`; most apps never call
  it). Overload `LaminarDB.open(path, config, allocator)` for apps that own allocation.
- Javadoc must state the ownership rule plainly: closing the native-side
  `QueryResult`/`Writer` never invalidates already-imported `ArrowBatch`es (refcounted
  export), but skipping `ArrowBatch.close()` leaks allocator memory until the allocator
  closes — use try-with-resources.

## 5 — Exception hierarchy (exact code mapping)

Generate the table below into Javadoc and into `error.rs` from the pinned core's
`api::codes` (single source: a `#[cfg(test)]` Rust test asserts every constant in the
pinned `codes` module appears in the mapping — fails the build when the core adds codes,
forcing a conscious mapping update).

| Code range | Java class (extends `LaminarException`) |
|---|---|
| 100–102 connection (`CONNECTION_FAILED`, `CONNECTION_CLOSED`, `CONNECTION_IN_USE`) | `LaminarConnectionException` |
| 200–203 schema (`TABLE_NOT_FOUND`, `TABLE_EXISTS`, `SCHEMA_MISMATCH`, `INVALID_SCHEMA`) | `LaminarSchemaException` |
| 300–302 ingestion (`INGESTION_FAILED`, `WRITER_CLOSED`, `BATCH_SCHEMA_MISMATCH`) | `LaminarIngestionException` |
| 400–402 query (`QUERY_FAILED`, `SQL_PARSE_ERROR`, `QUERY_CANCELLED`) | `LaminarQueryException` |
| 500–502 subscription | `LaminarSubscriptionException` (declared now, exercised in Phase 2) |
| 900 `INTERNAL_ERROR` | `LaminarInternalException` |
| 901 `SHUTDOWN` | `LaminarShutdownException` |

All unchecked; all carry `getCode()`. Unknown future codes map to `LaminarException`
itself (never swallowed).

## 6 — Test matrix (JUnit, in `src/test/java`)

Functional (happy paths): open/execute/query roundtrip for each SQL result kind;
insert-then-query value fidelity across all supported types (ints, floats, strings,
timestamps, nulls); writer happy path with watermark advancement; checkpoint on a
started pipeline returns an id; streaming query drains to EOF and `isActive()` flips;
bounded event-time join and temporal ASOF join produce correct matched rows as Java-side
watermarks advance (per §8 example — covers the stateful path end-to-end).

Negative: every exception class above is produced by a targeted scenario (bad SQL 401,
insert into missing source 200, wrong types 302, use-after-close 101, writer-after-close 301,
double-create source 201, checkpoint without config → documented behavior at pin).

Lifecycle/robustness: double-close everywhere (connection, writer, result, batch);
close-with-open-writer (writer flushed by connection close? verify core behavior —
document what the pin does, do not invent); 500× open/close loop with allocator
accounting back to zero; concurrent `execute` from 8 threads on one connection
(serializes without deadlock — timeout-bounded test); `tryNext` on an idle stream does
not spin CPU.

Interop: Spike A roundtrip tests promoted into the suite (export→import fidelity both
directions); map-insert of 10k rows; zero-copy insert via VectorSchemaRoot of 100k rows.

Documentation-as-test: a `QuickstartIT` that literally executes the README quickstart
code and the §8 stateful-join example (keeps docs honest — a broken example in docs is
a broken test in CI).

## 7 — Performance sanity gates (not CI-blocking; record numbers)

Manual/nightly: insert throughput ≥ 500k rows/s for zero-copy batches of ~65k rows on a
dev laptop; `execute`+small-query roundtrip < 1 ms after warmup. Record in
`docs/benchmarks.md` (created Phase 1, grown in Phase 2 with JMH per plan 03 §5).

## 8 — Documentation deliverables

- [ ] README quickstart (5-line open→DDL→insert→start→query→subscribe-note).
- [ ] `docs/stateful-and-joins.md` — the flagship capability walkthrough: watermarks,
      window-close emission, bounded event-time interval joins, temporal ASOF joins —
      anchored on this example (syntax verified against the core `docs/SQL_REFERENCE.md`
      at `v0.30.x`; re-verify at pin). The `QuickstartIT` executes it verbatim (§6):

```java
try (LaminarConnection conn = LaminarDB.open(":memory:")) {
    conn.execute("""
        CREATE SOURCE trades (
            trade_id BIGINT NOT NULL, symbol VARCHAR NOT NULL, price DOUBLE NOT NULL,
            ts TIMESTAMP NOT NULL,
            WATERMARK FOR ts AS ts - INTERVAL '5' SECOND)""");
    conn.execute("""
        CREATE SOURCE orders (
            order_id VARCHAR NOT NULL, symbol VARCHAR NOT NULL,
            side VARCHAR NOT NULL, price DOUBLE NOT NULL, ts TIMESTAMP NOT NULL,
            WATERMARK FOR ts AS ts - INTERVAL '5' SECOND)""");
    conn.execute("""
        CREATE SOURCE quotes (
            symbol VARCHAR NOT NULL, price DOUBLE NOT NULL, ts TIMESTAMP NOT NULL,
            WATERMARK FOR ts AS ts - INTERVAL '5' SECOND)""");

    // Temporal ASOF join: each trade enriched with the latest quote at its event time.
    conn.execute("""
        CREATE STREAM valued_trades AS
        SELECT t.trade_id AS trade_id, t.price AS trade_price, q.price AS quote_price
        FROM trades t
        LEFT JOIN quotes FOR SYSTEM_TIME AS OF t.ts AS q
          ON t.symbol = q.symbol""");

    // Bounded event-time interval join: trades matched to orders within 10 s.
    // Every projection aliased; every column left/right qualified; directional
    // predicate is right.ts BETWEEN left.ts AND left.ts + bound (engine rules).
    conn.execute("""
        CREATE STREAM matched AS
        SELECT t.trade_id AS trade_id, o.order_id AS order_id,
               t.price - o.price AS price_diff
        FROM trades t
        INNER JOIN orders o
        ON t.symbol = o.symbol
        AND o.ts BETWEEN t.ts AND t.ts + INTERVAL '10' SECOND""");

    conn.start();

    try (Writer trades = conn.writer("trades");
         Writer quotes = conn.writer("quotes")) {
        quotes.insert(Map.of("symbol", "AAPL", "price", 201.0,
                             "ts", Instant.parse("2026-08-29T10:00:00Z")));
        trades.insert(Map.of("trade_id", 1, "symbol", "AAPL", "price", 201.5,
                             "ts", Instant.parse("2026-08-29T10:00:02Z")));
        // Advance event time from Java: ASOF results finalize once the quotes
        // watermark passes the probe time; interval-join outer rows finalize when
        // the opposite watermark closes the match interval.
        quotes.watermark(Instant.parse("2026-08-29T10:00:07Z").toEpochMilli());
        trades.watermark(Instant.parse("2026-08-29T10:00:07Z").toEpochMilli());
    }

    try (Subscription sub = conn.subscribe("SELECT * FROM valued_trades")) {
        sub.streamBatches().forEach(this::consume);   // Arrow RecordBatch
    }
}
```

      The doc also states the fail-closed limits verbatim from the core reference —
      no windowed joins, no fused join+`GROUP BY` in one statement, no cross/unbounded/
      non-equality/multi-way joins — so Java users hit them as documented errors, not
      surprises. A `TUMBLE`-window aggregate variant (window close driven by the same
      `Writer.watermark()` calls) closes the walkthrough.
- [ ] `docs/threading.md` — thread-safety contracts per class, blocking semantics,
      virtual-thread guidance (blocking calls pin carriers; use async APIs in Phase 2).
- [ ] `docs/errors.md` — generated code table from §5.
- [ ] `docs/build.md` — contributor build (just targets), pinned-core policy.

## Pin findings (recorded during execution, 2026-08-29)

Corrections the pinned core v0.30.0 forced; each is implemented and tested:

1. **Ad-hoc queries read TABLEs and inline VALUES.** `SELECT` over streaming
   SOURCES/STREAMs is not the read path at the pin (verified by core probes;
   the Python binding's own tests query `VALUES` and assert insert counts,
   never query-back). Phase 1 tests follow that shape; stream output is read
   via subscriptions (Phase 2). `query()` materializes with **blocking**
   `next` — the core's own `Connection::query` collects via non-blocking
   `try_next` and can miss not-yet-ready batches.
2. **Query exhaustion:** `next()==null` signals end-of-stream; `isActive()`
   stays true until `cancel()` (§6's "isActive flips" expectation was wrong).
3. **`CREATE TABLE` requires exactly one PRIMARY KEY.**
4. **Temporal ASOF joins** need (a) inline `PRIMARY KEY` on the right source's
   key column, (b) `temporal_join_idle_history_retention` configured, and (c)
   **connector-backed sources** — refused with plain embedded sources under
   the `api`-only feature set. §8's ASOF example is documented in
   `docs/stateful-and-joins.md` with those requirements; exercising it
   end-to-end waits on the Phase 2 connector-matrix decision (plan 03 §6).
5. **TUMBLE syntax** is `GROUP BY key, tumble(ts, INTERVAL '10' SECOND)`
   (lowercase canonical; a function, not a trailing clause). `ORDER BY`
   without `LIMIT` fails closed on unbounded streams.
6. **Source-only pipelines run no checkpoint coordinator** — `checkpoint()`
   needs at least one derived stream; source-only call fails 900 "call
   start() first".
7. **DDL reopen persistence is not a guaranteed api behavior** (the Python
   binding tests no reopen); storage-dir tests assert checkpoint-id advance
   instead.
8. **SQL TIMESTAMP maps to Arrow `Timestamp(us)`** at the pin.
9. **Java→Rust Arrow batches deep-copy on import** (`arrow_jni.rs
   import_batch`): arrow-java releases exported buffers via a JNI upcall, so
   a zero-copy handoff would release JVM memory from arbitrary engine
   threads. Rust→Java stays zero-copy. §3's "zero-copy export" is amended
   accordingly (docs/benchmarks.md records the copy's cost share).
10. **arrow-java 19 on JDK 17+ needs `--add-opens java.base/java.nio=ALL-UNNAMED`**
    with the netty allocation backend (compile-scope `arrow-memory-netty`;
    surefire argLine carries the flag).
11. **§8's `Writer.insert(Map)` sketch** is `write(List<Map>)` in the real
    API (§1's own signature); `QuickstartIT` executes the corrected example.
12. **Native set deltas vs §2:** `executeSql`→`execute` and `closeConnection`
    →`close` (contract names), plus `streamSchemaExport` and `resultNumRows`
    (both §1 accessors with no §2 native — added); every other name matches.
13. **Duplicate `CREATE SOURCE` surfaces as 400** "already exists" (recorded
    in plan 01); `checkpointing is not enabled` (900) precedes state checks.

14. **Exit-review round 2 findings (all resolved):** never-imported
    `ArrowBatch` release (explicit `release()` on close), lock-across-call for
    every wrapper class, error-path FFI-container cleanup, dead `isClosed`
    native removed (Java checks its own handle), `writerFree` wired through a
    Cleaner backstop with an atomic claim (close never joins the cleaner
    thread — that deadlocked), version parity `0.30.0-alpha` everywhere,
    release-workflow jar clobbering fixed, unknown map keys now 302, pin
    behavior of closing with an open writer documented and tested (buffers
    pin until the writer closes, even late), type-name normalization lives
    Java-side (one source of truth: the imported pojo schema).

## Acceptance checklist (Phase 1 exit)

- [x] Full §6 matrix green (as reshaped by the pin findings above) across the
      CI matrix — extended to Linux x86_64/aarch64 (`ubuntu-latest`,
      `ubuntu-24.04-arm`) and macOS aarch64/x86_64 (`macos-latest`,
      `macos-13`) per plan 04 §4.
- [x] Rust-side `codes` coverage test green; no `unwrap`/`expect` on
      user-controlled paths; `cargo clippy -D warnings` clean.
- [x] Allocator accounting zero after every test class (JUnit `@AfterAll`
      assertion) — three real leak paths found and fixed by exactly this rule
      (`numRows` batch imports, `RowConverter.toRoot` failure paths, engine
      retention of java-exported buffers via the deep-copy change).
- [ ] **BLOCKED (maintainer):** alpha artifact on Maven Central. The full
      release pipeline is wired (release.yml: validate → 4-platform
      build-native → assemble-and-test incl. bare-project quickstart via
      `scripts/bare-quickstart.sh` → publish → verify-publish → GitHub
      release; `-alpha` versioning, CORE_PIN.md, CHANGELOG). Publishing
      requires one-time maintainer setup that cannot be delegated: Central
      Portal namespace ownership for `io.laminardb`, a GPG signing key, and
      the `maven-central` GitHub environment secrets. Until then the release
      workflow's publish step intentionally fails with a recorded blocker.
- [x] Review gates green per plan 06: SpotBugs + the JaCoCo zero-coverage
      rule wired into `just review` (the rule caught two genuinely unexercised
      classes — `LaminarShutdownException` gained a test,
      `LaminarSubscriptionException` carries the sanctioned Phase-2 exclusion);
      every public member exercised by a test and referenced in docs; phase
      review recorded in `docs/reviews/phase1-2026-08-29.md`.
- [x] Plans 01/02 statuses updated; conventional commits throughout.
