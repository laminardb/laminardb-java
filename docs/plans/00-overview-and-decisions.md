# Plan 00 — Overview and decision records

Status: **Accepted direction, not yet implemented** · Date: 2026-08-29
Applies to: `laminardb-java` (this repository, to be created)

## 1. Mission

Give Java developers the `pip install laminardb` experience in JVM terms: **one Maven
dependency, one line to open, streaming SQL in-process**.

```java
try (LaminarConnection conn = LaminarDB.open(":memory:")) {
    conn.execute("CREATE SOURCE sensors (ts TIMESTAMP, device VARCHAR, value DOUBLE)");
    conn.insert("sensors", rows);
    conn.start();
    try (Subscription sub = conn.subscribe(
            "SELECT device, avg(value) AS avg_v FROM sensors GROUP BY device")) {
        sub.streamBatches().forEach(batch -> consume(batch));   // Arrow RecordBatch
    }
}
```

Target user: server-side Java/Kotlin/Scala engineers (services, Spring/Quarkus apps,
stream processors) on Java 17–25. Phase 1 ships **embedded only** (in-JVM), with the
public API designed so a distributed/server-client mode is a later driver implementation,
never a rewrite.

## 2. Architecture

```
┌────────────────────────────────────────────────────────────┐
│  User code (Java 17+)                                      │
├────────────────────────────────────────────────────────────┤
│  io.laminardb — public API (pure Java)                     │
│  LaminarDB · LaminarConnection · LaminarConfig.Builder ·   │
│  QueryResult · Subscription · Writer · exceptions          │
├──────────────── internal SPI (non-public) ─────────────────┤
│  io.laminardb.internal.Binding — native backend interface  │
│  JniBinding (v1)                 FfmBinding (future, opt.) │
├────────────────────────────────────────────────────────────┤
│  laminar-java (Rust cdylib, crate `jni` 0.22)              │
│  → laminar_db::api::* via the core `api` cargo feature     │
│  → Arrow RecordBatch in/out via the Arrow C Data Interface │
└────────────────────────────────────────────────────────────┘
```

Two layers, mirroring `laminardb-python`:

1. **Rust layer** (`src/*.rs`, one cdylib): JNI native methods over
   `laminar_db::api::*`. Owns the process-global Tokio runtime, handle lifetimes,
   Arrow C Data Interface crossing, and error → Java-exception mapping.
2. **Java layer** (`io.laminardb`): the friendly, documented API. All native symbols
   live behind `io.laminardb.internal` and are never part of the public surface.

## 3. Decision records

### D1 — JNI over the `api` facade (not the C `ffi` feature, not FFM)

**Decision.** The Rust cdylib uses the `jni` crate and calls `laminar_db::api::*`
directly, exactly as `laminardb-python` uses it via PyO3.

**Why.**
- The `api` module is the core's intended binding seam and is complete (config open,
  insert, checkpoint, subscriptions, catalog, metrics). The C `ffi` feature is not: it
  lacks config-based open, `insert`, `checkpoint`, and poll subscriptions, has no
  cdylib crate-type anywhere in the workspace, and no cbindgen header. Using `api`
  means **zero main-repo changes** and keeps this repo self-contained.
- FFM (Panama) is final only on Java 22+; Java 25 (Sep 2025) is the first LTS with it.
  Java 21 remains the dominant enterprise LTS in 2026 and only has FFM as preview,
  which a shipped library cannot require. JNI covers 17/21/25 in one artifact.
- Strong fresh precedent: Apache `datafusion-java` (2025) ships exactly this shape —
  Rust engine, JNI, Arrow C Data Interface, bundled natives, JDK 17+.

**Consequences.** Rust-side JNI discipline required: cached `JavaVM`, exception-safe
`AttachGuard` for Rust→JVM callbacks, global refs for listener objects, no blocking in
`JNI_OnLoad`. JEP 472 (JDK 24+) puts JNI under the same `--enable-native-access`
warning regime as FFM, so both paths converge on documenting that flag.

**Revisit trigger.** See D8 — an FFM backend can be added behind the internal SPI when
Java 25+ adoption justifies it, without public API changes.

### D2 — Embedded-first; sidecar is the existing server, not this repo

**Decision.** Phase 1 embeds the engine in the JVM (`:memory:` and local-durable
embedded via `LaminarConfig.storage_dir` + checkpoints). The "sidecar" mode already
exists — it is the standalone `laminardb` server binary; a Java **client driver** for it
is Phase 3 work (plan 05).

**Why.** The server's current network data plane cannot carry a serious SDK: HTTP SQL
caps SELECTs at 1000 rows / 5 s, WebSocket streams are JSON rows, and there is **no
remote ingestion path at all**. Embedded avoids all three limits and matches the
proven Python UX. Embedded mode supports local durability (object-store/local
checkpoints) but **not** multi-node cluster execution — cluster requires server
processes; that boundary is intentional and documented, not a gap to paper over.

### D3 — Packaging: one fat jar with bundled natives

**Decision.** `io.laminardb:laminardb` on Maven Central, natives for all supported
platforms bundled under `/natives/<os>-<arch>/` and extracted at first load
(sqlite-jdbc / datafusion-java pattern). Per-platform classifier jars are a later
optional addition for slim Docker images (plan 04).

### D4 — Versioning: track the core, pin the core

**Decision.** Binding version tracks the core version (binding `0.31.0` ships core
`v0.31.0`); binding-only fixes bump the patch. Each release pins an **exact core git
tag** in `Cargo.toml` — never a branch. Release gate validates tag == Cargo version ==
pom version == pinned core tag.

**Why.** `laminardb-python` clones the monorepo's `main` at build time; that is the
mistake not to copy — its releases are not reproducible and its last two releases also
failed to publish to PyPI. Our release workflow adds a post-publish verification step.

### D5 — Java floor: 17

Arrow Java 20+ requires JDK 17; nothing in the stack needs less. Test matrix: 17, 21, 25.

### D6 — Arrow is the data plane

Zero-copy exchange via `org.apache.arrow.c` (`ArrowArray`/`ArrowSchema` addresses cross
JNI as `jlong`; the C Data Interface is ABI-stable, so arrow-java and arrow-rs versions
do not need to match). Friendly inputs (`List<Map<String,?>>`, JSON strings) and outputs
(`Stream<Map<String,Object>>`, row iteration) convert at the Java convenience layer.
JVM-side memory is managed by the caller-supplied or default Arrow allocator; imported
buffers are released by closing the imported vectors (ownership moves per the C Data
Interface contract).

### D7 — Callbacks cross the boundary per batch, never per row

Push subscriptions deliver one JNI crossing per `RecordBatch` (or per barrier). A
dedicated Rust worker thread polls the core subscription and invokes a global-ref'd Java
listener through an `AttachGuard` (mirrors `laminardb-python/src/callback.rs`). The
poll/iterator API is the primary subscription style; callbacks are the convenience.

### D8 — Public API hides the native mechanism (SPI)

`io.laminardb.internal.Binding` isolates the backend so a future FFM implementation
(Java 25+) or a network driver (plan 05) slots in without user-visible changes. The JNI
backend is v1; nothing public mentions JNI.

## 4. Phase map

| Phase | Plan | Ships | Exit criteria |
|---|---|---|---|
| 0 | 01 | repo skeleton, build, CI | open/execute/close smoke test green in CI on Linux + macOS |
| 1 | 02 | embedded MVP `0.x.0-alpha` on Maven Central (Linux x86_64/aarch64, macOS aarch64/x86_64) | quickstart runs from a bare Maven project with one dependency |
| 2 | 03 | subscriptions (poll + framed + callback), async adapters, benchmarks, Windows natives | parity with the Python binding's core flows; JMH numbers published |
| 3 | 05 | `laminar://` server driver + FFM backend (separate decisions) | out of scope until Phase 2 exits |

Plan 04 (release engineering) is written to be read before Phase 1 exits: the artifact
layout decisions in it constrain how Phase 0/1 structure the build.

## 5. Cross-cutting conventions

- **Commits**: Conventional Commits; no AI/assistant attribution, no `Co-Authored-By`
  trailers, no tool-session metadata (matches the main repo's policy).
- **Errors**: every native failure maps to an unchecked `LaminarException` subclass
  carrying the numeric `ApiError` code (table in plan 02 §5). Never swallow; never
  surface raw JNI errors to users.
- **Lifecycles**: every `AutoCloseable` has idempotent `close()`; double-close and
  use-after-close must be safe (throw `LaminarConnectionException` code 101, not
  segfault). Cleanup ownership is single and explicit — no async cleanup hidden in
  finalizers; `Cleaner` is a leak backstop only, never the primary path.
- **Thread-safety**: each public class documents its thread-safety contract in Javadoc.
  `LaminarConnection` is thread-safe (internal mutex serializes core calls — same
  contract as the Python binding); `Writer`/`Subscription` handles are single-owner.
- **Blocking**: every blocking public method says so in Javadoc, including
  virtual-thread pinning guidance (blocking JNI calls pin carriers; acceptable for a DB
  driver, escape hatch is the async API).
- **Code discipline**: carry over the main repo's readability rules in spirit — flat
  control flow, one owner per mutable state group, bounded loops with visible
  termination, typed errors, no `unwrap`/`expect` on user/network/config-controlled
  data. Rust glue modules stay under the same size thresholds (~600 lines, extract at
  ~800).
- **Verification**: the pinned core tag's source is authoritative. When a signature in
  these plans disagrees with the pinned core, fix the plan in the same PR.

## 6. Appendix — verified core `api` surface (core `v0.30.x`, 2026-08-29)

Verified against `crates/laminar-db/src/api/` of the main repo. The pinned tag in
`Cargo.toml` overrides this appendix if they drift.

```rust
// api/connection/mod.rs
Connection::open() -> Result<Connection, ApiError>
Connection::open_with_config(config: LaminarConfig) -> Result<Connection, ApiError>
Connection::execute(&self, sql: &str) -> Result<ExecuteResult, ApiError>   // ExecuteResult is an enum (ddl/rows_affected/metadata/query variants — verify at pin)
Connection::query(&self, sql: &str) -> Result<QueryResult, ApiError>
Connection::query_stream(&self, sql: &str) -> Result<QueryStream, ApiError>
Connection::writer(&self, source_name: &str) -> Result<Writer, ApiError>
Connection::insert(&self, source_name: &str, batch: RecordBatch) -> Result<u64, ApiError>
Connection::get_schema(&self, name: &str) -> Result<SchemaRef, ApiError>
Connection::list_sources / list_streams / list_sinks(&self) -> Vec<String>
Connection::start(&self) -> Result<(), ApiError>
Connection::close(self) -> Result<(), ApiError>
Connection::is_closed(&self) -> bool
Connection::checkpoint(&self) -> Result<u64, ApiError>          // checkpoint id
Connection::is_checkpoint_enabled(&self) -> bool
Connection::cancel_query(&self, query_id: u64) -> Result<(), ApiError>
Connection::shutdown(&self) -> Result<(), ApiError>
Connection::subscribe(&self, /* verify exact args at pin */) -> Result<ArrowSubscription, ApiError>
// catalog/metrics: source_info, sink_info, stream_info, query_info, pipeline_topology,
// pipeline_state, pipeline_watermark, total_events_processed, source_count, sink_count,
// active_query_count, metrics, source_metrics, all_source_metrics, stream_metrics, all_stream_metrics

// api/ingestion.rs
Writer::write(&mut self, batch: RecordBatch) -> Result<(), ApiError>
Writer::flush(&mut self) -> Result<(), ApiError>
Writer::close(self) -> Result<(), ApiError>
Writer::schema(&self) -> SchemaRef ; Writer::name(&self) -> &str
Writer::watermark(&mut self, timestamp: i64) ; Writer::current_watermark(&self) -> i64

// api/query.rs
QueryResult::{schema, batches, into_batches, num_rows, num_batches, batch(i), num_columns}
QueryStream::{schema, next, try_next, collect, is_active, cancel}

// api/subscription.rs
ArrowSubscription::{schema, next_frame, try_next_frame, is_active, cancel}
ArrowSubscriptionFrame // enum: data batch + checkpoint barrier variants — verify at pin

// api/error.rs — ApiError::{code() -> i32, message() -> &str}; codes module:
// 100 CONNECTION_FAILED, 101 CONNECTION_CLOSED, 102 CONNECTION_IN_USE,
// 200 TABLE_NOT_FOUND, 201 TABLE_EXISTS, 202 SCHEMA_MISMATCH, 203 INVALID_SCHEMA,
// 300 INGESTION_FAILED, 301 WRITER_CLOSED, 302 BATCH_SCHEMA_MISMATCH,
// 400 QUERY_FAILED, 401 SQL_PARSE_ERROR, 402 QUERY_CANCELLED,
// 500 SUBSCRIPTION_FAILED, 501 SUB_CLOSED, 502 SUBSCRIPTION_TIMEOUT,
// 900 INTERNAL_ERROR, 901 SHUTDOWN

// LaminarConfig (crates/laminar-db/src/config.rs) — phase-1-relevant fields:
// default_buffer_size, default_backpressure, storage_dir: Option<PathBuf>,
// checkpoint: Option<StreamCheckpointConfig>, incremental_emit, object_store_url,
// object_store_options, delivery_guarantee, pipeline_channel_capacity,
// pipeline_batch_window, pipeline_drain_budget_ns, pipeline_query_budget_ns,
// pipeline_max_input_buf_batches/bytes, pipeline_max_managed_state_bytes,
// temporal_join_idle_history_retention, (idle detection — see config.rs at pin)
```
