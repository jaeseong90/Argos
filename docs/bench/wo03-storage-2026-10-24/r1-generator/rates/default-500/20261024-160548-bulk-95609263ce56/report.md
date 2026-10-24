# Storage generator run 95609263ce56

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
| Host CPU avg / max | 0% / 0% |
| Generator CPU avg | 9% |

## Tables

| Table | Rows generated | Rows/simulated s | Blocks | Re-sent | Rows/s while inserting | Insert p95 ms | Compression | Bytes/row (data) | Bytes/row (disk) | Active parts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| xlog | 300,974 | 502 | 1 | 1 | 5,016,233 | 60 | - | - | - | - |
| profile_step | 511,405 | 852 | 2 | 1 | 2,519,236 | 61 | - | - | - | - |
| metric | 600,000 | 1,000 | 2 | 1 | 1,202,405 | 71 | - | - | - | - |
| sql_stat_raw | 74,016 | 123 | 1 | 1 | 10,573,714 | 7 | - | - | - | - |
| ext_stat_raw | 12,669 | 21 | 1 | 1 | 6,334,500 | 2 | - | - | - | - |

Bytes/row (data) = data_compressed_bytes / rows (the compression basis of 13 §15); Bytes/row (disk) = bytes_on_disk / rows, with skip indexes, marks and primary index (the capacity basis of 13 §14, N-19).

Simulation: tick 1,000 ms, 50 instances, active SQL per instance 0 (0 = usual statements), statistics rows/simulated s 144, all rows/simulated s 2,498.

Total: 1,499,064 rows in 2.78 s = 539,038 rows/s, 59.88 MiB/s RowBinary.

## D-12 totals

| Sum | xlog | statistics |
|---|---:|---:|
| SQL executions | 1,884,321 | 1,884,321 |
| SQL time (ms) | 32,020,474 | 32,020,474 |
| Outgoing calls | 124,484 | 124,484 |

Per-day values (by xlog ts and by statistics window ts) are in manifest.json.

