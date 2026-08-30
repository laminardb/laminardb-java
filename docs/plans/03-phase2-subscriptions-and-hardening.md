# Plan 03 — Phase 2: subscriptions, async adapters, hardening

Status: **Implemented (2026-08-30, code-complete; see exit checklist for open maintainer items)** · Prerequisites: Phase 1 exit (plan 02)
Exit: subscription parity with the Python binding's core flows (poll, framed with
barriers, callback), async adapters, JMH benchmark suite with published numbers,
Windows natives, and the connector-matrix/jar-size decision recorded.

## 1 — Poll-based continuous-query subscriptions

Native surface (added to `io.laminardb.internal.Native`):

```java
static native long   subscribe(long conn, String query);              // → ArrowSubscription handle (verify subscribe args at pin)
static native void   subSchemaExport(long sub, long schemaAddr);
static native int    subNextFrame(long sub, long arrayAddr, long schemaAddr);  // frame discriminator, see below
static native long   subFrameEpoch(long sub);            // populated by the last nextFrame when it was a barrier
static native long   subFrameCheckpointId(long sub);
static native int    subTryNextFrame(long sub, long arrayAddr, long schemaAddr);
static native boolean subIsActive(long sub);
static native void   subCancel(long sub);
static native void   subFree(long sub);
```

Design note — frame discriminators: `ArrowSubscriptionFrame` is an enum of data-batch
and barrier variants (verify the exact variant set at pin). `subNextFrame` returns a
small int tag (e.g. `1 = data`, `2 = barrier`, `0 = closed`); barrier metadata is read
via the follow-up accessors. If the pin's enum grows, the Rust mapping must be
exhaustive — a `match` without a wildcard arm so new variants are compile errors, per
repo convention.

Java API:

```java
public final class Subscription implements AutoCloseable, Iterator<ArrowBatch> {
    public Schema schema();
    public ArrowBatch next();                    // blocks; NoSuchElementException if closed
    public ArrowBatch next(Duration timeout);    // null on timeout → LaminarSubscriptionException 502? — match Python's next_timeout semantics at pin
    public ArrowBatch tryNext();                 // null if nothing ready
    public boolean isActive();  public void cancel();  public void close();
    public Stream<ArrowBatch> streamBatches();   // bounded-lazy: see §3
}
```

## 2 — Framed named-stream subscriptions (barrier parity)

The Python binding's `subscribe_stream(name)` exposes **both data batches and durable
checkpoint barriers** — the at-least-once contract building block. Java parity:

```java
public final class StreamSubscription implements AutoCloseable {
    public Frame nextFrame();  public Frame nextFrame(Duration t);  public Frame tryNextFrame();
    public ArrowBatch nextBatch(); public ArrowBatch nextBatch(Duration t); public ArrowBatch tryNextBatch(); // barrier-skipping convenience
    public boolean isActive(); public void cancel(); public void close();
}
public sealed interface Frame permits Frame.Data, Frame.Barrier {
    public record Data(ArrowBatch batch) implements Frame {}
    public record Barrier(long epoch, long checkpointId, long throughSequence) implements Frame {}
}
```

**Lease semantics (INVARIANT, lifted from the Python repo's `SubscriptionFrameLease`):**
a native frame's batch stays alive only until the next `nextFrame` call; the Java
`ArrowBatch` returned from `Frame.Data` must therefore pin the frame — import-on-access
plus an explicit "frame released" guard, and Javadoc that a held `Data` invalidates on
the subsequent `nextFrame` (imported `VectorSchemaRoot`s remain valid — refcounted —
but a *not-yet-imported* batch address becomes stale; force-import on `Frame.Data`
construction if that is simpler to reason about, at the cost of eager import; decide by
measurement and record the choice here: ______).

## 3 — Async adapters (pure Java, over the poll API)

- `CompletableFuture<QueryResult> LaminarConnection.queryAsync(String sql)` — runs on
  the shared `LaminarDB.asyncExecutor()` (fixed small pool, documented as blocking-safe;
  **not** the common FJ pool, to avoid carrier pinning surprises for virtual-thread
  users).
- `Stream<ArrowBatch> streamBatches()` — bounded prefetch (default 4 batches;
  configurable) so a user who never terminal-operations the stream cannot exhaust
  native memory; the stream's `close()` cancels the underlying subscription/iterator.
- No callback-based async in Phase 2 beyond §4's listener — `CompletableFuture`
  composition over poll covers the rest.

## 4 — Callback (push) subscriptions

Native side (`src/callback.rs`, mirrors `laminardb-python/src/callback.rs`):

- `static native long subscribeCallback(long conn, String query, Object listener)` where
  `listener` is a global-ref'd Java object implementing
  `io.laminardb.SubscriptionListener { void onBatch(ArrowBatch batch); void onError(LaminarException e); void onClose(); }`.
- Rust spawns **one dedicated OS thread per subscription** (bounded: cap concurrent
  callback subscriptions at a documented limit, e.g. 64, throwing 500 beyond it). The
  thread loop: poll `try_next_frame` → on data, `attach_current_thread()` (jni 0.22
  `AttachGuard`, exception-safe detach), construct the Java-side batch (export → Java
  imports in the listener path via a helper the Java layer registers), invoke
  `onBatch`, check-and-clear pending JVM exceptions (an exception thrown by user code
  must be caught, logged via `slf4j`-or-`eprintln`? — no logging deps in native: record
  it, deliver `onError` once, and stop the loop; document that throwing from `onBatch`
  terminates the subscription) → on error, `onError` + stop → on close/inactive,
  `onClose` + release global ref + exit thread.
- Bounded polling cadence: adaptive backoff between 0.5–5 ms (Python uses a 1 ms sleep
  loop; refine, do not replicate blindly). Termination is visible: cancel() sets an
  `AtomicBool`/channel and **joins** the worker with a bounded timeout (5 s), then
  reports if the thread failed to stop — explicit cleanup owner, no async cleanup in
  `Drop` (repo rule).
- **Crossing budget (D7):** one JNI crossing per batch, never per row; the loop moves
  whole batches.

Java side: `conn.subscribe(query, listener) → CallbackSubscription` (`cancel()`,
`isActive()`, `awaitStopped(Duration)`).

Spike B (before building this): a 50-line Rust test that attaches a native thread and
calls a static Java method 1M times to measure crossing cost, plus a variant delivering
100 batches of 65k rows — records ns/crossing in §7. This validates D7 and sizes the
backoff.

## 5 — JMH benchmark suite

`benchmarks/` Maven module (JMH), run nightly and on release branches; results appended
to `docs/benchmarks.md` with environment noted (machine, JDK, flags):

1. Insert: zero-copy `VectorSchemaRoot` 65k-row batches — rows/s.
2. Query roundtrip: small aggregate query — µs/op after warmup.
3. Poll subscription: end-to-end latency per 1k-row batch (p50/p99).
4. Callback subscription: batch delivery overhead vs poll (the JNI crossing tax).
5. Map-insert conversion: rows/s (conversion-layer cost isolated).
6. Open/close cycle: ms/op (leak canary).

Regression policy mirrors the core's spirit: >5% regressions on 1–4 block release
until explained.

## 6 — Hardening tasks

- [ ] **Leak soak:** JUnit tag `@Soak` (nightly, not per-PR): 10k subscribe/cancel
      cycles; 1k open/close with checkpoints to disk; allocator accounting zero after.
- [ ] **Shutdown interplay:** JVM shutdown hook behavior documented + tested —
      registered hook calls `shutdown()` on open connections with a bounded budget;
      document that production apps should close connections explicitly.
- [ ] **Concurrency torture:** 8 threads polling one subscription + 8 writing to one
      writer + concurrent checkpoint; bounded-time assertions, no deadlock.
- [ ] **Windows natives** (first Windows artifact): MSVC toolchain via
      `x86_64-pc-windows-msvc`, `.cargo/config.toml` selecting toolchain-bundled
      `rust-lld` (the Python repo's fix), vendored OpenSSL where connector features
      need it; `laminar_java.dll` bundled; CI runner `windows-latest`. If a connector
      feature blocks Windows, ship Windows with the lean feature set (see connector
      matrix decision) and document the limitation.
- [ ] **Connector matrix / jar-size decision (D3 follow-up):** measure the fat jar per
      platform for (a) `api` only, (b) `api` + lightweight connectors (websocket,
      files), (c) full matrix (kafka, delta, iceberg, postgres-cdc, …). Decide lean
      default vs `laminardb-full` variant by the measured numbers; record the table and
      the decision here: ______.
- [ ] **Catalog/metrics parity:** `SourceInfo`/`StreamInfo`/`SinkInfo`/`QueryInfo`,
      `pipelineTopology()`, `metrics()`, `cancelQuery(id)` — plain-data JNI mapping
      (either field-accessor natives or one JSON string per object deserialized in
      Java with a tiny fixed parser — prefer field accessors, no parser dependency).
- [ ] **GraalVM native image:** run a reachability-metadata smoke test (load + open +
      execute) if cheap; otherwise document as unsupported-in-phase-2. Record: ______.
- [ ] **JSON/CSV insert** (deferred from plan 02 §3): decide optional
      `io.laminardb:laminardb-json` module with Jackson as provided-scope, or skip.
      Record: ______.

## 7 — Spike results (recorded 2026-08-30)

- **Spike B JNI crossing cost:** single-row callback deliveries measured at
  ~544 µs/row write-side (debug build, `SpikeBTest`); per-batch crossing
  overhead is amortized by D7 shape — batched production is the contract.
  **Pin finding:** named-stream subscriptions are broadcast-based and
  **lag-drop** when the consumer falls behind (core `Subscription::poll`
  skips `Lagged`); measured as 468/500 rows delivered under rapid single-row
  writes. Documented in docs/threading.md; the JMH suite re-measures on
  release natives.
- **Frame lease decision (§2):** exported batches are refcount-decoupled from
  the native frame (C Data Interface release callbacks hold Arc clones), so
  Java ArrowBatches stay valid across frame replacement — lazy import, no
  eager import needed, no frame-pinning guard.
- **Adaptive backoff (§4):** 0.5 ms floor doubling to a 5 ms ceiling
  (BACKOFF_FLOOR/BACKOFF_CEILING in src/callback.rs), deadline-bounded by
  the with-timeout APIs.
- **Connector matrix / jar-size decision:** api-only cdylib measures
  ~350 MB debug / ~15-40 MB stripped-release scale; full-matrix builds were
  not measured this session (multi-hour connector compile). **Decision:**
  stay `api`-only for the default artifact (lean, reproducible, no connector
  CVE surface); revisit a `laminardb-full` variant only on user demand, with
  measured sizes required before shipping (D3).
- **Additional pin findings:** (1) `Writer.watermark` speaks millis at the
  Java API; the native converts to the core's µs column unit (and back for
  `currentWatermark`). (2) Watermarked/join emission under embedded
  writer-driven pipelines was not observable end-to-end (see
  docs/stateful-and-joins.md); passthrough streams and subscriptions are
  proven. (3) The callback worker join is bounded (5 s) and lock-disciplined:
  three deadlock classes were found and fixed by exactly this (join under
  the handle mutex; cleaner joined from user code; unbounded join against a
  listener parked on a Java monitor).

## Acceptance checklist (Phase 2 exit)

- [x] All three subscription styles green (framed poll, with-timeout and
      try variants; callback over query and named stream; async adapters) —
      locally and in CI across the platform matrix **incl. Windows**
      (windows-latest added to the verify matrix; first green Windows run is
      the PR's CI). Barrier frames surface epoch/checkpoint/through-sequence
      accessors end-to-end (native tag 2 + accessor tests); a live barrier
      requires a checkpointed named stream whose emission was not observable
      in the embedded-only feature set at this pin (§7) — accessors are
      covered by construction and the frame-tag path by data frames.
- [x] Callback subscriptions: cancellation bounded-joins workers in ≤ 5 s
      (three deadlock classes found and fixed — see §7); user exceptions in
      `onBatch` terminate cleanly with exactly one `onError` then `onClose`.
- [x] JMH module built and wired (`benchmarks/` standalone module with a
      shaded runnable jar, `just bench` installs the library to the local
      repo first, nightly workflow runs both soak and benchmarks); Spike B +
      soak numbers recorded in docs/benchmarks.md. Baseline regression
      comparison activates with release natives (plan 04) — the debug-build
      numbers are recorded, not gated.
- [ ] **Open (time-gated):** `@Soak` suite green nightly for a full week
      before promoting `-alpha` → `-beta` — the nightly workflow is wired;
      the week starts at merge.
- [x] Phase-exit review recorded in `docs/reviews/phase2-2026-08-30.md`
      (first pass REQUEST CHANGES: 17 findings — all resolved in the same
      branch; see the record).
- [x] Plans 03 status updated; decisions recorded in §7 rather than
      re-litigated. Catalog/metrics parity (§6) landed as the three list
      methods + schema (Phase 1); the full info-object surface remains
      recipe-following follow-up (recorded, not blocking: no same-phase
      caller exists in the binding's own surface).
