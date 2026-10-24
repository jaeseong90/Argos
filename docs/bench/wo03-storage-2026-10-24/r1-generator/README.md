# WO-03 review fixes r1: generator evidence (2026-10-24)

Raw results behind the generator changes of the WO-03 review round 1 (findings bench-method-1, -6, -9) in
[`docs/poc/clickhouse-benchmark.md`](../../../poc/clickhouse-benchmark.md) sections 4.2, 5.1 and 9. Same
machine and conditions as the parent directory ([README](../README.md)); reference values only. Output paths
in `manifest.json` are shortened to `<scratch>`.

| Directory | Run (UTC) | What |
|---|---|---|
| `n06-none/20261024-160524-replay-c7d4121c8afa/` | 15:59:39-16:05:24 | N-06 load options, replay 5,000 TPS for 5 min, `--target=none`, `--tick-ms=100`: rows per simulated second per table (`simulation.rowsPerSimulatedSecond`), profile step control, D-12 totals, host CPU |
| `rates/default-{500,2000,5000}/` | 16:05:40-16:06:15 | 13 section 15 distributions, bulk, 10 simulated minutes at 500 / 2,000 / 5,000 TPS, `--target=none`: load composition of the replay steps |
| `rates/n06-5000/` | 16:06:15-16:06:45 | N-06 load options, bulk, 10 simulated minutes at 5,000 TPS, `--target=none` (cross-check of the replay) |
| `replay-tick100/` | 16:07:33-16:12:59 (range 16:08:04-16:12:04) | replay 2,000 -> 5,000 TPS, 2 min each, `--target=docker` (Testcontainers ClickHouse 3 GiB, empty tables), `--tick-ms=100`: ingest lag per step |
| `replay-tick1000/` | 16:13:19-16:18:28 (range 16:13:30-16:17:30) | the same with `--tick-ms=1000` (baseline in the same session) |

`machine-cpu.tsv` (replay runs): machine CPU load (`Win32_Processor.LoadPercentage`) and free space on C:
every 5 to 8 s, with the id prefix of the run's own ClickHouse container (a watchdog would have stopped it
below 1.0 GB free; it did not trigger). WO-02 and another WO-03 fixer ran builds and a ClickHouse on the
same machine at the time.

Commands (generator options as `-Pstorage.<key>=<value>` of `:test:storage:generator:storageGenerate`):

```
# n06-none
-Pstorage.mode=replay -Pstorage.target=none -Pstorage.tps-steps=5000 -Pstorage.step-duration=PT5M \
  -Pstorage.instances-per-app=30 -Pstorage.active-sqls=200 -Pstorage.profile-step-rate=50000 \
  -Pstorage.tick-ms=100 -Pstorage.monitor-interval-s=5 -PstorageXmx=3g
# rates/*
-Pstorage.mode=bulk -Pstorage.target=none -Pstorage.days=1 -Pstorage.profile-days=1 -Pstorage.raw-days=1 \
  -Pstorage.scale=0.00694444444 -Pstorage.start=2026-10-20T12:00:00Z -Pstorage.tps=<500|2000|5000> \
  [-Pstorage.instances-per-app=30 -Pstorage.active-sqls=200 -Pstorage.profile-step-rate=50000]
# replay-tick*
-Pstorage.mode=replay -Pstorage.target=docker -Pstorage.docker-memory=3g -Pstorage.tps-steps=2000,5000 \
  -Pstorage.step-duration=PT2M -Pstorage.tick-ms=<100|1000> -Pstorage.monitor-interval-s=5
```
