# Storage generator run 07b3257d13ab

| Item | Value |
|---|---|
| Mode | bulk |
| Seed / scale | 42 / 0.01 |
| Target | clickhouse argos_writer argos.* |
| Machine profile | wo03-local-r5-5600h |
| Range | 2026-10-23T20:24:00Z .. 2026-10-24T03:36:00Z |
| profile_step from | 2026-10-24T01:55:12Z |
| metric from | 2026-10-24T00:14:24Z |
| Version switch / deploy | 2026-10-23T23:45:36Z / 2026-10-23T23:45:36Z |
| Host CPU avg / max | 81% / 100% |
| Generator CPU avg | 8% |

## Tables

| Table | Rows generated | Rows/simulated s | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row (data) | Bytes/row (disk) | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 12,961,791 | 500 | 26 | 1 | 110,043 | 7,295 | 2.56 | 82.72 | 86.03 | 9 |
| profile_step | 5,148,066 | 851 | 11 | 1 | 146,840 | 3,691 | 7.15 | 14.50 | 14.56 | 6 |
| metric | 12,096,300 | 1,000 | 25 | 1 | 181,012 | 3,807 | 11.87 | 2.61 | 2.62 | 3 |
| sql_stat_raw | 3,172,102 | 122 | 7 | 1 | 31,531 | 5,686 | 5.06 | 27.29 | 27.36 | 8 |
| ext_stat_raw | 542,241 | 21 | 2 | 1 | 42,090 | 3,802 | 5.14 | 21.44 | 21.51 | 3 |

Bytes/row (data) = data_compressed_bytes / rows (the compression basis of 13 §15); Bytes/row (disk) = bytes_on_disk / rows, with skip indexes, marks and primary index (the capacity basis of 13 §14, N-19).

Simulation: tick 1,000 ms, 50 instances, active SQL per instance 0 (0 = usual statements), statistics rows/simulated s 143, all rows/simulated s 2,495.

Total: 33,920,500 rows in 119.95 s = 282,796 rows/s, 38.19 MiB/s RowBinary.

Rollup tables after the load:

| Table | Rows | Compression | Bytes/row (data) | Bytes/row (disk) |
|---|---:|---:|---:|---:|
| app_stat_1h | 160 | 1.65 | 2288.15 | 2348.45 |
| app_stat_1m | 9,820 | 2.19 | 719.81 | 732.77 |
| error_stat_1m | 62,389 | 4.03 | 14.39 | 15.67 |
| ext_stat_1h | 668 | 2.77 | 68.63 | 92.77 |
| ext_stat_1m | 29,176 | 4.44 | 42.81 | 44.02 |
| instance_stat_1m | 27,507 | 2.74 | 611.74 | 616.88 |
| metric_1h | 41,500 | 3.44 | 18.51 | 20.65 |
| metric_1m | 1,040,300 | 4.24 | 14.88 | 14.97 |
| service_stat_1h | 2,891 | 1.95 | 1077.89 | 1108.33 |
| service_stat_1m | 75,657 | 2.84 | 267.80 | 270.28 |
| sql_stat_1h | 18,535 | 4.19 | 49.22 | 51.41 |
| sql_stat_1m | 343,149 | 5.16 | 38.50 | 39.09 |

Raw counts match the generator counters: true

ClickHouse during the run: max active parts per partition 9, peak MemoryTracking 860 MiB, max merges 2, DelayedInserts 0.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 80,968,953 | 80,968,953 |
| SQL time (ms) | 1,383,115,627 | 1,383,115,627 |
| Outgoing calls | 5,372,100 | 5,372,100 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

