# Storage generator run c3cd0823acd5

| Item | Value |
|---|---|
| Mode | replay |
| Seed / scale | 42 / 1.0 |
| Target | clickhouse argos_writer argos.* |
| Machine profile | - |
| Range | 2026-10-24T07:42:30Z .. 2026-10-24T07:48:30Z |
| profile_step from | 2026-10-24T07:42:30Z |
| metric from | 2026-10-24T07:42:30Z |
| Version switch / deploy | 2026-10-24T07:48:30Z / 2026-10-24T07:48:30Z |
| Host CPU avg / max | 33% / 98% |
| Generator CPU avg | 1% |

## Tables

| Table | Rows generated | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 901,250 | 149 | 1 | 2,242 | 185 | 2.61 | 81.12 | 6 |
| profile_step | 1,539,649 | 147 | 1 | 3,830 | 97 | 7.40 | 14.02 | 5 |
| metric | 360,000 | 132 | 2 | 896 | 71 | 8.11 | 3.83 | 7 |
| sql_stat_raw | 65,308 | 82 | 1 | 163 | 63 | 4.61 | 30.00 | 5 |
| ext_stat_raw | 8,202 | 81 | 1 | 20 | 60 | 4.32 | 26.42 | 1 |

Total: 2,874,409 rows in 404.13 s = 7,113 rows/s, 0.93 MiB/s RowBinary.

Rollup tables after the load:

| Table | Rows | Compression | Bytes/row |
|---|---:|---:|---:|
| app_stat_1h | 42 | 1.65 | 1381.43 |
| app_stat_1m | 203 | 1.85 | 1034.35 |
| error_stat_1m | 1,869 | 3.70 | 15.69 |
| ext_stat_1h | 70 | 2.34 | 81.34 |
| ext_stat_1m | 485 | 3.50 | 54.36 |
| instance_stat_1m | 527 | 1.95 | 1035.56 |
| metric_1h | 5,900 | 3.82 | 16.85 |
| metric_1m | 35,900 | 3.92 | 16.23 |
| service_stat_1h | 226 | 1.79 | 954.71 |
| service_stat_1m | 770 | 2.21 | 821.71 |
| sql_stat_1h | 2,151 | 3.73 | 55.08 |
| sql_stat_1m | 8,635 | 4.79 | 43.03 |

Raw counts match the generator counters: true

ClickHouse during the run: max active parts per partition 10, peak MemoryTracking 306 MiB, max merges 4, DelayedInserts 0.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 5,685,927 | 5,685,927 |
| SQL time (ms) | 95,998,218 | 95,998,218 |
| Outgoing calls | 372,904 | 372,904 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

## Ingest lag (receive to ack)

All rows: p50 1,679 ms, p99 3,071 ms, max 3,356 ms.

| TPS step | Rows | p50 ms | p99 ms | max ms |
|---:|---:|---:|---:|---:|
| 500.0 | 299,295 | 1,631 | 3,039 | 3,137 |
| 2000.0 | 802,673 | 1,679 | 3,071 | 3,254 |
| 5000.0 | 1,758,765 | 1,679 | 3,103 | 3,356 |

