# test/storage/generator

Synthetic telemetry for the storage PoC (WO-03, P-4): the distributions of docs/13 §15, loaded into
ClickHouse the way the Argos server writes (13 §7.4, 12 §3.8). Development-only, never shipped.

```
./gradlew :test:storage:generator:test               # no Docker: distributions, invariants, determinism, dedup tokens
./gradlew :test:storage:generator:integrationTest    # Docker: bulk + replay loads checked against the manifest (about 1.5 min)
./gradlew :test:storage:generator:storageGenerate -Pscale=0.01 -Pstorage.target=docker
./gradlew :test:storage:generator:storageBench -Pbench.machine-profile=<id> [-Pscale=...] [-Pstorage.<key>=<value>]
```

`-Pstorage.<key>=<value>` passes any generator parameter (`--help` lists them with defaults;
`GeneratorConfig`), `-Pscale` is the 18 §9 scale, `-PstorageXmx=3g` the generator heap.

## What it generates

| Item | Default (13 §15) |
|---|---|
| Apps | 10 (prod 7, stg 3), 5 instances each (50) |
| Services | 100 HTTP services, global Zipf s=1.1 spread over the apps, plus one batch job per app |
| SQL | 2,000 statements (200 per app), Zipf 1.0; a service uses its usual statements (sql_ids mean 3), 5 % add a rare one, 5 % run an N+1 loop |
| Outgoing calls | 20 hosts: one internal host per app (a call produces a child transaction of the same trace in the called app) and 10 external hosts |
| Versions | 3; on day 15 a rolling restart moves every instance to v2, the last instance of each app (canary) to v3 |
| kind, errors | BATCH 3 %; errors 1 % over 50 error groups (5 per app) |
| Response time | log-normal median 80 ms, sigma 1.0 (per-service shifts with traffic-weighted mean 0) + 1 % outliers 2-30 s |
| Privacy fields | user_id 30 % (hashed, 16 hex), client addresses 10,000 masked /24 (Zipf), request_id 50 % (UUID) |
| Profile | agent sampler (errors, >= 1 s, 10 % of the rest), about 14 steps per capture, STACK captures, summary mode beyond 1,000 steps |
| Metrics | 100 series per instance on the 5 s grid (JVM, pools, GC, threads, JDBC, Tomcat, HTTP, agent) |
| Statistics | 10 s windows derived from the transactions (D-12 holds by construction), 15 buckets |
| Server path | clock skew +/-2 s, two instances +60 s (timestamp guard clamps them, D-15), late batches 0.5 % (1-30 min, `late` flag), midnight crossing |

`scale` shrinks the time span of every table (xlog 30 days, profile_step the last 7, metric the last
14, statistics the whole xlog span); rates and cardinalities do not change, so a 10-minute query reads
the same data as at full scale. `--profile=ci` is 1/100 (xlog 13 M rows over 7.2 h centred on the last
UTC midnight), `tiny` 1/1000, `local` 1/20.

## Modes

- `bulk`: rows in arrival order (date order; late rows land in later blocks) are cut into blocks of
  `block-rows` (500,000) per table and inserted by one connection per table.
- `replay`: the writer path of 12 §3.8 in real time: every row is a WAL record, a writer per table
  flushes every 2 s or 50,000 rows (`BLOCK_INTENT` -> insert -> `BLOCK_COMMIT`), TPS steps
  `tps-steps` x `step-duration`, optional simulated writer restarts (`writer-restarts`). Reports the
  ingest lag (receive -> ack) per step, parts, merges, memory and DelayedInserts.

The simulation advances in ticks of `tick-ms` (default 1000): a row is handed to the writer at the
end of the tick of its receive time, so the replay ingest lag (receive -> ack) includes up to one
tick of waiting on top of the 2 s flush. Use `--tick-ms=100` for lag measurements. Another tick
interleaves the HTTP and batch arrivals of an instance differently (same distributions and volumes,
not the same rows); the manifest and D-12 invariants hold for any tick.

### N-06 load composition (Q17)

The 13 §15 distributions give, at 5,000 TPS, about 8,500 profile_step rows/s and a few hundred
statistics rows/s (50 instances, each service using its usual statements). N-06 / Q17 ask for
50,000 step rows/s and 10,000 statistics rows/s. These options reproduce that load without a
separate synthetic stream (statistics stay derived from the transactions, so D-12 holds):

| Option | N-06 value | Effect |
|---|---|---|
| `--instances-per-app=30` | 300 instances (13 §14) | more (instance, statement) pairs per 10 s window |
| `--active-sqls=200` | 13 §14 "active SQL 200" | each instance draws its statements uniformly from its active set (200 = every statement of its app) instead of the services' usual ones |
| `--profile-step-rate=50000` | 50,000 step rows/s | the capture rate of normal transactions is adapted every tick so that profile_step rows follow the target at the highest TPS step (lower steps proportional) |
| `--tick-ms=100` | - | finer release of received rows (ingest lag) |

Measured with `--target=none` at 5,000 TPS (replay, 5 min): xlog 5,011, profile_step 50,018,
sql_stat_raw 5,300, ext_stat_raw 126, metric 6,000 rows/s (66,455 in total; normal capture rate about
0.73). The SQL statistics rows per second are bounded by instances x active statements / 10 s (13 §14):
300 x 200 / 10 = 6,000 rows/s, so 10,000 statistics rows/s is not reachable with the 13 §14
parameters (docs/poc/clickhouse-benchmark.md, section 14.2). The report gives the generated rows per
simulated second per table (`simulation.rowsPerSimulatedSecond` in `manifest.json`).

Every insert carries `insert_deduplication_token = argos:<table>:<block_id>` and
`deduplicate_blocks_in_dependent_materialized_views = 1`. Bulk block ids contain a run id derived from
the parameters and the start (an identical re-run is deduplicated); replay block ids are WAL positions
prefixed by a WAL id. `duplicate-rate` (and `force-duplicate`: the first block of every table) re-sends
blocks with the same token.

## Targets and output

`--target=none` (count only), `file:<dir>` (RowBinaryWithNamesAndTypes block files and `blocks.tsv`;
on Windows give a `C:/...` path, Git Bash `/c/...` paths are not translated for Java),
`docker` (Testcontainers ClickHouse with deploy/clickhouse, `docker-memory`, `docker-volume` to keep
the data), `url:http://host:8123/` (passwords from `ARGOS_CH_WRITER_PASSWORD`,
`ARGOS_CH_READER_PASSWORD`, `ARGOS_CH_MIGRATOR_PASSWORD`; never on the command line).

Each run writes `<out>/<time>-<mode>-<runId>/`: `manifest.json` (configuration, range, expected rows
per table and per day, D-12 sums by xlog day and by window day, rows per simulated second, insert
statistics, host CPU load, ClickHouse sizes and compression), `report.md`, `samples.json` (tx_id, trace_id, user_id, request_id
samples for query parameters, including the +60 s instances) and `dictionary.tsv` (names of the
dictionary keys). Table sizes are given on two bases: bytes per row of the column data
(`data_compressed_bytes`, the 13 §15 compression ratio) and of the disk (`bytes_on_disk`, with skip
indexes, marks and primary index: the 13 §14 / N-19 capacity basis).
