# Session seed prompt — laminardb-java implementation

Copy everything below the line into a fresh session opened in this repository.
For later phases, change only the phase number, the entry plan, and the exit
criteria line (each phase's exit criteria are at the end of its own plan).

---

You are starting implementation work in **laminardb-java** — the Java bindings
repository for LaminarDB, a Rust streaming database. This checkout currently contains
only the plan series; your session implements **Phase 0 (repo scaffold, build wiring,
CI)** and nothing beyond it.

**Read before writing any code, in this order:**

1. `README.md` — bootstrap state and plan index.
2. `docs/plans/00-overview-and-decisions.md` — architecture and decision records
   D1–D8. These are settled decisions; do not re-litigate them.
3. `docs/plans/01-phase0-scaffold-and-build.md` — your work plan. Execute tasks
   0.1 → 0.7 in order, ticking checkboxes in the plan file as you complete them.
4. `docs/plans/06-review-gates.md` — the review standard every change and phase exit
   is held to. Read `agents/code-review.md` too; it is the reviewer prompt you will
   use at phase exit.

**Authoritative references:**

- Core Rust engine: the main-repo checkout at `~/Source/laminardb` (read-only
  reference). The `api` feature of `crates/laminar-db` is the seam being bound. The
  pinned core git tag (set per the plan's Cargo.toml task) is authoritative over plan
  prose wherever they disagree — fix the plan in the same PR that proves it wrong.
- Python binding — invariant reference, never a code template to transliterate:
  https://github.com/laminardb/laminardb-python (`src/async_support.rs`,
  `src/error.rs`, `src/callback.rs`, `src/stream_subscription.rs`, its `AGENTS.md`).
- SQL surface: `~/Source/laminardb/docs/SQL_REFERENCE.md`.

**Working rules:**

- Stay in phase scope: no Phase 1 API surface, no subscriptions, no publishing, no
  speculative natives "while you're in there".
- Conventional Commits; no AI/assistant attribution, no `Co-Authored-By` trailers,
  no tool-session metadata in commits.
- Before declaring any task done: `cargo fmt`, `cargo clippy --all-targets --
  -D warnings`, and whatever Java gates are wired so far must pass (`just verify`
  and `just review` once Task 0.4 lands).
- Record spike results and verification findings in the plan's own fill-in sections
  (e.g. plan 01 "Spike results") — decisions live in the plans, not in chat memory.
- If you hit a genuine blocker or a decision only the maintainer can make, stop and
  report it; do not silently widen scope or weaken a gate to proceed.

**Phase exit (from plan 01):** CI green on Linux x86_64 and macOS aarch64 from clean
checkouts; `just review` wired for Phase-0 scope; `agents/code-review.md` committed;
a full-tree review pass against plan 06 recorded in `docs/reviews/phase0-<date>.md`
with zero open REQUEST CHANGES findings. When Phase 0 exits, set plan 01's header to
`Status: Implemented (<date>)` and stop — do not roll Phase 1 into this session.

**Environment:** macOS arm64 dev machine. Expected toolchain: Rust stable via rustup
(rustfmt + clippy components), JDK 17+, Maven, `just`. The pinned core builds with
`--features api` only and should need no system packages; if the build proves
otherwise, record it in plan 01 Task 0.6 and mirror the fix in CI.

Begin with plan 01, Task 0.1.
