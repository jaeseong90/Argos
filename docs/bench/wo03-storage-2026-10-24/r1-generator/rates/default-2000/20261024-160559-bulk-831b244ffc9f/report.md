# Storage generator run 831b244ffc9f

| Item | Value |
|---|---|
| Mode | bulk |
| Seed / scale | 42 / 0.00694444444 |
| Target | none (count only) |
| Machine profile | - |
| Range | 2026-10-20T12:00:00Z .. 2026-10-20T12:10:00Z |
| profile_step from | 2026-10-20T12:00:00Z |
| metric from | 2026-10-20T12:00:00Z |
| Version switch / deploy | 2026-10-20T12:10:00Z / 2026-10-20T12:10:00Z |
| Host CPU avg / max | 22% / 43% |
| Generator CPU avg | 17% |

## Tables

| Table | Rows generated | Rows/simulated s | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row (data) | Bytes/row (disk) | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 1,201,906 | 2,003 | 3 | 1 | 480,762 | 86 | - | - | - | - |
| profile_step | 2,042,920 | 3,405 | 5 | 1 | 637,019 | 53 | - | - | - | - |
| metric | 600,000 | 1,000 | 2 | 1 | 923,077 | 26 | - | - | - | - |
| sql_stat_raw | 107,315 | 179 | 1 | 1 | 8,942,917 | 12 | - | - | - | - |
| ext_stat_raw | 13,650 | 23 | 1 | 1 | 13,650,000 | 1 | - | - | - | - |

Bytes/row (data) = data_compressed_bytes / rows (the compression basis of 13 §15); Bytes/row (disk) = bytes_on_disk / rows, with skip indexes, marks and primary index (the capacity basis of 13 §14, N-19).

Simulation: tick 1,000 ms, 50 instances, active SQL per instance 0 (0 = usual statements), statistics rows/simulated s 202, all rows/simulated s 6,610.

Total: 3,965,791 rows in 4.84 s = 820,056 rows/s, 105.71 MiB/s RowBinary.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 7,527,337 | 7,527,337 |
| SQL time (ms) | 128,272,092 | 128,272,092 |
| Outgoing calls | 498,158 | 498,158 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

