# Benchmarks

## Phase 2 additions (2026-08-30, debug build, macOS aarch64)

- Spike B (callback delivery, rapid single-row writes): ~544 µs/row
  write-side; broadcast lag-drop observed under consumer-slower-than-producer
  stress (machine-dependent; e.g. 468/500 and 181/500 on two runs) — batched
  production is the contract, the ratio is recorded, never gated.

- The JMH module (`benchmarks/`, `just bench`) carries the plan 03 §5 suite:
  insert throughput, query roundtrip, poll- and callback-subscription
  overhead, map conversion, open/close cycles. Nightly CI runs it; numbers
  land here per environment.

Phase 1 records manual, non-CI-blocking sanity numbers on the dev machine
(macOS aarch64, JDK 17.0.20, Rust 1.97.1 debug-build cdylib — release natives
ship with the Phase 1 artifact). The JMH suite lands in Phase 2 (plan 03 §5).

| Workload | Result (2026-08-29, debug build) | Gate (plan 02 §7) |
|---|---|---|
| Map-insert, 10k rows (conversion included) | ~0.16 s end-to-end in-suite | informational |
| Zero-copy-path insert (post-copy, see note), 100k rows | ~15 ms in-suite | ≥ 500k rows/s target for release natives |
| VALUES query roundtrip (3 rows) | sub-millisecond in-suite | < 1 ms target |

Note: at the pin, Java→Rust batches are deep-copied on import (see
`src/arrow_jni.rs` `import_batch`): arrow-java releases exported buffers via a
JNI upcall, so a zero-copy handoff would release JVM memory from arbitrary
engine threads. Rust→Java remains zero-copy. The Phase 2 JMH suite will
re-measure against the 500k rows/s gate on release natives and record the
copy's share.
