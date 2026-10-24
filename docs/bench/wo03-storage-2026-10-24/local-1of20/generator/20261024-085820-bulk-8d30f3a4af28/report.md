# Storage generator run 8d30f3a4af28

| Item | Value |
|---|---|
| Mode | bulk |
| Seed / scale | 42 / 0.05 |
| Target | clickhouse argos_writer argos.* |
| Machine profile | wo03-local-r5-5600h |
| Range | 2026-10-22T20:00:00Z .. 2026-10-24T08:00:00Z |
| profile_step from | 2026-10-23T23:36:00Z |
| metric from | 2026-10-23T15:12:00Z |
| Version switch / deploy | 2026-10-23T12:48:00Z / 2026-10-23T12:48:00Z |
| Host CPU avg / max | 76% / 100% |
| Generator CPU avg | 7% |

## Tables

| Table | Rows generated | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 64,807,447 | 130 | 1 | 128,493 | 7,295 | 2.56 | 82.71 | 25 |
| profile_step | 25,691,012 | 52 | 1 | 155,847 | 3,423 | 7.16 | 14.49 | 13 |
| metric | 60,480,000 | 121 | 2 | 187,922 | 2,975 | 11.84 | 2.62 | 16 |
| sql_stat_raw | 15,866,840 | 32 | 1 | 32,085 | 6,847 | 5.10 | 27.08 | 15 |
| ext_stat_raw | 2,713,412 | 6 | 1 | 5,981 | 4,238 | 5.15 | 21.40 | 8 |

Total: 169,558,711 rows in 505.92 s = 335,148 rows/s, 45.26 MiB/s RowBinary.

Rollup tables after the load:

| Table | Rows | Compression | Bytes/row |
|---|---:|---:|---:|
| app_stat_1h | 745 | 1.69 | 2199.43 |
| app_stat_1m | 44,622 | 2.21 | 778.52 |
| error_stat_1m | 310,573 | 4.01 | 14.48 |
| ext_stat_1h | 2,674 | 2.89 | 65.80 |
| ext_stat_1m | 145,718 | 4.45 | 42.69 |
| instance_stat_1m | 132,142 | 2.75 | 633.52 |
| metric_1h | 112,900 | 3.60 | 17.64 |
| metric_1m | 5,089,500 | 4.25 | 14.83 |
| service_stat_1h | 7,564 | 1.90 | 1378.44 |
| service_stat_1m | 363,254 | 2.86 | 276.02 |
| sql_stat_1h | 81,652 | 4.24 | 48.63 |
| sql_stat_1m | 1,694,669 | 5.25 | 37.80 |

Raw counts match the generator counters: true

ClickHouse during the run: max active parts per partition 16, peak MemoryTracking 848 MiB, max merges 3, DelayedInserts 0.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 405,367,977 | 405,367,977 |
| SQL time (ms) | 6,919,250,612 | 6,919,250,612 |
| Outgoing calls | 26,854,223 | 26,854,223 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

