# laminardb-java — implementation plan series

Status: **Phase 0 implemented (2026-08-29); Phases 1–3 planned** · Owner: LaminarDB team

Java bindings for the Rust [LaminarDB](https://github.com/laminardb/laminardb) streaming
database. Phase 0 (repo scaffold, build wiring, CI) is implemented: a Rust JNI cdylib
over the core's `api` feature (pinned to git tag `v0.30.0`), a minimal `io.laminardb`
API, one-command build/test/verify/review via `just`, and a two-OS CI matrix.

## Building and testing

Requires Rust stable (rustfmt + clippy), JDK 17+, Maven, `just`, and `cargo-machete`
(`cargo install cargo-machete`). Then: `just verify` (correctness gate) and `just
review` (review gate). See `AGENTS.md` for the full operating context.

## Plan index

| # | Plan | Phase | Depends on |
|---|---|---|---|
| 1 | [00-overview-and-decisions.md](docs/plans/00-overview-and-decisions.md) | read first | — |
| 2 | [01-phase0-scaffold-and-build.md](docs/plans/01-phase0-scaffold-and-build.md) | Phase 0 — scaffold, build wiring, CI | 00 |
| 3 | [02-phase1-embedded-mvp.md](docs/plans/02-phase1-embedded-mvp.md) | Phase 1 — embedded MVP, first Maven artifact | 01 |
| 4 | [03-phase2-subscriptions-and-hardening.md](docs/plans/03-phase2-subscriptions-and-hardening.md) | Phase 2 — subscriptions, async, hardening | 02 |
| 5 | [04-release-engineering.md](docs/plans/04-release-engineering.md) | Cross-phase — packaging, publishing, versioning | read before finishing 01 |
| 6 | [05-phase3-distributed-future.md](docs/plans/05-phase3-distributed-future.md) | Phase 3 — future: server driver, FFM backend | 02 |
| 7 | [06-review-gates.md](docs/plans/06-review-gates.md) | Cross-phase — review gates: structure, slop, docs, dead code, tests | every PR + every phase exit |

Keep plan status checkboxes updated as tasks complete; when a phase ships, mark its
plan header `Status: Implemented (<date>, <release>)` and leave it in place as a record.

## Reference material

- Core repository: the `api` feature of `crates/laminar-db` (see plan 00 appendix for the
  verified surface as of core `v0.30.x`; the pinned core tag is always authoritative).
- Python binding (architectural mirror): https://github.com/laminardb/laminardb-python —
  especially `src/error.rs`, `src/async_support.rs`, `src/callback.rs`,
  `src/stream_subscription.rs`, and its `AGENTS.md` binding invariants.
- Existence proof for the JNI + Arrow pattern: https://github.com/apache/datafusion-java.
- Reviewer prompt: paste [agents/code-review.md](agents/code-review.md) as the
  instructions of any reviewer (human or agent) — it enforces
  [docs/plans/06-review-gates.md](docs/plans/06-review-gates.md).

## License

Apache-2.0, matching the core repository.
