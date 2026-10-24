# Storage generator run qfixlive

| Item | Value |
|---|---|
| Mode | replay |
| Seed / scale | 7 / 1.0 |
| Target | clickhouse argos_writer argos.* |
| Machine profile | wo03-local-r5-5600h |
| Range | 2026-10-24T16:40:52Z .. 2026-10-24T17:00:52Z |
| profile_step from | 2026-10-24T16:40:52Z |
| metric from | 2026-10-24T16:40:52Z |
| Version switch / deploy | 2026-10-24T17:00:52Z / 2026-10-24T17:00:52Z |
| Host CPU avg / max | 29% / 100% |
| Generator CPU avg | 0% |

## Tables

| Table | Rows generated | Rows/simulated s | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row (data) | Bytes/row (disk) | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 4,202,119 | 3,502 | 593 | 2 | 3,382 | 155 | 2.58 | 82.24 | 85.56 | 16 |
| profile_step | 7,255,387 | 6,046 | 592 | 3 | 5,840 | 61 | 7.30 | 14.26 | 14.29 | 7 |
| metric | 1,200,000 | 1,000 | 484 | 1 | 967 | 45 | 11.51 | 2.69 | 2.70 | 9 |
| sql_stat_raw | 245,223 | 204 | 283 | 2 | 198 | 48 | 5.04 | 27.37 | 27.43 | 7 |
| ext_stat_raw | 27,489 | 23 | 287 | 1 | 22 | 39 | 5.08 | 21.80 | 21.92 | 6 |

Bytes/row (data) = data_compressed_bytes / rows (the compression basis of 13 §15); Bytes/row (disk) = bytes_on_disk / rows, with skip indexes, marks and primary index (the capacity basis of 13 §14, N-19).

Simulation: tick 100 ms, 50 instances, active SQL per instance 0 (0 = usual statements), statistics rows/simulated s 227, all rows/simulated s 10,775.

Total: 12,930,218 rows in 1244.63 s = 10,389 rows/s, 1.38 MiB/s RowBinary.

Rollup tables after the load:

| Table | Rows | Compression | Bytes/row (data) | Bytes/row (disk) |
|---|---:|---:|---:|---:|
| app_stat_1h | 230 | 1.65 | 2093.17 | 2208.84 |
| app_stat_1m | 9,523 | 2.18 | 808.92 | 818.90 |
| error_stat_1m | 69,171 | 4.10 | 14.15 | 14.56 |
| ext_stat_1h | 765 | 2.73 | 69.56 | 97.52 |
| ext_stat_1m | 30,515 | 4.38 | 43.38 | 44.25 |
| instance_stat_1m | 28,512 | 2.65 | 658.34 | 662.92 |
| metric_1h | 37,500 | 3.58 | 17.82 | 19.64 |
| metric_1m | 1,138,000 | 4.25 | 14.82 | 14.88 |
| service_stat_1h | 2,064 | 1.83 | 1322.41 | 1344.30 |
| service_stat_1m | 76,050 | 2.79 | 295.99 | 298.01 |
| sql_stat_1h | 20,934 | 4.10 | 50.14 | 52.36 |
| sql_stat_1m | 371,591 | 5.20 | 38.28 | 38.69 |

Raw counts match the generator counters: false

ClickHouse during the run: max active parts per partition 13, peak MemoryTracking 428 MiB, max merges 2, DelayedInserts 0.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 26,189,803 | 26,189,803 |
| SQL time (ms) | 446,901,502 | 446,901,502 |
| Outgoing calls | 1,742,357 | 1,742,357 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

## Ingest lag (receive to ack)

All rows: p50 1,119 ms, p99 2,175 ms, max 3,066 ms.

| TPS step | Rows | p50 ms | p99 ms | max ms |
|---:|---:|---:|---:|---:|
| 2000.0 | 3,968,340 | 1,087 | 2,143 | 2,912 |
| 5000.0 | 8,913,899 | 1,119 | 2,175 | 3,066 |

