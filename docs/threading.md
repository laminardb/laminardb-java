# Threading model

## Thread-safety contracts

| Class | Contract |
|---|---|
| `LaminarDB` | Stateless entry points; the shared allocator is thread-safe. |
| `LaminarConnection` | Thread-safe. One internal lock guards the native handle's lifetime and serializes core calls — the same contract as the Python binding. |
| `Writer`, `QueryStream`, `ArrowBatch`, `ExecuteResult`, `QueryResult`, `LaminarConfig`, `StreamSubscription` | Single-owner. Calls from multiple threads must be externally serialized. |
| `CallbackSubscription` | Single-owner; deliveries run on its dedicated worker thread. Query-backed workers emit no exhaustion signal at this pin — `cancel()` ends them. |
| `SubscriptionListener` | Invoked on the subscription's worker thread; throwing from `onBatch` ends the subscription with one `onError` then `onClose`. |
| Exceptions, `Schema`, `FieldInfo`, `Frame` | Immutable. |

## Blocking

Every public method that crosses JNI is blocking and may be long-running.
Javadocs say so per method. On **virtual threads** (Java 21+), a blocking JNI
call pins the carrier thread for its duration — acceptable for a database
driver workload; the escape hatch is the async adapter API (Phase 2, plan 03
§3). On platform threads there is no pinning concern.

## Engine threads

The native library keeps one process-wide Tokio runtime, created on first
`open()` and entered around blocking core calls. Engine background tasks run
on that runtime's worker threads. `close()`/`shutdown()` stop the engine for
that connection; the runtime itself is process-global by design (mirrors the
Python binding).

## Subscription delivery guarantees (core v0.30.0)

Named-stream subscriptions are broadcast-based: a consumer that falls behind
receives a **subscription error** ("subscription fell behind by N entries",
code 500) — the subscription then terminates; `nextFrame()` throws
`LaminarSubscriptionException` and callback listeners receive `onError`
followed by `onClose`. Write in batches — one JNI crossing per batch (D7) —
and keep consumers draining: a callback listener that stalls its worker
thread loses batches and then ends its subscription with an error.

At most **64 concurrent callback subscriptions** exist per process
(`LaminarSubscriptionException` code 500 beyond); each runs on a dedicated
worker thread with adaptive poll backoff (0.5–5 ms).

## Closing with resources still open

Closing a connection with an open `Writer` does not crash and the connection
fully closes — but the open writer's undrained batches **keep their backing
JVM buffers pinned until the writer itself is closed** (bounded: one buffer
set per written batch). Closing the writer — even after the connection has
closed — releases everything. Always close writers explicitly; `close()`
flushes, closes the native writer, and releases every buffer set the engine
held for it.

## Arrow memory

`LaminarDB.defaultAllocator()` is the process-wide Arrow allocator used for
lazy batch imports. Batches you fail to `close()` leak allocator memory until
the allocator closes — use try-with-resources everywhere. The allocator's
buffers are native memory: size long-lived connections' consumption
accordingly.

## JVM flags

JDK 17+ requires `--add-opens java.base/java.nio=ALL-UNNAMED` for arrow-java's
allocation managers (netty or unsafe alike). On JDK 24+ the JVM additionally
prints a native-access warning under JEP 472; silence it with
`--enable-native-access=ALL-UNNAMED`. Functionality is unaffected.
