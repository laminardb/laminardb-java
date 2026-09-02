# Changelog

## [0.30.0-alpha]
- Named-stream subscriptions: framed poll access (`StreamSubscription`) with
  data batches and checkpoint barrier frames, timeout-bounded and try
  variants, and batch-only conveniences.
- Callback (push) subscriptions: `SubscriptionListener` delivery on a
  dedicated worker thread per subscription (bounded at 64), one JNI crossing
  per batch, listener-throw → single `onError` then `onClose`, bounded
  cancel-and-join (5 s).
- Async adapters: `LaminarConnection.queryAsync` on the shared blocking-safe
  daemon executor; bounded-lazy `QueryStream.streamBatches` with stream-close
  wiring.
- Watermark unit normalization: the Java API speaks epoch millis; the native
  layer converts to/from the core's column unit (µs at this pin).
- Hardening: nightly soak suite (10k subscribe/cancel cycles, callback
  cycles, 1k disk checkpoints, 8-thread torture with concurrent
  checkpoints), JMH benchmark module, Windows in the CI matrix,
  darwin-amd64 cross-build after GitHub retired Intel macOS runners.
- Documentation: threading contracts (subscription delivery guarantees,
  64-subscription cap, lag behavior), stateful-joins pin observations,
  benchmark numbers with environment notes.
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
