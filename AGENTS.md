# Repository guidance

## Project purpose

This repository provides the Java bindings for the LaminarDB streaming SQL
database. The native layer is a Rust cdylib (`laminar-java`, crate `jni` 0.22)
over `laminar_db::api` via the core's `api` cargo feature; the Java layer is the
documented `io.laminardb` API. All native symbols live behind
`io.laminardb.internal` and are never part of the public surface.

The core dependency is pinned to an **exact git tag** in `Cargo.toml` (never a
branch). The pinned tag's source is authoritative over plan prose wherever they
disagree; fix the plan in the same PR that proves it wrong. No checkout of the
core monorepo is needed — cargo resolves the tag from GitHub directly.

## Repository layout

- `src/lib.rs`: cdylib entry, `JNI_OnLoad` (caches the `JavaVM`, nothing else).
- `src/connection.rs`: Phase 0 JNI surface (`openDefault`, `executeSql`,
  `closeConnection`, `version`); grows per plan 02 §2.
- `src/handle.rs`: the peer-pointer discipline shared by every JNI module.
- `src/error.rs`: the single `ApiError` → Java exception mapping point.
- `src/runtime.rs`: the process-global Tokio runtime.
- `src/arrow_jni.rs`: Arrow C Data Interface crossings (Spike A seed).
- `src/main/java/io/laminardb`: public API. `src/main/java/io/laminardb/internal`:
  native seam (package-private; never referenced by user code).
- `src/test/java`: JUnit suite (smoke tests + Arrow spike).
- `justfile`: build orchestration; `pom.xml` stays build-tool pure (cargo is
  never invoked from Maven).
- `docs/plans/`: implementation plan series — the living backlog; keep
  checkboxes and status headers current. `docs/reviews/`: phase review records.
- `agents/code-review.md`: the reviewer prompt enforcing `docs/plans/06`.

## Build and verify

- `just build` — `cargo build`, then stage the cdylib under `target-native/debug/`.
- `just test` — build + `mvn -Djava.library.path=target-native/debug test`.
- `just verify` — correctness gate: fmt, clippy `-D warnings`, Rust unit tests,
  JUnit.
- `just review` — review gate (plan 06 §2): fmt, clippy, `cargo machete`, the
  `#[allow]`-grep, Spotless, Checkstyle.
- Native resolution order: system property `laminardb.native.path` (absolute
  file), then `java.library.path` via `System.loadLibrary("laminar_java")`.

## Binding invariants

- Native handles are `Box::into_raw` peers, freed exactly once; frees are
  NULL-tolerant and idempotent. Java wrappers null their `long` under a lock on
  close and guard every call — double-close and use-after-close must throw
  `LaminarConnectionException` (code 101), never crash.
- `LaminarConnection` is thread-safe: the Java-side lock guards handle
  lifetime; the native `Mutex<Option<T>>` serializes core calls — perform the
  state check and the core operation under one lock guard; dropping and
  reacquiring creates close races (a Python-binding lesson that carries over).
- Every native failure routes through `error.rs`'s `ApiError` → exception
  mapping with class-by-range, message verbatim, and the numeric code via the
  `(String, int)` constructor. Never panic across the JNI boundary; swallow
  only secondary errors when an exception is already pending.
- One process-global Tokio runtime, created on first connection, *entered*
  around blocking core calls (the core spawns background tokio tasks).
  INVARIANT (core v0.30.0, verified in `api/connection/mod.rs` and
  `api/subscription.rs`): blocking named-stream `subscribe()` and
  `next_frame()` reject being called inside a runtime context — subscription
  paths (plan 03) must stay outside the enter guard or use `spawn_blocking`.
- `JNI_OnLoad` only caches the `JavaVM` — no I/O, no engine construction.
- Arrow crosses via the C Data Interface (`org.apache.arrow.c` ↔
  `arrow::ffi`) with ownership moving per the release-callback contract; the
  ABI is stable so arrow-java and arrow-rs versions do not need to match.
  Callbacks cross the boundary per batch (or per barrier), never per row.
- Java floor 17. No `unwrap`/`expect` on user-, network-, or config-controlled
  data; flat control flow; visible termination on every loop.

## Making changes

1. Implement the Rust module and the Java layer it backs, keeping both in sync
   — every `io.laminardb.internal.Native` method must have a Java caller, and
   every public member a test and a doc reference (plan 06 §6).
2. Add focused JUnit coverage through the public API only: crossing fidelity,
   error mapping, lifecycle, threading, leak accounting — never duplicated core
   SQL semantics (plan 06 §7).
3. Run `just verify` and `just review`; both must be green before any task is
   done. Record spikes and findings in the plan's own fill-in sections.
4. Conventional Commits; no AI/assistant attribution, no `Co-Authored-By`
   trailers, no tool-session metadata. Stay in phase scope — no speculative
   natives or config knobs without a same-phase caller.
