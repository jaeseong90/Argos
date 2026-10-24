# Storage generator run 823a0cfb9fc3

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
| Host CPU avg / max | 33% / 51% |
| Generator CPU avg | 15% |

## Tables

| Table | Rows generated | Rows/simulated s | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row (data) | Bytes/row (disk) | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 3,002,854 | 5,005 | 7 | 1 | 384,095 | 83 | - | - | - | - |
| profile_step | 5,115,882 | 8,526 | 11 | 1 | 610,123 | 53 | - | - | - | - |
| metric | 600,000 | 1,000 | 2 | 1 | 407,332 | 26 | - | - | - | - |
| sql_stat_raw | 142,440 | 237 | 1 | 1 | 10,174,286 | 14 | - | - | - | - |
| ext_stat_raw | 14,030 | 23 | 1 | 1 | 7,015,000 | 2 | - | - | - | - |

Bytes/row (data) = data_compressed_bytes / rows (the compression basis of 13 §15); Bytes/row (disk) = bytes_on_disk / rows, with skip indexes, marks and primary index (the capacity basis of 13 §14, N-19).

Simulation: tick 1,000 ms, 50 instances, active SQL per instance 0 (0 = usual statements), statistics rows/simulated s 261, all rows/simulated s 14,792.

Total: 8,875,206 rows in 10.10 s = 879,081 rows/s, 118.59 MiB/s RowBinary.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 18,718,447 | 18,718,447 |
| SQL time (ms) | 321,701,894 | 321,701,894 |
| Outgoing calls | 1,244,158 | 1,244,158 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

