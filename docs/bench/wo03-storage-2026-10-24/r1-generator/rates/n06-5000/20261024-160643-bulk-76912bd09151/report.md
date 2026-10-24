# Storage generator run 76912bd09151

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
| Host CPU avg / max | 39% / 54% |
| Generator CPU avg | 14% |

## Tables

| Table | Rows generated | Rows/simulated s | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row (data) | Bytes/row (disk) | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 3,003,163 | 5,005 | 7 | 1 | 158,797 | 120 | - | - | - | - |
| profile_step | 30,002,894 | 50,005 | 61 | 1 | 1,319,388 | 36 | - | - | - | - |
| metric | 3,600,000 | 6,000 | 8 | 1 | 184,729 | 33 | - | - | - | - |
| sql_stat_raw | 3,164,094 | 5,273 | 7 | 1 | 168,196 | 49 | - | - | - | - |
| ext_stat_raw | 74,585 | 124 | 1 | 1 | 12,430,833 | 6 | - | - | - | - |

Bytes/row (data) = data_compressed_bytes / rows (the compression basis of 13 §15); Bytes/row (disk) = bytes_on_disk / rows, with skip indexes, marks and primary index (the capacity basis of 13 §14, N-19).

Simulation: tick 1,000 ms, 300 instances, active SQL per instance 200 (0 = usual statements), statistics rows/simulated s 5,398, all rows/simulated s 66,408, profile_step rows/s target 50,000 achieved 50,005 (normal capture rate mean 0.73).

Total: 39,844,736 rows in 23.73 s = 1,679,370 rows/s, 166.60 MiB/s RowBinary.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 18,876,982 | 18,876,982 |
| SQL time (ms) | 319,481,795 | 319,481,795 |
| Outgoing calls | 1,244,397 | 1,244,397 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

