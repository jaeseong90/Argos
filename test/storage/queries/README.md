# test/storage/queries

The 20 queries and checks of the storage PoC (WO-03, P-4; docs/13 sections 6.3 and 15, docs/18 section 9), the
runner that measures them, the procedures behind Q14..Q20, a Markdown report generator, and the CI consistency
tests T-ST-030..033 and T-ST-053 against a real ClickHouse.

```
./gradlew :test:storage:queries:test              # no Docker: catalog rules, criteria, bucket estimate, encoding
./gradlew :test:storage:queries:integrationTest   # Docker, about 5 minutes: T-ST-030..033, T-ST-053, whole catalog
./gradlew :test:storage:queries:storageQueries -Pqueries.args="--volume <name> --memory-gib 5 --cold restart"
```

## Query files

`params.sql` and `Q01.sql`..`Q20.sql` in this directory are plain SQL with directive comments; every file can be
read and run by hand.

| Directive | Meaning |
|---|---|
| `@id`, `@title`, `@source` | Q number, title, design references (13 §6.3 (n), §15 Qn, requirement ids) |
| `@kind` | `timing` (Q1..Q11), `consistency` (Q12, Q13), `procedure` (Q14..Q20) |
| `@account` | `reader` (argos_reader, 13 §6.3) or `migrator` (nightly checks, DDL of the procedures) |
| `@criterion` | 13 §15 criterion: `warm_ms <= 1000`, `rows == 0`, procedure metrics, or `record` |
| `@settings` | per-query limits sent with the request (argos_reader may only lower them) |
| `@probe name` | a SELECT returning one row; its column aliases become parameters |
| `@optional reason` | (probes) the data may have no value: the variants that need it are `NOT_APPLICABLE`, not failed |
| `@variant name k=v` | a measured statement with literal parameters; `@expect rows >= 1` marks a broken lookup |
| `@step name` | a statement run by a procedure |

Placeholders: `{db}`, `{p}`, `{retention.*}` are rendered from the schema parameters (identifiers and
numbers only); `{name:Type}` are ClickHouse query parameters bound by the server from `param_<name>`, so values
never enter the SQL text. `params.sql` resolves the shared values once per run from the loaded data (range,
busiest application); each file's probes add its own (a transaction id, a trace id, a user id, an SQL id).

## Runner

`QueryRunner`: timing variants run once cold and three times warm; the criterion is judged on the median client
wall time of the warm runs. Every statement carries a query id, and `QueryLog` reads its system.query_log record
(server time, rows and bytes read, compressed bytes read (ProfileEvents `ReadCompressedBytes`: columns, marks and
skip index files), marks selected of the marks of the selected parts, skip index time, memory, CPU). Client timeout =
max_execution_time + 30 s. The machine CPU load is sampled every second and reported with every measurement (the PoC
machine is shared): a sample covers the second before it, so the load of a statement is the mean of the samples from
the first after its start to the first after its end, computed when the report is written (also for statements
shorter than a second).

Cold runs (`--cold`): none of the Argos accounts may run `SYSTEM DROP MARK CACHE` (SEC-76), so caches are emptied
from outside: `restart` (server restart on its named data volume), `restart+dropcache` (plus a privileged helper
container that drops the Docker host page cache; affects every container), `command:<shell>` (an operator command
on the dedicated host), or `none` (the first run, caches warm).

## Procedures

| Id | Class | What it measures |
|---|---|---|
| Q05/bloom | `BloomVariants` | xlog copies with bloom fpp 0.01/0.025 x GRANULARITY 1/4 (variant DDL built from the V001 literals, ADR-0007 C-55/C-56): index size, tx/trace/user/sql lookups without a range |
| Q11/cost | `SqlIdsCost` | disk cost of `sql_ids` and its bloom index per row and GB/day at 500 TPS (A-10) |
| Q14 | `PercentileAccuracy` | tDigest / BFloat16 / DD state merges vs quantileExact at 1m/1h/1d (value and rank error, 1m maximum per day), judged on the C-53 criteria (1m group p95 value error of p50/p95 <= 2 %, p99 value error at 1h and above <= 5 %, 1m p99 rank error <= 0.5 pp); stored `quantilesDD` states of the loaded data (`Q14/dd_stored`); bucket p95 error, state size (A-11); D-14 rebuild determinism (1m and 1h states of one day rebuilt twice, state bytes and values compared) and, on the loaded data, `Q14/d14_rebuild`: the rollups of the last full day rebuilt twice with the C-59 procedure, the stored DD states compared byte for byte with the view-filled ones and between the rebuilds |
| Q15 | `DedupReinsert` | same token 100x, broken view, repair, retry (D-13) |
| Q16 | `LateArrival` | late rows update exactly their minutes and hours; Q12 again (D-03) |
| Q17 | `ConcurrentLoad` | virtual users running Q1/Q3/Q7 (1d_live: last 2 minutes from xlog) every 5 s; live window (end_minute = server minute, renewed per iteration) during a replay, fixed otherwise; latency and rows read per query; parts, DelayedInserts, memory (N-06); the replay is the generator's |
| Q18 | `RetentionOps` | user_id deletion: the two forms on two copies with the same content (d - 1 and d), same subject, alternating order, repeated; DROP PARTITION and disk reclaim; MATERIALIZE TTL on a day copy |
| Q19 | `MigrationRehearsal` | ADD/DROP COLUMN, MODIFY TTL materialize 0, no-op control (same TTL, materialize 1), real retention change (+1 day, materialize 1) and restore, MV replacement in 4 steps (DDL from deploy/clickhouse, backfill with the C-59 procedure) |
| Q20 | `CompressionComparison` | ratio and bytes per row of every table; six variants: `base` (V001, ZSTD(1), C-54), `lz4` (the pre-ADR-0007 V001, `LegacyCodecs`), `lz4_nocodec`, `lz4cols_zstd`, `nocodec` and `proposed` (the WO-03 proposal, `ProposedCodecs`), each table from its latest full 24 hours, merges settled, repeated |

## CLI (`QueryBench`)

```
--volume NAME [--memory-gib 5] | --url http://host:8123 (ARGOS_CH_{READER,WRITER,MIGRATOR}_PASSWORD)
--database argos --prefix P  [--apply-schema] [--load-ci-dataset]
--queries all|none|Q01,...   --procedures all|none|Q05/bloom,Q11/cost,Q14,...,Q20
--warm 3  --cold none|restart|restart+dropcache|command:SHELL
--q14-source synthetic|dataset --q14-apps 2 --q14-per-minute 3000 --q14-days 3 --q14-min-count 1000
--q15-tx 20000 --q15-repeats 100 --q16-rows 20000 --bloom-days 3
--q17-users 10 --q17-interval-s 5 --q17-duration-s 1800 --q17-ingestion replay_5000_TPS --q17-window live|fixed
--q18-wait-s 600 --q18-repeats 2 --q18-copy-hours 24 --q18-settle-s 120   --q20-repeats 2 --q20-settle-s 300
--out DIR  --strict
```

Output: `report.md` (environment, data, query table, findings, procedures) and `runs.tsv` (every statement with its
system.query_log record) in `build/reports/storage-queries/bench/`.

## CI tests

| Class | Covers |
|---|---|
| `ConsistencyIT` | T-ST-030 (raw counts, late rows and sum(app_stat_1m) = ground truth right after the load; Q12 per minute, 1h cascade, errors, nightly form, all in the C-65 form), T-ST-031 (Q13 totals, raw vs 1m vs 1h per day, raw vs 1m per minute exact; per day: loaded values = manifest, and the daily and nightly checks find 0 days under the C-57 rule, which the data needs: every day whose xlog and statistics differ stays within the counts of its midnight-crossing transactions), T-ST-032 (Q15), T-ST-033 (Q16, 20,000 rows), negative controls (incl. a key found only in the third source and a per-day difference beyond the C-57 bound) |
| `ReaderLimitsIT` | T-ST-053: Q01..Q11 as argos_reader with limits, every run in system.query_log; limits enforced; every DDL/write statement of the catalog refused |
| `BenchRunIT`, `ColdRestartIT` | the whole catalog and every procedure end to end, report written (on `-Pqueries.profile=ci`: a harness smoke run, Q01..Q11 once each, no copy procedures Q05/bloom, Q18, Q20; `-Pqueries.benchrun=full`, passed to the test JVM as `argos.queries.benchrun`, runs all of them on any profile); Q14 on the loaded data with the C-59 rebuild check; restart cold strategy |

The CI dataset is loaded by the PoC generator (test/storage/generator, 2 days at 1 TPS through the writer path);
`-Pqueries.profile=ci` loads the generator's 1/100 profile instead (about 13 million xlog rows, 18 §9; about 3
minutes for ConsistencyIT and ReaderLimitsIT), and `-Pqueries.dataset=fixture` uses the runner's own deterministic
`CiDataset`. Evidence lines go to `build/reports/storage-queries/*.md`, the CI report to
`build/reports/storage-queries/ci/`.
