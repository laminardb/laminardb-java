# Building laminardb-java

## Prerequisites

- Rust stable via rustup with `rustfmt` + `clippy` components
  (`rust-toolchain.toml` pins the toolchain selection).
- JDK 17+ (Temurin recommended), Maven 3.9+, `just`, and `cargo-machete`
  (`cargo install cargo-machete`).
- macOS: `brew install just maven openjdk@17` covers the last three.

## Everyday commands

- `just build` — build the cdylib and stage it under `target-native/debug/`.
- `just test` — build + run the JUnit suite.
- `just verify` — correctness gate: fmt, clippy `-D warnings`, Rust unit
  tests, JUnit.
- `just review` — review gate (plan 06 §2): fmt, clippy, `cargo machete`,
  the `#[allow]`-grep, Spotless, Checkstyle, SpotBugs, and the JaCoCo
  zero-coverage rule.
- `just clean`.

Maven never invokes cargo: `just` owns the orchestration; the pom wires
`java.library.path` to the staged cdylib via the `laminardb.native.dir`
property. `mvn test` works standalone once `just build` has staged the
library. JDK 17+ needs `--add-opens java.base/java.nio=ALL-UNNAMED` for
arrow-java allocation (already in the surefire argLine).

## Pinned core policy (D4)

`Cargo.toml` pins the core with an **exact git tag** — never a branch —
(currently `v0.30.0`), resolved directly from GitHub; no sibling checkout is
needed. The binding version tracks the core version. When a signature in the
plans disagrees with the pinned core, fix the plan in the same PR that proves
it wrong.

## Layout

- `src/*.rs` — the JNI cdylib (modules per plan 02 §2).
- `src/main/java/io/laminardb` — public API; `.../internal` — the native seam.
- `src/test/java` — JUnit suite (quickstart + matrix per plan 02 §6).
- `docs/plans` — the implementation plan series; `docs/reviews` — phase
  review records.
