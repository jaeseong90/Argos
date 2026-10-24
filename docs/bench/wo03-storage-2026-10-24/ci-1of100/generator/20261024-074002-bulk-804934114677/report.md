# Storage generator run 804934114677

| Item | Value |
|---|---|
| Mode | bulk |
| Seed / scale | 42 / 0.01 |
| Target | clickhouse argos_writer argos.* |
| Machine profile | - |
| Range | 2026-10-23T20:24:00Z .. 2026-10-24T03:36:00Z |
| profile_step from | 2026-10-24T01:55:12Z |
| metric from | 2026-10-24T00:14:24Z |
| Version switch / deploy | 2026-10-23T23:45:36Z / 2026-10-23T23:45:36Z |
| Host CPU avg / max | 74% / 100% |
| Generator CPU avg | 11% |

## Tables

| Table | Rows generated | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 12,961,791 | 26 | 1 | 170,707 | 4,991 | 2.56 | 82.72 | 9 |
| profile_step | 5,148,066 | 11 | 1 | 219,132 | 3,800 | 7.15 | 14.50 | 6 |
| metric | 12,096,300 | 25 | 1 | 277,578 | 1,823 | 11.87 | 2.61 | 3 |
| sql_stat_raw | 3,172,102 | 7 | 1 | 48,385 | 5,970 | 5.06 | 27.29 | 8 |
| ext_stat_raw | 542,241 | 2 | 1 | 64,923 | 3,008 | 5.14 | 21.44 | 3 |

Total: 33,920,500 rows in 77.48 s = 437,808 rows/s, 59.12 MiB/s RowBinary.

Rollup tables after the load:

| Table | Rows | Compression | Bytes/row |
|---|---:|---:|---:|
| app_stat_1h | 160 | 1.65 | 2288.15 |
| app_stat_1m | 9,820 | 2.19 | 719.81 |
| error_stat_1m | 62,389 | 4.03 | 14.39 |
| ext_stat_1h | 668 | 2.77 | 68.63 |
| ext_stat_1m | 29,176 | 4.44 | 42.81 |
| instance_stat_1m | 27,507 | 2.74 | 611.74 |
| metric_1h | 41,500 | 3.44 | 18.51 |
| metric_1m | 1,040,300 | 4.24 | 14.88 |
| service_stat_1h | 2,891 | 1.95 | 1077.77 |
| service_stat_1m | 75,657 | 2.84 | 267.80 |
| sql_stat_1h | 18,535 | 4.19 | 49.22 |
| sql_stat_1m | 343,149 | 5.16 | 38.50 |

Raw counts match the generator counters: true

ClickHouse during the run: max active parts per partition 10, peak MemoryTracking 718 MiB, max merges 2, DelayedInserts 0.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 80,968,953 | 80,968,953 |
| SQL time (ms) | 1,383,115,627 | 1,383,115,627 |
| Outgoing calls | 5,372,100 | 5,372,100 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

