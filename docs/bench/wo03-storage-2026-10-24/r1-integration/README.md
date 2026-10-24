# WO-03 integration re-verification after review round 1 (2026-10-24)

Raw results of the integration lead's end-to-end run at the CI scale (1/100) after the review round 1
fixes (`r1-generator/`, `r1-queries/` and the schema changes) were merged in the working tree. They back
section 16 of [`docs/poc/clickhouse-benchmark.md`](../../../poc/clickhouse-benchmark.md). Same machine as the
parent directory ([README](../README.md)); reference values only. Output paths are shortened to `<scratch>`.

## Run conditions

| Item | Value |
|---|---|
| Code | commit `4a43e77` plus the uncommitted working tree as of 2026-10-24 18:00 UTC (WO-03 after review round 1, with the two integration fixes of this run: `DockerStartup` in `test/storage/clickhouse-support`, the `bloom_targets` step of `Q05.sql`) |
| Server | `test/storage/bench/bench-server.sh start` with `ARGOS_BENCH_NAME=argos-wo03r1-bench`, `ARGOS_BENCH_PORT=18125`, `ARGOS_BENCH_MEMORY_GIB=5` (server 4,831,838,208 B, read query 1,932,735,282 B, insert 1,288,490,188 B): ClickHouse 26.8.34.13, repository configuration, accounts `argos_writer` / `argos_reader` / `argos_migrator` with generated passwords kept outside the repository |
| Data | generator profile `ci` (scale 0.01, seed 42), bulk run `07b3257d13ab`, range 2026-10-23 20:24 .. 2026-10-24 03:36 UTC (xlog 12,961,791 rows), then the replay below |
| Tools | `storageBench` (Gradle) for the bulk load; generator and QueryBench started with `java -cp <runtime classpath printed by Gradle>` for the rest, so that the replay and Q17 could run at the same time |
| Cold runs | `--cold none` (the first run is the cold one): the shared machine did not allow dropping the Docker host page cache |
| Concurrent load | WO-02 overhead benchmarks (JVMs of `overhead-bench`, sample applications) ran during the whole session. `machine-cpu.tsv` has the machine CPU load about every 5 s (UTC); every report also has the CPU load of each statement |
| Disk | host drive C: had 1.2-2.3 GB free during the run (`host-disk.tsv`, every 15 s, with the size of Docker Desktop's `docker_data.vhdx`, which stayed at 75,879,153,664 B). The bench volume held 2.868 GB when it was removed (active parts 1.83 GB, all parts 3.33 GB, `sizes-final.tsv`) |

## Time windows (UTC)

| Phase | Window | Machine CPU mean / max | Directory |
|---|---|---|---|
| Bulk load (`storageBench -Pstorage.profile=ci -Pstorage.apply-schema=true`) | 18:08:18-18:09:35 | 75 % / 100 % | `bulk/` |
| QueryBench run P: Q1-Q13, Q05/bloom, Q11/cost, Q14-Q16, Q18-Q20 | 18:09:37-18:30:45 | 37 % / 68 % | `querybench-p/` |
| Replay 500 -> 2,000 -> 5,000 TPS, 8 min each, 100 ms ticks | 18:30:46-18:55:31 | 35-37 % / 60-76 % per step | `replay/` |
| Q17 during the 2,000 TPS step (10 users, live window, 6 min) | 18:39:47-18:45:53 | 31 % / 56 % | `q17-2000/` |
| Q17 during the 5,000 TPS step | 18:47:47-18:53:53 | 32 % / 57 % | `q17-5000/` |
| Q12/Q13 after the replay | 18:55:31-18:55:39 | 23 % / 45 % | `after-replay/` |
| Q05/bloom again after the fix of `bloom_targets` (same server, data after the replay) | 18:56:17-18:58:54 | see report | `q05-bloom/` |

## Files

| File | Content |
|---|---|
| `bulk/manifest.json`, `bulk/report.md` | 33,920,500 rows in 68.95 s (491,929 rows/s), per-table compression and bytes per row (data and disk basis), parts, MemoryTracking, D-12 totals |
| `querybench-p/report.md`, `runs.tsv` | Command: `--queries all --procedures Q05/bloom,Q11/cost,Q14,Q15,Q16,Q18,Q19,Q20 --warm 3 --cold none --q14-days 3 --q18-repeats 2 --q18-copy-hours 24 --q18-wait-s 600 --q20-repeats 2 --q20-settle-s 180`. Q05/bloom is `ERROR` here (see below) |
| `replay/manifest.json`, `replay/report.md` | `--mode=replay --tps-steps=500,2000,5000 --step-duration=PT8M --tick-ms=100 --seed=11 --run-id=r1e2e`: ingest lag per step, insert p95, parts, MemoryTracking, D-12 totals. `rawCountsMatchManifest=false` because the tables already held the bulk data and the rows added by Q15/Q16; the raw counts after the run equal the counts before the replay plus the generated rows for all five tables (`sizes-final.tsv`) |
| `q17-2000/`, `q17-5000/` | Command: `--queries none --procedures Q17 --q17-users 10 --q17-interval-s 5 --q17-duration-s 360 --q17-ingestion replay_<tps>_TPS_(generator_url_target) --q17-window live` |
| `after-replay/` | `--queries Q12,Q13 --procedures none --warm 1` |
| `q05-bloom/` | `--queries none --procedures Q05/bloom --warm 3 --cold none --bloom-days 3` |
| `sizes-final.tsv` | `system.parts` per table (active) at the end, and total bytes of all and of active parts |
| `machine-cpu.tsv`, `host-disk.tsv` | samples described above |

## Findings of this run

- `Q05/bloom` failed in run P with `BAD_QUERY_PARAMETER` (`btx` = NULL): with `--bloom-days 3` on the 1/100
  data the copied days start at the partial first day (2026-10-23, data from 20:24), and the lookup targets
  were taken from 01:00-04:00 of that day. `Q05.sql` (`bloom_targets`) now takes them from the first hours
  of the last copied day (`full_day`), and `BloomVariants` fails with a clear message if a target is still
  NULL. `q05-bloom/` is the rerun on the same server.
- Q18: the two deletion forms did not repeat the order of review round 1 (`r1-queries/run-P`); see PoC
  section 10.1.
- In the parallel `./gradlew integrationTest` the schema test JVM failed every run on this machine with
  "Could not find a valid Docker environment". Ten JVMs that initialise Testcontainers at the same instant
  reproduced it (1-2 of 10 failed; Testcontainers' log: `DOCKER_HOST socket file '//./pipe/docker_engine'
  does not exist`, i.e. the `Files.exists` check of the named pipe failed while other processes held it).
  With `DockerStartup` (lock file + pipe wait before the first Testcontainers call) 3 x 10 JVMs all
  succeeded and the root `integrationTest` passed. The probe is not kept here (diagnostic only); the numbers
  are in PoC section 16.
