# Storage generator run r1e2e

| Item | Value |
|---|---|
| Mode | replay |
| Seed / scale | 11 / 1.0 |
| Target | clickhouse argos_writer argos.* |
| Machine profile | wo03-laptop-r5-5600h-shared |
| Range | 2026-10-24T18:30:46Z .. 2026-10-24T18:54:46Z |
| profile_step from | 2026-10-24T18:30:46Z |
| metric from | 2026-10-24T18:30:46Z |
| Version switch / deploy | 2026-10-24T18:54:46Z / 2026-10-24T18:54:46Z |
| Host CPU avg / max | 28% / 84% |
| Generator CPU avg | 0% |

## Tables

| Table | Rows generated | Rows/simulated s | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row (data) | Bytes/row (disk) | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 3,598,712 | 2,499 | 710 | 1 | 2,427 | 117 | 2.58 | 82.29 | 85.61 | 17 |
| profile_step | 6,117,165 | 4,248 | 710 | 1 | 4,126 | 46 | 7.26 | 14.24 | 14.29 | 11 |
| metric | 1,440,000 | 1,000 | 592 | 1 | 971 | 45 | 11.64 | 2.66 | 2.67 | 4 |
| sql_stat_raw | 255,666 | 178 | 301 | 1 | 173 | 42 | 5.05 | 27.36 | 27.43 | 10 |
| ext_stat_raw | 32,102 | 22 | 301 | 1 | 22 | 35 | 5.18 | 21.22 | 21.31 | 4 |

Bytes/row (data) = data_compressed_bytes / rows (the compression basis of 13 §15); Bytes/row (disk) = bytes_on_disk / rows, with skip indexes, marks and primary index (the capacity basis of 13 §14, N-19).

Simulation: tick 100 ms, 50 instances, active SQL per instance 0 (0 = usual statements), statistics rows/simulated s 200, all rows/simulated s 7,947.

Total: 11,443,645 rows in 1484.64 s = 7,708 rows/s, 1.01 MiB/s RowBinary.

Rollup tables after the load:

| Table | Rows | Compression | Bytes/row (data) | Bytes/row (disk) |
|---|---:|---:|---:|---:|
| app_stat_1h | 203 | 1.65 | 2172.40 | 2281.27 |
| app_stat_1m | 9,648 | 2.17 | 802.93 | 812.11 |
| error_stat_1m | 69,551 | 4.07 | 14.25 | 14.66 |
| ext_stat_1h | 1,429 | 3.59 | 52.93 | 63.97 |
| ext_stat_1m | 35,873 | 4.63 | 41.04 | 41.79 |
| instance_stat_1m | 30,576 | 2.66 | 621.43 | 625.73 |
| metric_1h | 27,601 | 3.59 | 17.84 | 19.55 |
| metric_1m | 1,167,810 | 4.24 | 14.85 | 14.96 |
| service_stat_1h | 2,904 | 1.88 | 1011.23 | 1027.61 |
| service_stat_1m | 79,642 | 2.80 | 284.81 | 286.75 |
| sql_stat_1h | 50,784 | 4.61 | 44.67 | 45.57 |
| sql_stat_1m | 451,216 | 5.22 | 38.05 | 38.44 |

Raw counts match the generator counters: false

ClickHouse during the run: max active parts per partition 15, peak MemoryTracking 473 MiB, max merges 1, DelayedInserts 0.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 22,484,677 | 22,484,677 |
| SQL time (ms) | 380,079,773 | 380,079,773 |
| Outgoing calls | 1,493,552 | 1,493,552 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

## Ingest lag (receive to ack)

All rows: p50 1,119 ms, p99 2,143 ms, max 2,680 ms.

| TPS step | Rows | p50 ms | p99 ms | max ms |
|---:|---:|---:|---:|---:|
| 500.0 | 1,192,014 | 1,119 | 2,143 | 2,680 |
| 2000.0 | 3,150,406 | 1,119 | 2,143 | 2,303 |
| 5000.0 | 7,058,537 | 1,135 | 2,175 | 2,471 |

