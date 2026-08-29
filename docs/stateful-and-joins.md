# Stateful processing and joins

LaminarDB is a streaming database: sources flow through derived streams with
event-time semantics driven by **watermarks**. This walkthrough shows the
Java-side patterns against the SQL the pinned core (v0.30.0) actually admits.
All limits below are the engine's fail-closed rules, surfaced verbatim.

## Watermarks drive everything

A source declares its watermark inline; writers advance it from Java:

```java
conn.execute("""
    CREATE SOURCE trades (
        trade_id BIGINT NOT NULL, symbol VARCHAR NOT NULL, price DOUBLE NOT NULL,
        ts TIMESTAMP NOT NULL,
        WATERMARK FOR ts AS ts - INTERVAL '5' SECOND)""");

try (Writer trades = conn.writer("trades")) {
    trades.write(rows);
    trades.watermark(Instant.parse("2026-08-29T10:00:07Z").toEpochMilli());
}
```

Rows become **final** when a watermark closes their window or match interval —
nothing is emitted early, and Java-side `watermark()` calls are what move time
forward in embedded mode.

## Bounded event-time interval join

Trades matched to orders within 10 seconds. Every projection aliased, every
column left/right qualified, the interval predicate directional
(`right.ts BETWEEN left.ts AND left.ts + bound`):

```sql
CREATE STREAM matched AS
SELECT t.trade_id AS trade_id, o.order_id AS order_id,
       t.price - o.price AS price_diff
FROM trades t
INNER JOIN orders o
ON t.symbol = o.symbol
AND o.ts BETWEEN t.ts AND t.ts + INTERVAL '10' SECOND;
```

Outer and anti rows become final only when the opposite input's watermark
closes their possible match interval.

## Temporal ASOF join (`FOR SYSTEM_TIME AS OF`)

Each trade enriched with the latest quote at its event time:

```sql
CREATE SOURCE quotes (symbol VARCHAR PRIMARY KEY, price DOUBLE, ts TIMESTAMP NOT NULL,
    WATERMARK FOR ts AS ts - INTERVAL '5' SECOND);

CREATE STREAM valued_trades AS
SELECT t.trade_id AS trade_id, q.price AS quote_price
FROM trades t
LEFT JOIN quotes FOR SYSTEM_TIME AS OF t.ts AS q
  ON t.symbol = q.symbol;
```

Two pin-time requirements the SQL alone will tell you about if you forget
them: the right source must declare its equality key as a **PRIMARY KEY**, and
temporal joins require the `temporal_join_idle_history_retention` config. In
the current Phase-1 feature set (core `api` only, no connectors) temporal
joins additionally require **connector-backed sources** — plain embedded
sources are refused ("must be a direct configured source"). This path unlocks
with the connector-matrix decision in Phase 2 (plan 03 §6).

## TUMBLE windows

Fixed windows; `tumble()` returns the window-start timestamp; window close is
watermark-driven:

```sql
CREATE STREAM totals AS
SELECT symbol, count(*) AS trade_count
FROM trades
GROUP BY symbol, tumble(ts, INTERVAL '10' SECOND);
```

## Fail-closed limits (verbatim from the core)

- No cross, unbounded, general non-equality, intermediate-input, or multi-way
  joins on the bounded stream-stream path.
- No fused join + `GROUP BY` in one statement — name the join output and
  create a separate keyed aggregate stage.
- `ORDER BY` without `LIMIT` is not supported on unbounded streams.
- Equality keys are ordered `VARCHAR`/`BIGINT` columns; types must match at
  each position; SQL `NULL` keys do not match.

## Reading stream output

Stream data is consumed via **subscriptions**, which land in Phase 2 (plan
03). Until then, `query()` reads TABLEs and bounded/inline SQL; the
`QuickstartIT` test exercises everything on this page that Phase 1 can
observe end-to-end.
