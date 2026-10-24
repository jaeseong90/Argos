# Storage generator run wo03local

| Item | Value |
|---|---|
| Mode | replay |
| Seed / scale | 7 / 1.0 |
| Target | clickhouse argos_writer argos.* |
| Machine profile | wo03-local-r5-5600h |
| Range | 2026-10-24T10:04:24Z .. 2026-10-24T10:34:24Z |
| profile_step from | 2026-10-24T10:04:24Z |
| metric from | 2026-10-24T10:04:24Z |
| Version switch / deploy | 2026-10-24T10:34:24Z / 2026-10-24T10:34:24Z |
| Host CPU avg / max | 25% / 100% |
| Generator CPU avg | 0% |

## Tables

| Table | Rows generated | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 4,501,626 | 678 | 2 | 2,444 | 147 | 2.57 | 82.58 | 28 |
| profile_step | 7,754,443 | 680 | 3 | 4,210 | 72 | 7.21 | 14.42 | 12 |
| metric | 1,800,000 | 642 | 1 | 977 | 70 | 11.95 | 2.60 | 14 |
| sql_stat_raw | 318,947 | 382 | 2 | 174 | 54 | 5.09 | 27.13 | 22 |
| ext_stat_raw | 40,076 | 380 | 2 | 22 | 48 | 5.16 | 21.37 | 11 |

Total: 14,415,092 rows in 1844.16 s = 7,817 rows/s, 1.02 MiB/s RowBinary.

Rollup tables after the load:

| Table | Rows | Compression | Bytes/row |
|---|---:|---:|---:|
| app_stat_1h | 805 | 1.69 | 2160.26 |
| app_stat_1m | 44,934 | 2.20 | 792.55 |
| error_stat_1m | 319,568 | 4.01 | 14.48 |
| ext_stat_1h | 3,394 | 3.19 | 59.54 |
| ext_stat_1m | 152,925 | 4.49 | 42.34 |
| instance_stat_1m | 136,057 | 2.73 | 635.03 |
| metric_1h | 100,301 | 3.68 | 17.26 |
| metric_1m | 5,207,410 | 4.28 | 14.71 |
| service_stat_1h | 7,820 | 1.89 | 1319.59 |
| service_stat_1m | 369,598 | 2.84 | 279.89 |
| sql_stat_1h | 112,170 | 4.44 | 46.40 |
| sql_stat_1m | 1,812,669 | 5.27 | 37.73 |

Raw counts match the generator counters: false

ClickHouse during the run: max active parts per partition 14, peak MemoryTracking 729 MiB, max merges 4, DelayedInserts 0.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 28,128,119 | 28,128,119 |
| SQL time (ms) | 477,820,157 | 477,820,157 |
| Outgoing calls | 1,866,051 | 1,866,051 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

## Ingest lag (receive to ack)

All rows: p50 1,631 ms, p99 3,071 ms, max 3,873 ms.

| TPS step | Rows | p50 ms | p99 ms | max ms |
|---:|---:|---:|---:|---:|
| 500.0 | 1,495,012 | 1,615 | 3,039 | 3,873 |
| 2000.0 | 3,954,687 | 1,615 | 3,039 | 3,139 |
| 5000.0 | 8,912,968 | 1,631 | 3,071 | 3,273 |

