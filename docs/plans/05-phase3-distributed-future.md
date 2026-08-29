# Plan 05 — Phase 3 (future): distributed mode, server driver, FFM backend

Status: **Direction only — do not implement before Phase 2 exits.** Each section is a
decision placeholder with its trigger; nothing here is committed scope.

## 1 — Connection-string routing (the seam that makes Phase 3 cheap)

Phase 1/2 must keep one rule intact: `LaminarDB.open(String path, …)` treats the first
argument as a routing key owned by `io.laminardb.internal.Binding`:

| Path form | Binding implementation | Phase |
|---|---|---|
| `":memory:"` / filesystem path | `JniBinding` (embedded, local-durable via config) | 1 |
| `laminar://host:port` | network driver over the server | 3 |
| `laminar+cluster://…` | cluster-aware driver | 3+ |

Consequences for Phase 1/2 (cheap now, expensive to retrofit): no public API leaks
handle types, exceptions, or threading assumptions that only hold in-process; async
adapters built on poll APIs rather than in-process callbacks; Javadoc never promises
single-process semantics. The public surface in plans 02/03 already complies — this
section is the guardrail for keeping it that way.

## 2 — Server client driver (`laminar://`)

What the standalone `laminardb` server already offers a JVM client, and the honest
limits (verified against the core as of `v0.30.x`):

| Capability | Protocol | Limits |
|---|---|---|
| DDL + admin (start/stop/checkpoint/reload) | HTTP `/api/v1/*`, token auth | fine for control plane |
| Queries | HTTP `/api/v1/sql` | SELECT capped at 1000 rows / 5 s, JSON rows — not a data plane |
| Streaming reads | WebSocket `/ws/{name}` | JSON rows, 1 MiB frames — workable for MVP, not Arrow |
| Streaming reads (relational) | pgwire `SUBSCRIBE` via PostgreSQL JDBC | read-only; DDL not on pgwire |
| **Ingestion** | **none over the network** | the blocker |

Phase 3 shape, staged:

1. **3a — read/control driver:** `LaminarConnection` over HTTP admin + pgwire
   `SUBSCRIBE` (plain PostgreSQL JDBC as a dependency of the *driver*, optional module).
   Viable for apps that ingest via server-side connectors (Kafka→) and consume via
   subscriptions. Embedded `Writer`/`insert` surface throws
   `UnsupportedOperationException` with a clear message on the network binding.
2. **3b — real data plane (needs core-repo work, propose there, don't hack around):**
   an Arrow-Flight-based ingestion + subscription endpoint on the server (gRPC +
   Arrow streams, token auth, bounded streams). Until the core ships something at this
   grade, a Java network driver must not fake it over the JSON/capped surfaces.

Guardrail carried over from the core's design stance: cluster admission is deliberately
narrow and fail-closed; the Java driver surfaces those refusals verbatim and never
widens admission client-side to make demos pass.

## 3 — FFM (Panama) backend behind the SPI

Revisit trigger (from D1/D8): Java 25+ is the required floor for final FFM, so this
becomes worthwhile when (a) a surveyed slice of target users are on 25+, or (b) a
need arises that JNI cannot serve (e.g. GraalVM native-image requirements, or removing
libjvm coupling for a host of embedding scenarios). Not before.

If triggered:

- Consume the core's **C `ffi` feature** (not a parallel hand-written C ABI), which
  first needs core-repo enablers — file them upstream: (1) a `cdylib` target or a
  maintained shim crate, (2) cbindgen header generation in core CI, (3) FFI surface
  gaps: config-based open, `insert`, `checkpoint`, poll-based `ArrowSubscription`,
  canonical (non-`Debug`) schema type strings.
- `FfmBinding` implements the same internal `Binding` interface; jextract over the
  cbindgen header as a dev-time accelerator only (the surface is small enough to
  hand-maintain; jextract output is not a public dependency).
- Upcalls for callbacks: FFM auto-attaches native threads — the callback design from
  plan 03 §4 translates one-for-one (worker thread → upcall stub), and the JNI
  AttachGuard/global-ref machinery is simply absent.
- Ship as the default only when benchmarks (plan 03 §5) show parity or better and the
  soak suite passes; otherwise ship as opt-in (`laminardb.binding=ffm` system property).

## 4 — Explicit non-goals (recorded so they are not re-litigated)

- **In-process cluster execution.** Cluster mode requires server processes with gossip,
  leases, and fenced shuffle; the JVM embeds single-node embedded mode only (D2).
- **A second SQL dialect or client-side planning.** SQL goes to the core verbatim.
- **Android/ART support.** Untested, unplanned; the FFM/JNI matrix targets server JVMs.
- **Automatic retry/reconnect semantics hiding delivery guarantees.** The typed
  at-least-once/exactly-once connector composition rules from the core surface as-is
  through exceptions and docs.

## 5 — Open questions (parking lot)

- Does the core's roadmap include an Arrow Flight / gRPC data plane, and on what
  horizon? (Determines whether 3b is "integrate" or "propose and wait".)
- Kotlin extension module (`laminardb-kotlin`, coroutine adapters over the async API) —
  demand? Record: ______
- Spring Boot starter (`laminardb-spring-boot-starter`, auto-configured embedded
  engine bean) — natural post-Phase-2 follow-up; keep out of the core module.
