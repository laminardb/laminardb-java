# Changelog

## [0.30.0-alpha]
- Full embedded API: open (default/path/config), execute with `ExecuteResult`,
  materialized `query`, streaming `stream`, map/Arrow `insert`, `Writer` with
  event-time watermarks, start/checkpoint/shutdown, schema and catalog
  helpers.
- Complete exception hierarchy mapped from the core's numeric codes, with a
  Rust-side coverage test pinning the mapping to the core's `codes` module.
- Arrow C Data Interface data plane: zero-copy Rust→Java batches, verified
  import-copy Java→Rust (arrow-java releases exported buffers via JNI upcall;
  copying keeps releases on the calling thread).
- Friendly-row conversion (`List<Map>` ↔ Arrow) with UTF-8 and timestamp
  handling; lazy `ArrowBatch` imports over the process-wide allocator.
- Review gates per plan 06: SpotBugs and the JaCoCo zero-coverage rule join
  fmt/clippy/machete/allows-grep/Spotless/Checkstyle in `just review`.
- Bundled-natives `NativeLoader` (plan 04 §2) with SHA-256-verified
  extraction; release workflow for the four Phase-1 platforms.
- Documentation: quickstart, stateful joins walkthrough, threading, errors,
  build, benchmarks.
