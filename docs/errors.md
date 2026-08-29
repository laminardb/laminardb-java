# Error codes and exception mapping

Every native failure maps to an unchecked subclass of
`io.laminardb.LaminarException` carrying the core's numeric `ApiError` code
via `getCode()`. The Rust-side mapping lives in `src/error.rs`; a coverage
test there fails the build when the pinned core adds codes.

| Code(s) | Meaning | Java class |
|---|---|---|
| 100 | `CONNECTION_FAILED` | `LaminarConnectionException` |
| 101 | `CONNECTION_CLOSED` | `LaminarConnectionException` |
| 102 | `CONNECTION_IN_USE` | `LaminarConnectionException` |
| 200 | `TABLE_NOT_FOUND` | `LaminarSchemaException` |
| 201 | `TABLE_EXISTS` | `LaminarSchemaException` |
| 202 | `SCHEMA_MISMATCH` | `LaminarSchemaException` |
| 203 | `INVALID_SCHEMA` | `LaminarSchemaException` |
| 300 | `INGESTION_FAILED` | `LaminarIngestionException` |
| 301 | `WRITER_CLOSED` | `LaminarIngestionException` |
| 302 | `BATCH_SCHEMA_MISMATCH` | `LaminarIngestionException` |
| 400 | `QUERY_FAILED` | `LaminarQueryException` |
| 401 | `SQL_PARSE_ERROR` | `LaminarQueryException` |
| 402 | `QUERY_CANCELLED` | `LaminarQueryException` |
| 500 | `SUBSCRIPTION_FAILED` | `LaminarSubscriptionException` (Phase 2) |
| 501 | `SUBSCRIPTION_CLOSED` | `LaminarSubscriptionException` (Phase 2) |
| 502 | `SUBSCRIPTION_TIMEOUT` | `LaminarSubscriptionException` (Phase 2) |
| 900 | `INTERNAL_ERROR` (incl. native panics) | `LaminarInternalException` |
| 901 | `SHUTDOWN` | `LaminarShutdownException` |
| other | unknown/future codes | `LaminarException` |

## Pin-time observed behaviors (v0.30.0)

These are engine behaviors the binding surfaces verbatim, recorded so they
surprise nobody:

- Duplicate `CREATE SOURCE` reports **400** with an "already exists" message
  (the SQL path), not 201.
- Materialized `query()` of streaming sources is not the read path for source
  data at this pin; use subscriptions (Phase 2). Tables and inline SQL query
  normally.
- Source-only pipelines (no derived stream) run no checkpoint coordinator;
  `checkpoint()` fails with 900 "call start() first".
