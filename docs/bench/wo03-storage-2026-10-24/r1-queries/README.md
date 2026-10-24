# WO-03 review fixes r1: query runner evidence (2026-10-24)

Raw results behind the query runner changes of the WO-03 review round 1 (area storage-queries: findings
bench-method-2, -3, -4, -5, -7, -8, -10, -11, correctness-1, -2, -4, qa-1, -3, -5) in
[`docs/poc/clickhouse-benchmark.md`](../../../poc/clickhouse-benchmark.md) sections 5.3, 6.1, 6.3, 7, 8, 9, 10,
12.1 and 13. Same machine as the parent directory ([README](../README.md)); reference values only. Output
paths are shortened to `<scratch>`.

## Run conditions

| Item | Value |
|---|---|
| Server | a bench server started with `test/storage/bench/bench-server.sh` (`ARGOS_BENCH_MEMORY_GIB=5`, own name and port), ClickHouse 26.8.34.13, repository configuration, accounts `argos_writer` / `argos_reader` / `argos_migrator` with generated passwords (kept outside the repository) |
| Data | 1/100 (`--profile=ci`) bulk, generator run `07b3257d13ab`, range 2026-10-23 20:24 .. 2026-10-24 03:36 UTC (xlog 12,961,791 rows) |
| Tools | `test/storage/queries` (QueryBench) and `test/storage/generator` of the working tree at 2026-10-24 16:13 UTC (classes copied before the runs, so concurrent edits of other fixers did not change them) |
| Concurrent load | WO-02 builds and benchmarks, other WO-03 fixers' builds and ClickHouse containers. `host.tsv`: machine CPU (`Win32_Processor.LoadPercentage`) and free space on C: about every 18 s (a watchdog would have stopped this server below 700 MB free; it did not trigger). Every report also has the CPU load of each statement (1 s samples of the runner) |
| Cold runs | `--cold none` (the first run is the cold one): the shared machine did not allow dropping the Docker host page cache |

## Files

| Directory / file | Run (UTC) | What |
|---|---|---|
| `bulk/` | 16:14:43-16:16:45 | generator report and manifest of the 1/100 bulk load |
| `run-P/report.md`, `runs.tsv` | 16:17:19-16:39:11 | QueryBench `--queries all --procedures Q14,Q18,Q19,Q20 --warm 3 --cold none --q14-days 3 --q18-repeats 2 --q18-copy-hours 24 --q18-wait-s 600 --q20-repeats 2 --q20-settle-s 180`: Q1-Q13 (new columns: compressed bytes read, marks of total, CPU of every statement), Q05 A-6 variants, Q07 `1d_live`, Q12/Q13 UNION ALL forms and `minute_raw_1m`, Q14 over 3 days with the per-day table and the D-14 determinism table, Q18 controlled comparison of the two deletion forms, Q19 with `ttl_change_1`, Q20 with `proposed`, per-table ranges, settled merges and 2 repetitions. The Q20 "Loaded data" table also lists the `sk_` tables of the skew run below (same database) |
| `skew25h/` | generator 16:19:34 (7.5 s), QueryBench 16:19:47-16:19:55 (during run P) | `--seed=42 --days=2 --tps=1 --profile-days=1 --raw-days=0.5 --metric-series=2 --users=5000 --block-rows=20000 --skewed-offset-ms=90000000 --table-prefix=sk_`, then QueryBench `--prefix sk_ --queries Q05 --procedures none`: `fallback_2d` finds no row for a transaction 25 h ahead whose restored day is two days after its ts |
| `a6-coverage.txt` | 16:40:52 and 16:46 | SQL and result: transactions of the last day whose ts is more than 2 s from the time in their tx_id, and how many of those the 2 day fallback misses (1/100 data with the default +60 s skew; `sk_` data with +25 h) |
| `mutation-5min/report.md` | 16:18:35-16:18:39 (own Testcontainers server, during run P) | Q12/Q13 against a copy of `deploy/clickhouse` whose `rollups/ext_stat_1m.sql` used `toStartOfFiveMinutes(ts)` instead of `toStartOfMinute(ts)` (the repository file was not changed), fixture data set: only `Q13/minute_raw_1m` fails (1,000 rows, the LIMIT), `rollups_14d`, `daily_30d` and `total_30d` pass |
| `replay/` | 16:40:51-17:01:37 | generator replay `--mode=replay --tps-steps=2000,5000 --step-duration=PT10M --tick-ms=100 --seed=7 --run-id=qfixlive` into the 1/100 data: ingest lag per step |
| `q17-2000/`, `q17-5000/` | 16:41:51-16:49:56, 16:51:51-16:59:58 | QueryBench `--queries none --procedures Q17 --q17-users 10 --q17-interval-s 5 --q17-duration-s 480 --q17-window live` during the two replay steps: latency and rows read per virtual-user query, distinct end minutes of the live window, monitor samples |
| `after-replay/report.md` | 17:01:57-17:02:06 | Q12/Q13 after the replay |
| `it/` | 17:02:27-17:08:17 | `./gradlew :test:storage:schema-tests:integrationTest :test:storage:generator:integrationTest :test:storage:queries:integrationTest -Pqueries.profile=ci --rerun-tasks` (BUILD SUCCESSFUL, 5 min 48 s): the evidence files of the query integration tests (`consistency.md`, `dataset.md`, `reader-limits.md`, `bench-ci.md`, `cold-restart.md`; appended by every run since the build directory was cleaned, the last section is this run) and the `BenchRunIT` smoke report (`benchrun-smoke-report.md`) |
