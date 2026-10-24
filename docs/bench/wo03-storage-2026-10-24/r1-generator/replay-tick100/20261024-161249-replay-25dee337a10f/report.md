# Storage generator run 25dee337a10f

| Item | Value |
|---|---|
| Mode | replay |
| Seed / scale | 42 / 1.0 |
| Target | clickhouse argos_writer argos.* |
| Machine profile | - |
| Range | 2026-10-24T16:08:04Z .. 2026-10-24T16:12:04Z |
| profile_step from | 2026-10-24T16:08:04Z |
| metric from | 2026-10-24T16:08:04Z |
| Version switch / deploy | 2026-10-24T16:12:04Z / 2026-10-24T16:12:04Z |
| Host CPU avg / max | 47% / 99% |
| Generator CPU avg | 1% |

## Tables

| Table | Rows generated | Rows/simulated s | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row (data) | Bytes/row (disk) | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 841,111 | 3,505 | 131 | 1 | 2,974 | 237 | 2.61 | 81.22 | 84.78 | 5 |
| profile_step | 1,424,003 | 5,933 | 131 | 1 | 5,038 | 122 | 7.33 | 14.16 | 14.29 | 7 |
| metric | 240,000 | 1,000 | 99 | 1 | 849 | 80 | 7.72 | 4.09 | 4.19 | 4 |
| sql_stat_raw | 51,166 | 213 | 57 | 1 | 184 | 77 | 4.57 | 30.25 | 30.73 | 1 |
| ext_stat_raw | 5,726 | 24 | 56 | 1 | 21 | 62 | 4.02 | 28.42 | 30.29 | 1 |

Bytes/row (data) = data_compressed_bytes / rows (the compression basis of 13 §15); Bytes/row (disk) = bytes_on_disk / rows, with skip indexes, marks and primary index (the capacity basis of 13 §14, N-19).

Simulation: tick 100 ms, 50 instances, active SQL per instance 0 (0 = usual statements), statistics rows/simulated s 237, all rows/simulated s 10,675.

Total: 2,562,006 rows in 284.89 s = 8,993 rows/s, 1.20 MiB/s RowBinary.

Rollup tables after the load:

| Table | Rows | Compression | Bytes/row (data) | Bytes/row (disk) |
|---|---:|---:|---:|---:|
| app_stat_1h | 20 | 1.63 | 2318.90 | 2568.05 |
| app_stat_1m | 101 | 1.78 | 1510.03 | 1609.42 |
| error_stat_1m | 1,498 | 3.59 | 16.19 | 24.73 |
| ext_stat_1h | 70 | 2.37 | 80.40 | 160.80 |
| ext_stat_1m | 344 | 3.24 | 58.64 | 79.83 |
| instance_stat_1m | 301 | 1.90 | 1377.93 | 1420.53 |
| metric_1h | 7,300 | 3.75 | 17.40 | 23.45 |
| metric_1m | 27,200 | 3.99 | 16.03 | 17.79 |
| service_stat_1h | 110 | 1.77 | 1775.94 | 1884.89 |
| service_stat_1m | 551 | 2.13 | 948.15 | 977.35 |
| sql_stat_1h | 2,250 | 3.77 | 54.45 | 65.79 |
| sql_stat_1m | 7,006 | 4.65 | 44.20 | 48.35 |

Raw counts match the generator counters: true

ClickHouse during the run: max active parts per partition 10, peak MemoryTracking 239 MiB, max merges 1, DelayedInserts 0.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 5,286,456 | 5,286,456 |
| SQL time (ms) | 90,550,888 | 90,550,888 |
| Outgoing calls | 348,022 | 348,022 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

## Ingest lag (receive to ack)

All rows: p50 1,199 ms, p99 2,239 ms, max 2,597 ms.

| TPS step | Rows | p50 ms | p99 ms | max ms |
|---:|---:|---:|---:|---:|
| 2000.0 | 786,526 | 1,151 | 2,175 | 2,439 |
| 5000.0 | 1,764,166 | 1,215 | 2,271 | 2,597 |

