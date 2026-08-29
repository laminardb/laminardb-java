# Plan 01 — Phase 0: repo scaffold, build wiring, CI

Status: **Implemented (2026-08-29)** · Prerequisite: plan 00 (decisions D1–D8)
Exit: `just verify` builds the cdylib + Java jar and runs the JUnit smoke test green in
CI on Linux x86_64 and macOS aarch64.

## Goal

A skeleton repository where a Rust JNI crate over `laminar_db::api` and a minimal Java
API build and test together with one command, exercised by CI. No Maven Central
publishing yet (plan 04); no subscriptions yet (plan 03). The point is to de-risk the
three integration seams — cargo↔Maven, JNI↔`api`, Java↔Arrow — while the surface is
tiny.

## Task 0.1 — Repository bootstrap

- [x] Add the GitHub remote (`git remote add origin git@github.com:laminardb/laminardb-java.git`,
      per the repo `README.md`) — this checkout is already the repository root; keep
      `docs/plans/` as the living backlog. (Added as the HTTPS URL — the checkout
      authenticates via `gh`, whose configured git protocol is HTTPS.)
- [x] `LICENSE` — Apache-2.0 (copy from the core repo).
- [x] `.gitignore` — `/target/`, `target-native/`, `.idea/`, `*.iml`, `.DS_Store`,
      `HeapDumpOnOutOfMemoryError*.hprof`? (no — keep it minimal; the standard
      Rust+Maven/JVM sets). (Plus `/target-rust/`: `.cargo/config.toml` points Cargo at
      `target-rust/` so Cargo and Maven build trees — both `target/` by default — do not
      clobber each other's caches.)
- [x] `rust-toolchain.toml` — mirror the core: `channel = "stable"`, components
      `rustfmt`, `clippy`.
- [x] `AGENTS.md` — operating context for coding agents in this repo: the two-layer
      architecture, the binding invariants lifted from plan 00 §5, build/test commands,
      and the "pinned core tag is authoritative" rule. Model it on
      `laminardb-python/AGENTS.md`.
- [x] `justfile` (or `Makefile` — pick one, `just` preferred; the core repo uses `Justfile`)
      with at least: `build`, `test`, `verify` (= fmt + clippy + rust unit tests + mvn test),
      `review` (= the plan 06 §2 tooling set: fmt/clippy/machete/allows-grep, Spotless,
      Checkstyle), `clean`.

## Task 0.2 — Rust crate scaffold

- [x] `Cargo.toml` (as executed; deltas from the sketch below are the corrections
      the pin forced — newest core tag at execution time is **v0.30.0**, the core
      workspace pins `arrow = "=58.4.0"`, and the runtime module needs `tokio`;
      `once_cell` (std `OnceLock` suffices) and `thiserror` (no local error enums —
      the core's `ApiError` is reused directly) would fail `cargo machete`, so both
      were dropped):

```toml
[package]
name = "laminar-java"
version = "0.30.0"          # tracks the pinned core tag; see plan 04 §3 before first release
edition = "2021"            # matches the core workspace edition at the pin
rust-version = "1.95"       # core workspace floor at the pin
license = "Apache-2.0"

[lib]
crate-type = ["cdylib"]
name = "laminar_java"       # liblaminar_java.so / liblaminar_java.dylib / laminar_java.dll

[dependencies]
jni = "0.22"                # no `invocation` feature: we never create a JVM from Rust
laminar-db = { git = "https://github.com/laminardb/laminardb", tag = "v0.30.0", default-features = false, features = ["api"] }
arrow = { version = "=58.4.0", default-features = false, features = ["ffi"] }  # match the core's workspace pin exactly
tokio = { version = "1", features = ["rt-multi-thread", "time"] }
parking_lot = "0.12"

[profile.release]
lto = true
codegen-units = 1
strip = "symbols"
```

      Pin policy (D4): **git tag, never branch**. Pinned `v0.30.0` — the newest core
      tag at execution time (2026-08-29); resolves from GitHub directly, no sibling
      clone needed. If/when `laminar-db` on crates.io demonstrably publishes the
      `api` feature at parity, switching to a crates.io dep is allowed and preferred;
      record the switch in this plan.

- [x] `src/lib.rs` — module root only: `mod error; mod handle; mod runtime; mod connection;
      mod arrow_jni;` plus `#[no_mangle] pub extern "C" fn JNI_OnLoad(vm: JavaVM, ...)`
      that caches the `JavaVM` in a `static OnceLock<JavaVM>` and returns `JNI_VERSION_1_8`.
      No work beyond caching in `JNI_OnLoad` (no I/O, no engine construction).
      (jni 0.22 idiom: `JNI_OnLoad` takes `*mut sys::JavaVM` and wraps via
      `JavaVM::from_raw` — the wrapper type itself is not FFI-safe as a parameter.)

- [x] `src/handle.rs` — the peer-pointer discipline used by every other module:

```rust
/// Native handles are `Box::into_raw` peers. Every `*_from_java` returns an owned
/// pointer; every free is NULL-tolerant and idempotent. INVARIANT: a handle is
/// freed exactly once; use-after-free is impossible from Java because the Java
/// wrapper nulls its `long` on close and guards every call.
pub(crate) struct ConnHandle(pub(crate) Mutex<Option<laminar_db::api::Connection>>);
```

      (Interior is `Option<Connection>` so `close(self)` can take the connection out
      exactly once; the `Arc` from the sketch was dropped — each handle has a single
      owner. Phase 0's guard helper is `fn conn<'a>(ptr) -> Result<&'a ConnHandle,
      Failure>` mapping null → `LaminarConnectionException` 101; with jni 0.22 the
      throwing happens via the error policy below, so the helper no longer needs
      `env`.)

- [x] `src/error.rs` — the single `ApiError → Java exception` mapping point:
      `throw_api_error(env, err)` maps code ranges to class names (100–199 →
      `LaminarConnectionException`, else `LaminarException`), message verbatim, code
      via the `(String, int)` ctor; classes cached lazily as global refs in one
      `OnceLock`. jni 0.22 shape: every native method runs its body through
      `EnvUnowned::with_env` and resolves with a custom `ErrorPolicy`
      (`ThrowLaminar`) that maps `Failure::{Api, Jni}` onto `throw_api_error` and
      converts Rust panics into `LaminarException` code 900 — panic-safety across
      the boundary comes from the policy, not per-call discipline. In Phase 0,
      `LaminarException` + `LaminarConnectionException` suffice.

- [x] `src/runtime.rs` — process-global Tokio runtime, modeled on
      `laminardb-python/src/async_support.rs`:

```rust
static RUNTIME: OnceLock<Option<Runtime>> = OnceLock::new();
pub(crate) fn runtime() -> Result<&'static Runtime, ApiError> { ... }
```

      Rules (INVARIANT, lifted from the Python repo's hard-won lessons):
      - one multi-thread runtime for the process, created on first connection;
      - blocking `api` calls run on the calling Java thread — the runtime must be
        *entered* (`.enter()` guard) around them because the core spawns background
        tokio tasks;
      - documented exception from the Python repo to carry over: some named-stream
        subscribe/next paths reject being called *inside* a runtime — when the pinned
        core shows that behavior, run those paths on `spawn_blocking` instead.
        **Verified at the pin** — see §Spike results.

- [x] `src/connection.rs` — Phase 0 surface only, four methods
      (see plan 02 §2 for the full naming scheme):
      `Java_io_laminardb_internal_Native_openDefault`, `executeSql`, `closeConnection`,
      `version`. `open` = `api::Connection::open()` inside `runtime().enter()`.
      `executeSql` returns `void`: the sketch's boxed `ExecuteOutcome {kind, detail}`
      had no Phase-0 consumer for kind or detail (dead code per plan 06 §6), and
      accessors would have broken the four-method contract either way — the
      boxed-handle pattern lands in Phase 1 as `execute`/`executeKind`/`executeFree`
      (plan 02 §2). `execute` drops the core `ExecuteResult` for now.
      `close` = `close(self)` semantics: the handle must be consumed exactly once —
      take `Option` out of the mutex interior and drop.

## Task 0.3 — Java side scaffold

- [x] `pom.xml` — Maven (chosen over Gradle: library-first, mirrors the datafusion-java
      precedent, simplest CI): `groupId io.laminardb`, `artifactId laminardb`,
      `maven.compiler.release 17`, JUnit 5, AssertJ. Dependency policy: the *only*
      compile-scope dependencies at Phase 0 are `org.apache.arrow:arrow-c-data` and
      `org.apache.arrow:arrow-vector` (+ transitive `arrow-memory-*`); everything else
      test-scope — including `arrow-memory-unsafe`, which arrow-java 19 requires at
      runtime as an allocation-manager backend (with `--add-opens
      java.base/java.nio=ALL-UNNAMED` in the surefire argLine). No shading (plan 04
      revisits relocation if ever needed).
- [x] `io.laminardb.internal.Native` — (correction: `public final` class, not
      package-private — `io.laminardb` must call it across the package boundary and
      Java has no cross-package privacy; the *package* is the isolation boundary per
      plan 06 §3 "under internal"), `static {}` block loads the library via
      `NativeLoader.load()`; declares only the four native methods of Phase 0. All
      methods take/return `long` handles and primitive types.
- [x] `io.laminardb.internal.NativeLoader` — Phase 0 version: resolve from
      (a) system property `laminardb.native.path` (absolute file), then
      (b) `java.library.path` via `System.loadLibrary("laminar_java")`.
      The bundled-extraction path (jar `/natives/...`) is added in Phase 1 per plan 04 §2;
      keep the resolution order property-first so it stays testable.
- [x] `io.laminardb.LaminarException extends RuntimeException` — `private final int code;`
      ctor `(String message, int code)`, `getCode()`.
- [x] `io.laminardb.LaminarDB` — `public static LaminarConnection open()` (default
      in-memory) throwing the mapped exceptions, plus `public static String
      getVersion()` (the `version` native's caller — plan 02 §1 signature); the
      `io.laminardb.LaminarConnection implements AutoCloseable` — minimal:
      `execute(String sql)` (holds the lock across the native call so close cannot
      free a handle mid-execute), `close()` (idempotent: nulls the handle under a
      lock; second close is a no-op), `isClosed()`.

## Task 0.4 — Build wiring

- [x] `justfile` targets:
      - `build`: `cargo build` then copy `target-rust/debug/liblaminar_java.{so,dylib}` to
        `target-native/debug/` (one stable directory regardless of profile).
      - `test`: `build` + `mvn test` (the surefire argLine sets
        `-Djava.library.path=${laminardb.native.dir}` with a pom property defaulting
        to `target-native/debug` — surefire's `systemPropertyVariables` cannot carry
        `java.library.path` because the forked JVM reads it at startup, not after).
      - `verify`: `cargo fmt --check`, `cargo clippy -- -D warnings`, `cargo test`,
        then `test`.
- [x] `pom.xml` stays build-tool pure (no cargo invocation from Maven in Phase 0 —
      `just` owns the orchestration; a `rust-maven-plugin` evaluation is deferred to
      plan 04 and only for contributor convenience, never required).
- [x] CI can therefore run plain `just verify`.

## Task 0.5 — Spike A: Arrow C Data Interface roundtrip (do this before designing Phase 1's data path)

- [x] Prove the exact mechanism against the pinned arrow-rs/arrow-java pair with a
      `#[cfg(test)]` Rust test + a JUnit test:
      1. **Export (Rust→Java):** Java allocates `ArrowArray.allocateNew(allocator)` +
         `ArrowSchema.allocateNew(allocator)`, passes `memoryAddress()` of both as
         `jlong`; Rust writes via `arrow::ffi::to_ffi` (or `to_ffi` + schema export);
         Java imports with `org.apache.arrow.c.Data` into a `VectorSchemaRoot`; values
         verified; Java closes the root; no leaks (allocator accounting to zero).
      2. **Import (Java→Rust):** Java exports a `VectorSchemaRoot` via
         `Data.exportVectorSchemaRoot` into freshly allocated structs, passes addresses;
         Rust consumes via `arrow::ffi::from_ffi` (ownership moves — the Java-side
         structs must be dead afterwards, per the release-callback contract);
         RecordBatch contents verified in Rust.
      3. Record the exact arrow-java class/method names that worked (they drift
         between arrow-java versions; the ABI does not) in this plan's §Spike results.
- [x] Acceptance: roundtrip test green on Linux + macOS; a repeated-loop variant
      (10k iterations) shows stable allocator accounting (no leak, no double-free).
      (Green locally on macOS aarch64; Linux arm of the matrix runs in CI.)

## Task 0.6 — CI

- [x] `.github/workflows/ci.yml`, matrix: `ubuntu-latest` (x86_64) and `macos-latest`
      (aarch64). Jobs:
      1. `rust-lint`: fmt + clippy `-D warnings`.
      2. `review`: `just review` — the plan 06 §2 tooling gates for Phase-0 scope
         (Spotless, Checkstyle, `cargo machete`, the allows-grep).
      3. `verify`: `just verify` (includes Rust unit tests + JUnit smoke).
- [x] Prereq system packages for the core's build (the Python repo's CI installs
      cmake/clang/openssl for connector features — Phase 0 enables **only** the `api`
      feature with no connectors, so expect none; add if the pin proves otherwise and
      record here). Confirmed: the `api`-only build needs no system packages beyond
      the Rust toolchain and a C/C++ toolchain (cc is present on both runners).
- [x] Branch protection on `main`: PR + green CI required.

## Task 0.7 — Smoke tests (JUnit)

- [x] `open()` → `isClosed()==false` → `execute("CREATE SOURCE t (a INT)")` succeeds →
      `close()` → `isClosed()==true`.
- [x] Double `close()` is a no-op; `execute` after close throws `LaminarConnectionException`
      with code 101 (`CONNECTION_CLOSED`).
- [x] `execute("CREATE SOURCE t (a INT)")` twice → code **400 (`QUERY_FAILED`)** with
      message "source 't' already exists" — **not** 201 as this plan originally
      claimed. Verified against the pinned v0.30.0 with a standalone probe: the SQL
      execute path surfaces duplicate DDL as a query error; the `TABLE_EXISTS` (201)
      mapping in `ApiError` is not what `Connection::execute` produces. Plan 02 §5's
      mapping table tests must assert against observed codes, not the constructor
      table.
- [x] Invalid SQL → code 401 (`SQL_PARSE_ERROR`), message surfaced.
- [x] Open/close in a loop 200× — no crash (leak soak comes properly in Phase 2).

## Acceptance checklist (Phase 0 exit)

- [x] `just verify` green locally on Linux or macOS dev machine. (macOS aarch64.)
- [x] CI green on both matrix OSes from a clean checkout (proves the pinned git-tag
      core dep resolves without manual sibling clones — the Python repo's ergonomic
      mistake, avoided). (Run 33250001085 at commit 44d6fdf: all six jobs green.)
- [x] Review gates from plan 06 §2 wired for Phase-0 scope (`just review` green);
      `agents/code-review.md` committed and referenced from `AGENTS.md`.
- [x] Phase-exit review pass per plan 06 §8 recorded in
      `docs/reviews/phase0-2026-08-29.md` with zero open REQUEST CHANGES findings.
      (First pass returned 5 findings — all resolved in commit 89dd091 and recorded
      there.)
- [x] Spike A documented (§ below) with working arrow-java API names.
- [x] `AGENTS.md` merged; plan checkboxes updated; conventional commit history.

## Spike results (recorded 2026-08-29, core v0.30.0 / arrow-rs =58.4.0 / arrow-java 19.0.0 / jni 0.22.4)

- **arrow-java version used: 19.0.0** (newest on Maven Central at execution time;
  versions independent of arrow-rs — the ABI is stable, per plan 00 D6).
  Test-scope `arrow-memory-unsafe` supplies the allocation manager; JDK 17 needs
  `--add-opens java.base/java.nio=ALL-UNNAMED` in the surefire argLine.
- **Export (Rust→Java) call sequence:**
  `ArrowArray.allocateNew(allocator)` + `ArrowSchema.allocateNew(allocator)` →
  `Spike.exportSampleBatch(array.memoryAddress(), schema.memoryAddress())` →
  `Data.importVectorSchemaRoot(allocator, array, schema, new CDataDictionaryProvider())`
  returns a fresh `VectorSchemaRoot`; verify values; closing the root (and the
  structs — the importer owns the release callbacks afterwards) is safe; allocator
  accounting returns to zero. Rust side: `StructArray::from(batch)` →
  `to_ffi(&struct_array.to_data())` → `ptr::write` both structs into the
  Java-provided addresses. Note `to_ffi` returns `(FFI_ArrowArray, FFI_ArrowSchema)`
  — array first — and takes `&ArrayData`, not `&dyn Array`.
- **Import (Java→Rust) call sequence:**
  build a `VectorSchemaRoot` (`new Field(name, new FieldType(nullable, type, null), null)`;
  the 2-arg Field/FieldType ctors are gone in 19.0.0) →
  `Data.exportVectorSchemaRoot(allocator, root, new CDataDictionaryProvider(), array, schema)`
  → `Spike.importBatch(addresses)`. **Ownership verified:** after Rust's
  `from_ffi` consumes the structs (via `ptr::read`), the Java wrappers must be
  marked dead with `array.markReleased(); schema.markReleased();` before close —
  closing them unmarked double-fires the release callback.
- **Roundtrip stability:** 10k-iteration export→import→close loop keeps
  `RootAllocator.getAllocatedMemory()` at zero throughout; the pure-Rust
  to_ffi/from_ffi roundtrip repeats 1k times without double-free.
- **Runtime-entry finding (Task 0.2, named-stream caveat): CONFIRMED, not moot.**
  At the pin, `api::Connection::subscribe()` and `ArrowSubscription::next_frame()`
  both begin with `if tokio::runtime::Handle::try_current().is_ok() { return Err(...) }`
  (verified in `crates/laminar-db/src/api/connection/mod.rs` and
  `api/subscription.rs` at tag v0.30.0; corroborated by
  `laminardb-python/AGENTS.md`). Phase 0's open/execute/close are unguarded and
  correct to run inside `.enter()`; Phase 2's subscription paths (plan 03) must
  keep those calls *outside* the enter guard or route them through
  `spawn_blocking`.
- **Additional pin findings:** duplicate `CREATE SOURCE` surfaces as code 400, not
  201 (Task 0.7 above); `ApiError::code()` on that path reports the SQL-layer
  code. `Connection::close(self)` consumes, validating the `Option`-take design.
