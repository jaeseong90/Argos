# SQL templates of the query API

The REST API (WO-23, docs/14) reads ClickHouse only through the templates in this directory. Each
file `sql/<path>.sql` is one statement named `<path>` (for example `dashboard/app_series.1m`). The
server reads and checks every file when it starts (`io.argos.server.storage.query.ReaderQueries`);
a file that breaks a rule below fails the start and the unit test `ReaderQueryCatalogTest`.

The SQL reference is `test/storage/queries/Q01..Q11.sql` (docs/13 section 6.3): the same tables,
columns (C-17, C-67) and functions (`quantilesDDMerge(0.01, 0.5, 0.95, 0.99)` on the rollup states,
C-53; 15-bucket estimates for SQL and outgoing calls, C-14). In `quantilesDD*` the first parameter
(0.01) is the relative accuracy, not a level: the result array is `[p50, p95, p99]` (three values;
an empty state gives NaN, read as null).

## Rules

| Rule | Why |
|---|---|
| One `SELECT` or `WITH ... SELECT`, no `;` | the executor sends one statement per request |
| No `SETTINGS` or `FORMAT` clause, no `INTO OUTFILE` | the executor appends `SETTINGS max_execution_time, max_rows_to_read, max_result_rows, max_memory_usage` (ADR-0007 C-64) and asks for `JSONCompactEachRowWithNamesAndTypes` (a `Settings['x']` column of `system.query_log` is fine) |
| No `UNION` outside parentheses | a trailing `SETTINGS` clause must apply to the whole statement: put a union inside `FROM (...)` |
| Values only as query parameters `{name:Type}` | request values never enter the SQL text (SEC-76); the executor binds them from `QueryParams` (`param_<name>`) and refuses a statement with a declared parameter that has no value |
| Identifiers only as `{db}`, `{p}`, `{rollup:<table>}` | rendered from `argos.clickhouse.database`, `argos.clickhouse.table-prefix` and the PostgreSQL setting `rollup_alias` (13 section 13.2 step 3) |
| Tables as `{db}.{p}<raw table>` or `{db}.{p}{rollup:<rollup table>}` | a rollup table named directly would bypass the alias of a rebuilt rollup |
| An exact `LIMIT` wherever a row cap applies | `max_result_rows` with `result_overflow_mode = break` stops only at a block boundary (C-64) |
| `FixedString` and `IPv6` converted in SQL (`hex(trace_id)`, `toString(remote_ip)`) | the result reader converts numbers, strings, times, arrays and maps only |
| Parameter names `from_sN`, `to_sN` are reserved | segment bounds of segmented queries |

Comment lines (`--`) are documentation and are not sent. The directive `-- @tables xlog, app_stat_1m`
lists the tables a template reads (it matches the `@ArgosSource` of the endpoint).

## Parameters

Declare every value with its ClickHouse type and set it with the matching `QueryParams` setter:

| SQL | `QueryParams` |
|---|---|
| `{app:UInt32}` | `uint32("app", appId)` |
| `{tx:UInt64}` | `uint64("tx", txId)` (64 bits, unsigned decimal) |
| `{from:DateTime64(3, 'UTC')}` | `dateTime64("from", instant)` |
| `{minute_from:DateTime('UTC')}` | `dateTime("minute_from", instant)` |
| `{uid:String}` | `string("uid", value)` |
| `{services:Array(UInt64)}` | `uint64Array("services", ids)` |
| `{names:Array(String)}` | `stringArray("names", names)` |
| `{sort:Identifier}` | `identifier("sort", column)`: a column chosen from an allow list, never request text |

## Segmented queries (raw + 1m + 1h in one statement)

A time range planned by `SourceSelector` (14 section 3) can span several storage levels: raw rows
for the last 2 minutes and the partial minutes at unaligned edges, minute rollups, hour rollups for
full hours. A segmented query reads all of them in one statement:

- an outer template with the placeholder `{segments}` once, for example
  `SELECT b, sum(c) AS cnt, quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q) AS q FROM ({segments}) GROUP BY b ORDER BY b`;
- one fragment per level (`<name>.raw`, `<name>.1m`, `<name>.1h`) with the same result columns,
  each restricted by `{from:DateTime64(3, 'UTC')}` and `{to:DateTime64(3, 'UTC')}` (or `DateTime('UTC')`);
  partial aggregates only: `count()`/`sum(count)`, sums, `max`, and the percentile states
  `quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms)` (raw) or
  `quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed)` (rollups) that the outer template merges.

The executor puts `SELECT * FROM (<fragment of segment i>)` for every segment into a `UNION ALL`,
renames `from`/`to` of segment `i` to `from_si`/`to_si` and binds them to the segment bounds. Buckets
use `toStartOfInterval(<time column>, toIntervalSecond({step_seconds:UInt32}))`, the grid of
`SourcePlan.firstBucket()`. The raw fragment buckets `toDateTime(ts)` so that the bucket column has
the same type (`DateTime('UTC')`) as the `minute`/`hour` columns of the rollups; every fragment
returns the same column types (the `UNION ALL` refuses others). The integration test templates in
`server/src/integrationTest/resources/sqltest/series.*.sql` are a complete example.
