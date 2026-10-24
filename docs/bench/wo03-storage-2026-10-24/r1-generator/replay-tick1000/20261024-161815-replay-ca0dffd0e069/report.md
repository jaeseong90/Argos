# Storage generator run ca0dffd0e069

| Item | Value |
|---|---|
| Mode | replay |
| Seed / scale | 42 / 1.0 |
| Target | clickhouse argos_writer argos.* |
| Machine profile | - |
| Range | 2026-10-24T16:13:30Z .. 2026-10-24T16:17:30Z |
| profile_step from | 2026-10-24T16:13:30Z |
| metric from | 2026-10-24T16:13:30Z |
| Version switch / deploy | 2026-10-24T16:17:30Z / 2026-10-24T16:17:30Z |
| Host CPU avg / max | 63% / 100% |
| Generator CPU avg | 1% |

## Tables

| Table | Rows generated | Rows/simulated s | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row (data) | Bytes/row (disk) | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 841,133 | 3,505 | 107 | 1 | 2,980 | 783 | 2.61 | 81.12 | 84.76 | 7 |
| profile_step | 1,421,059 | 5,921 | 106 | 1 | 5,038 | 527 | 7.29 | 14.22 | 14.35 | 5 |
| metric | 240,000 | 1,000 | 88 | 1 | 851 | 399 | 7.69 | 4.10 | 4.19 | 3 |
| sql_stat_raw | 50,769 | 212 | 58 | 1 | 181 | 463 | 4.57 | 30.24 | 30.72 | 1 |
| ext_stat_raw | 5,690 | 24 | 57 | 1 | 20 | 463 | 3.99 | 28.65 | 31.25 | 2 |

Bytes/row (data) = data_compressed_bytes / rows (the compression basis of 13 §15); Bytes/row (disk) = bytes_on_disk / rows, with skip indexes, marks and primary index (the capacity basis of 13 §14, N-19).

Simulation: tick 1,000 ms, 50 instances, active SQL per instance 0 (0 = usual statements), statistics rows/simulated s 235, all rows/simulated s 10,661.

Total: 2,558,651 rows in 284.37 s = 8,997 rows/s, 1.20 MiB/s RowBinary.

Rollup tables after the load:

| Table | Rows | Compression | Bytes/row (data) | Bytes/row (disk) |
|---|---:|---:|---:|---:|
| app_stat_1h | 29 | 1.64 | 1731.14 | 2048.45 |
| app_stat_1m | 113 | 1.81 | 1435.35 | 1563.50 |
| error_stat_1m | 1,600 | 3.47 | 16.73 | 30.18 |
| ext_stat_1h | 97 | 2.40 | 78.72 | 177.00 |
| ext_stat_1m | 385 | 3.21 | 59.12 | 88.68 |
| instance_stat_1m | 314 | 1.89 | 1400.98 | 1457.08 |
| metric_1h | 8,200 | 3.80 | 16.96 | 21.84 |
| metric_1m | 28,400 | 3.79 | 16.84 | 18.40 |
| service_stat_1h | 184 | 1.78 | 1094.78 | 1200.17 |
| service_stat_1m | 646 | 2.17 | 860.45 | 897.61 |
| sql_stat_1h | 2,322 | 3.76 | 54.49 | 67.24 |
| sql_stat_1m | 7,477 | 4.71 | 43.70 | 48.23 |

Raw counts match the generator counters: true

ClickHouse during the run: max active parts per partition 9, peak MemoryTracking 353 MiB, max merges 1, DelayedInserts 0.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 5,253,827 | 5,253,827 |
| SQL time (ms) | 89,940,517 | 89,940,517 |
| Outgoing calls | 348,248 | 348,248 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

## Ingest lag (receive to ack)

All rows: p50 1,839 ms, p99 3,775 ms, max 5,679 ms.

| TPS step | Rows | p50 ms | p99 ms | max ms |
|---:|---:|---:|---:|---:|
| 2000.0 | 776,312 | 1,695 | 3,103 | 3,313 |
| 5000.0 | 1,768,136 | 1,887 | 3,967 | 5,679 |

