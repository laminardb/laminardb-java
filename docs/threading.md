# Threading model

## Thread-safety contracts

| Class | Contract |
|---|---|
| `LaminarDB` | Stateless entry points; the shared allocator is thread-safe. |
| `LaminarConnection` | Thread-safe. One internal lock guards the native handle's lifetime and serializes core calls — the same contract as the Python binding. |
| `Writer`, `QueryStream`, `ArrowBatch`, `ExecuteResult`, `QueryResult`, `LaminarConfig` | Single-owner. Calls from multiple threads must be externally serialized. |
| Exceptions, `Schema`, `FieldInfo` | Immutable. |

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
