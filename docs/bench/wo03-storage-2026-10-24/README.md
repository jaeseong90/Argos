# WO-03 storage PoC: raw evidence (2026-10-24)

Raw results behind [`docs/poc/clickhouse-benchmark.md`](../../poc/clickhouse-benchmark.md) (P-4, WO-03).
All numbers come from a shared developer laptop. They are **reference values**, not Gate 0
verdicts: Gate 0 is decided on the target hardware (`argos-storage`, T-ST-001..020, PoC section 13).

## Run conditions

| Item | Value |
|---|---|
| Date | 2026-10-24 (all times below in UTC unless stated) |
| Code | commit `4a43e77` plus the uncommitted working tree of WO-00..WO-04 as of 2026-10-24 (WO-03 files: `deploy/clickhouse/**`, `test/storage/**`) |
| Machine | AMD Ryzen 5 5600H (6C/12T), 15.3 GiB RAM, NVMe, Windows 11 Home 10.0.26200, Java 21 |
| Docker | Docker Desktop 4.61.0, WSL 2.7.1 (kernel 6.18), VM 12 CPUs / about 7.4 GiB, shared with WO-02 |
| ClickHouse | 26.8.34.13 LTS, `clickhouse/clickhouse-server:26.8.34.13@sha256:8b28eb09479c36c4533eec7a6268917d04ba3a6fd90e3f4205f70e761b6d7779` |
| Container memory | 1/100 generator lane 4 GiB (Testcontainers), 1/100 query integration tests 3 GiB (Testcontainers), 1/20 bench server 5 GiB (`test/storage/bench/bench-server.sh`) |
| Concurrent load | WO-02 (agent PoC) ran builds and JVM benchmarks on the same machine. Machine CPU load is in `cpu-load.tsv` and in every report |
| Accounts | `deploy/clickhouse/users.d/argos.xml` as committed (no `default` user, `argos_writer` / `argos_reader` / `argos_migrator`); generated passwords were kept outside the repository and are not in these files |

### Time windows

| Run | Window (UTC) | Directory |
|---|---|---|
| 1/100 generator lane: bulk 1, bulk 2, replay 500/2,000/5,000 TPS x 2 min | 07:38:44-07:40:02, 07:40:44-07:42:04, 07:42:30-07:48:30 | `ci-1of100/generator/` |
| 1/100 `:test:storage:queries:integrationTest -Pqueries.profile=ci`, run 2 (8 min 48 s, BUILD SUCCESSFUL) | about 08:34-08:43; `BenchRunIT` QueryBench 08:36:13-08:41:38 | `ci-1of100/querybench/`, `ci-1of100/it/` |
| 1/20 bulk load (`storageBench`, profile `local`) | 08:49:53-08:58:20 | `local-1of20/generator/20261024-085820-bulk-8d30f3a4af28/` |
| 1/20 QueryBench run A (Q1-Q20, procedures) | 09:00:19-09:35:23 | `local-1of20/querybench/qA/` |
| 1/20 QueryBench run A2 (Q1-Q11 timing, graceful cold restart) | 09:36:54-09:57:03 | `local-1of20/querybench/qA2/` |
| 1/20 V001 DDL dump; manual codec experiment (variant DDL 09:47, copy/insert/read results 10:00-10:01), A-6 fallback 10:02, skip index sizes 10:05 | 09:05; about 09:47-10:05 | `local-1of20/codec/`, `local-1of20/sizes/` |
| 1/20 replay 500 -> 2,000 -> 5,000 TPS, 10 min each | 10:04:24-10:35:09 | `local-1of20/generator/20261024-103509-replay-wo03local/` |
| 1/20 Q17 at 2,000 TPS / 5,000 TPS | 10:15:25-10:23:27 / 10:25:26-10:33:28 | `local-1of20/querybench/q17-2000/`, `q17-5000/` |
| 1/20 QueryBench run C (Q12/Q13 after the replay) | 10:35:45-10:35:51 | `local-1of20/querybench/qC/` |
| ClickHouse monitor (15 s) | 08:59:48-10:35:57 | `local-1of20/monitor/ch-monitor.tsv` |
| Machine CPU samples (about 5 s) | 08:21:46-11:23:02 (file timestamps are local time, UTC+9) | `cpu-load.tsv` |

## Files

### `cpu-load.tsv`

Machine-wide CPU load sampled by a PowerShell loop every 5 s (plus WMI query time):
`<local time, UTC+9, no offset>\tcpu=<Win32_Processor.LoadPercentage average>\tfreeMB=<free physical memory>`.
Covers the 1/100 query tests and the whole 1/20 session. PoC section 2 quotes the 1/20 window
(08:50-10:36 UTC, about 1,030 samples): mean 44.5 %, 13.6 % of samples at 80 % or more.

### `ci-1of100/` (CI profile, scale 0.01, xlog 12,961,791 rows)

| File | Content | PoC sections |
|---|---|---|
| `querybench/report.md`, `runs.tsv` | `BenchRunIT` QueryBench run `wo03-bb03c7cc`: Q1-Q11 timings (cold = first run), Q14, Q15-Q20 procedures, per-run rows of `system.query_log` | 5.1, 5.3, 6.2, 9, 10.1, 12.1, 12.3 |
| `it/consistency.md` | `ConsistencyIT` results (Q12, Q13, Q15, Q16, negative control) | 7.1 |
| `it/reader-limits.md` | `ReaderLimitsIT` (T-ST-053): every catalog query under the `argos_reader` limits | 6.2, 7.1 |
| `it/dataset.md`, `it/generator-log.txt` | Data set loaded by the tests (generator run `04de5ea683da`, load time, late rows, re-sent blocks, skewed instances) | 7.1 |
| `it/bench-ci.md`, `it/cold-restart.md` | `BenchRunIT` Q14 summaries and `ColdRestartIT` cold/warm values | 7.1, 8 |
| `generator/20261024-074002-bulk-804934114677/`, `.../20261024-074204-bulk-804934114677/` | Generator lane bulk loads 1 and 2 (`manifest.json`, `report.md`): rows/s, insert p95, compression, B/row, parts, MemoryTracking, host CPU | 4.1, 5.1 |
| `generator/20261024-074915-replay-c3cd0823acd5/` | Generator lane replay, 500/2,000/5,000 TPS x 2 min (ingest lag p50/p99/max per step) | 4.2, 5.1 |

The files under `it/` are appended by every integration test run since the build directory was
last cleaned, so they hold several sections (fixture data set and generator data set). The last
section of each file is run 2. The QueryBench report of run 1 (about 08:25-08:33 UTC; `BenchRunIT`
failed with `TIMEOUT_EXCEEDED` in `Q05/bloom_fill`) was overwritten by run 2 and is not available.

### `local-1of20/` (local profile, scale 0.05, 36 h of data, xlog 64,807,447 rows after the bulk load)

| File | Content | PoC sections |
|---|---|---|
| `generator/20261024-085820-bulk-8d30f3a4af28/` | Bulk load (`manifest.json`, `report.md`): 169,558,711 rows in 505.9 s, per-table rows/s and insert p95, compression, B/row, parts, MemoryTracking, host CPU, D-12 totals | 3, 4.1 |
| `generator/20261024-103509-replay-wo03local/` | Replay 500 -> 2,000 -> 5,000 TPS (2 s / 50,000-row blocks, `argos:<table>:<block_id>` tokens, duplicates): ingest lag per step, insert p95, parts, MemoryTracking | 4.2, 9 |
| `querybench/qA/` | Run A `wo03-90d5b258`: Q1-Q11 (cold restart without graceful wait, server time missing), Q12/Q13, Q14, Q15, Q16, Q18, Q19, Q20, Q05/bloom, Q11/cost | 5.3, 6.1 (note), 6.3, 7.2, 8, 10, 12.3, 12.6 |
| `querybench/qA2/` | Run A2 `wo03-f348495d`: Q1-Q11, cold = graceful restart + page cache drop before each variant | 6.1, 6.3, 12.1, 12.3 |
| `querybench/qC/` | Run C `wo03-5a803646`: Q12/Q13 after the replay (xlog 69,369,073 rows) | 7.2 |
| `querybench/q17-2000/`, `q17-5000/` | Q17: 10 virtual users x (Q1, Q3, Q7) every 5 s for 8 min during the replay steps; latency per query, parts, memory, RSS | 9 |
| `monitor/ch-monitor.tsv` | ClickHouse state every 15 s via `argos_reader` (see below) | 4.3 |
| `monitor/hostdisk.txt` | Free space of the host drive C: during the replay (GB, timestamps with +09:00) | 3 |
| `sizes/sizes-after-bulk.txt`, `sizes-final.txt` | Per table: parts, rows, compressed MiB, ratio, B/row, disk MiB, max parts per partition (`system.parts`, active) after the bulk load and after the replay | 5.1 |
| `sizes/xlog-columns.txt` | xlog bytes per row and ratio per column, V001 codecs (`system.parts_columns`) | 5.2 |
| `sizes/xlog-columns-codecs.txt` | xlog B/row per column: V001 (`base_lz4`), ZSTD(1) default with V001 column codecs (`zstd`), proposed codecs (`tuned`) | 5.2 |
| `sizes/xlog-codecs.tsv` | V001 column codec per xlog column (empty = server default) | 5.2 |
| `sizes/xlog-skip-indexes.txt` | xlog skip index sizes (`system.data_skipping_indices`) | 12.6 |
| `sizes/a6-fallback.txt` | A-6 lookup of a transaction from a +60 s skewed instance. No header in the dump; columns are: variant, server time of 3 warm runs (ms), rows read, marks. Variant 1 = restored ts +-2 s (0 rows), variants 2-4 = 2-day partition bloom fallback | 12.1 |
| `codec/codec-sizes.txt` | One day (2026-10-23) copied into `q20z_*` (ZSTD(1) default + V001 column codecs) and `q20t_*` (proposed codecs): rows, compressed bytes, ratio, B/row, parts | 5.1, 5.3, 12.5 |
| `codec/codec-insert.txt` | Insert wall time and CPU of those copies (`system.query_log`) | 5.3 |
| `codec/codec-read-qlog.txt` | Read cost on the same day for V001 (`xlog-*`), `q20z_xlog-*`, `q20t_xlog-*`: server ms of 3 runs, median ms, median CPU ms, rows, MiB | 5.3, 12.5 |
| `codec/ddl/ddl-<table>.sql` | `SHOW CREATE TABLE` of the V001 tables used as the base | 5.3, 12.5 |
| `codec/ddl/ddl-q20z-<table>.sql`, `ddl-q20t-<table>.sql` | DDL of the ZSTD(1) default variant and of the proposed codec variant | 5.3, 12.5 |

`ch-monitor.tsv` columns: `ts` (ClickHouse `now()`, UTC), active parts, max active parts per
partition, running merges, `MemoryTracking` bytes, `DelayedInserts` (cumulative event; `\N` = never
incremented, i.e. 0), bytes on disk of all parts (outdated included), bytes on disk of active parts,
free bytes of the ClickHouse disk, xlog rows, and the latest `cpu-load.tsv` sample. The first sample
was taken with the earlier column set (no active-bytes and free-space columns); the second header line
marks the switch. Rows that only carry the CPU value are samples taken while the server restarted for a
cold run.

### `r1-generator/`, `r1-queries/`, `r1-integration/` (review round 1, 2026-10-24 15:59-19:00 UTC)

Measurements taken by the fixers of review round 1 with the corrected tools (15:59-17:08 UTC), and the
integration lead's end-to-end re-run at 1/100 after the fixes were merged (17:33-19:00 UTC). Each directory
has its own README with the run conditions, the exact commands and the time windows.

| Directory | Content | PoC sections |
|---|---|---|
| [`r1-generator/`](r1-generator/README.md) | Generator only: load mix per TPS step (`--target=none`, default distributions and the N-06 options), replay into Testcontainers ClickHouse with 100 ms and 1,000 ms simulation ticks (ingest lag), machine CPU per step | 4.2, 5.1, 9 |
| [`r1-queries/`](r1-queries/README.md) | 1/100 bench server: QueryBench run P (Q1-Q13, Q14 over 3 days with D-14 determinism, Q18 controlled comparison, Q19 `ttl_change_1`, Q20 with `proposed`), A-6 skew runs, mutation check, replay 2,000/5,000 TPS with Q17 on a live window, Q12/Q13 after the replay, storage integration test summaries | 5.3, 6.1-6.3, 7, 8, 9, 10, 12.1 |
| [`r1-integration/`](r1-integration/README.md) | 1/100 bench server, end to end after the r1 fixes: bulk load, QueryBench run P (Q1-Q13 and all procedures except Q17), replay 500/2,000/5,000 TPS x 8 min with 100 ms ticks, Q17 (live window) in the 2,000 and 5,000 TPS steps, Q12/Q13 after the replay, Q05/bloom after its fix, final sizes, machine CPU and host disk | 10.1, 16 |

## Changes made when copying

Only these normalizations were applied; no value was changed:

- UTF-8 byte order mark removed and CRLF converted to LF (`cpu-load.tsv`).
- The absolute path of the work machine's scratch directory, which contains the local user name, was
  replaced by `<scratch>` (4 occurrences: the cold-restart command line in `qA/report.md` and
  `qA2/report.md`, the `out` option in the two 1/20 generator manifests).
- `-- SPDX-License-Identifier: Apache-2.0` was prepended to `codec/ddl/*.sql` (T-BLD-007).
- Two `.log` files were renamed to `.txt` because the repository ignores `*.log`:
  `it/generator.log` -> `it/generator-log.txt`, `monitor/hostdisk.log` -> `monitor/hostdisk.txt`.

Not copied: generator `dictionary.tsv` and `samples.json` (synthetic dictionaries and sample rows,
regenerated from the seed), console logs that repeat the reports, the derived `ch-monitor-clean.tsv`,
a codec experiment shell log without values, and the scratch credential file used by the diagnostic
scripts. The 1/20 data (7.0 GB active) and its Docker volume were deleted after the measurement.

## Known gaps

- 1/100 QueryBench run 1 (see `ci-1of100/` above) is lost; PoC section 6.2 quotes run 2.
- The A-6 restore miss rate in PoC section 12.1 (2.86 %, 1,233,574 of 43,204,363 rows of 2026-10-23
  outside +-2 s) was counted by a manual query during the session; no raw file was kept. It is
  re-measured on the target hardware (PoC section 13, item 11).

## Source

Copied from the scratch directory of the WO-03 session on the work machine,
`%TEMP%\claude\C--workspace-Argos\2b93fd47-468f-4262-bae3-62f54b0ac448\scratchpad\`:

| Here | Scratch path |
|---|---|
| `cpu-load.tsv` | `wo03-int\cpu-load.tsv` |
| `ci-1of100/querybench/` | `wo03-int\ci-1of100-run2\ci\` |
| `ci-1of100/it/` | `wo03-int\ci-1of100-run2\` |
| `ci-1of100/generator/` | `wo03-generator\evidence\` |
| `local-1of20/querybench/<run>/` | `wo03-int\bench\<run>\` |
| `local-1of20/generator/` | `wo03-int\bench\gen\`, `wo03-int\bench\replay\` |
| `local-1of20/monitor/`, `sizes/`, `codec/` | `wo03-int\bench\` |

Reproduction commands: PoC section 15 and [`test/storage/bench/README.md`](../../../test/storage/bench/README.md).
