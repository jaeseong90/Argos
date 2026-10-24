# Storage generator run c7d4121c8afa

| Item | Value |
|---|---|
| Mode | replay |
| Seed / scale | 42 / 1.0 |
| Target | none (count only) |
| Machine profile | - |
| Range | 2026-10-24T15:59:39Z .. 2026-10-24T16:04:39Z |
| profile_step from | 2026-10-24T15:59:39Z |
| metric from | 2026-10-24T15:59:39Z |
| Version switch / deploy | 2026-10-24T16:04:39Z / 2026-10-24T16:04:39Z |
| Host CPU avg / max | 34% / 100% |
| Generator CPU avg | 1% |

## Tables

| Table | Rows generated | Rows/simulated s | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row (data) | Bytes/row (disk) | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 1,503,437 | 5,011 | 163 | 1 | 4,389 | 2 | - | - | - | - |
| profile_step | 15,005,254 | 50,018 | 302 | 1 | 43,706 | 5 | - | - | - | - |
| metric | 1,800,000 | 6,000 | 136 | 2 | 5,255 | 1 | - | - | - | - |
| sql_stat_raw | 1,590,014 | 5,300 | 78 | 1 | 4,652 | 3 | - | - | - | - |
| ext_stat_raw | 37,714 | 126 | 76 | 1 | 110 | 0 | - | - | - | - |

Bytes/row (data) = data_compressed_bytes / rows (the compression basis of 13 §15); Bytes/row (disk) = bytes_on_disk / rows, with skip indexes, marks and primary index (the capacity basis of 13 §14, N-19).

Simulation: tick 100 ms, 300 instances, active SQL per instance 200 (0 = usual statements), statistics rows/simulated s 5,426, all rows/simulated s 66,455, profile_step rows/s target 50,000 achieved 50,018 (normal capture rate mean 0.73).

Total: 19,936,419 rows in 344.74 s = 57,830 rows/s, 5.74 MiB/s RowBinary.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 9,379,086 | 9,379,086 |
| SQL time (ms) | 159,897,952 | 159,897,952 |
| Outgoing calls | 623,392 | 623,392 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

## Ingest lag (receive to ack)

All rows: p50 631 ms, p99 2,015 ms, max 2,383 ms.

| TPS step | Rows | p50 ms | p99 ms | max ms |
|---:|---:|---:|---:|---:|
| 5000.0 | 19,845,013 | 631 | 2,015 | 2,383 |

