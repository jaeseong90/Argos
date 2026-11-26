# test/load/fake-agents

Server load generator and runner of [docs/18 §8](../../../docs/18-test-design.md) (WO-29): fake
agents that speak the `argos.v1` protocol (the server's test-fixture `FakeAgent`, not the agent code)
and drive the whole server, the measurements of N-06 and N-18, the D-13 end check, and the report
`docs/bench/server-load.md`. Development-only, never shipped.

| | |
|---|---|
| Gradle project | `:test:load:fake-agents` |
| Task | `serverLoadTest` (not part of `check` or `build`; requires `-Pbench.machine-profile=<id>`) |
| Requirements | N-06 (5,000 TPS for 1 hour), N-18, D-13 (sent = stored), 12 §11 (memory budget) |
| Runs on | N-06 verdicts: a 13 §7.1 machine (8 cores, 16 GiB). Anywhere else: `smoke` or `mini`, every timing and resource verdict INVALID |

## Running

```
./gradlew serverLoadTest -Pbench.machine-profile=<id> [-Pserver.load.profile=full|smoke|mini] \
    [-Pserver.load.publish=true] [-Pserver.load.args="--tps=2000 --duration=10m"] [-Pargos.ui.skip=true]
```

Wrap every run in the shared-tree lock: `bash test/lock/argos-lock.sh run server-it --purpose
"serverLoadTest" -- ./gradlew serverLoadTest ...`. Run nothing else heavy at the same time.

| Profile | Load | Stack | Wall time |
|---|---|---|---|
| `full` | 50 instances over 10 apps, 5,000 TPS, 50,000 step rows/s, 10,000 statistics rows/s, 100 metric series per instance and 5 s, 5 min warm-up + 1 h | ClickHouse 7.5 GiB, PostgreSQL 1.5 GiB, server `-Xmx4g` (13 §7.1) | about 1.5 h |
| `smoke` (default) | 10 instances over 5 apps, 1,000 TPS with the same proportions, 1 min + 5 min | ClickHouse 4 GiB, PostgreSQL 1 GiB, server `-Xmx2g` | 12-15 min |
| `mini` | 4 instances over 2 apps, 200 TPS, 20 s + 90 s (the runner's self-check) | ClickHouse 2 GiB, PostgreSQL 512 MiB, server `-Xmx1g` | about 5 min |

The task builds `:server:bootJar` (add `-Pargos.ui.skip=true` to skip the UI bundle, the load run
does not need it) and writes `build/reports/server-load/`:

| File | Content |
|---|---|
| `server-load.md` | the report; `-Pserver.load.publish=true` copies it to `docs/bench/server-load.md` |
| `samples.tsv` | one line per 5 s sample: ingest lag p99 of the interval, WAL fsync max, parts, `DelayedInserts`, ClickHouse memory, container memory, server RSS, heap, non-heap, direct, threads, sessions, writer queue |
| `class-histogram.txt`, `native-memory.txt` | `jcmd GC.class_histogram` and `VM.native_memory summary` of the server at the end |
| `work/server/` | the server's console log, GC log, `hs_err`/heap dumps if any; `work/server-pinned.jfr` (`jdk.VirtualThreadPinned`) |

Exit: the task fails when an invariant fails (D-13 counts, OOM 0, ClickHouse settings) or the run
aborts; timing and resource verdicts never fail it, they are in the report (PASS/FAIL at full
scale, INVALID otherwise).

## Generator parameters (18 §8)

`-Pserver.load.args` overrides any default of the profile (`--key=value`, space separated):

| Parameter | Default (full) | Meaning |
|---|---|---|
| `--instances`, `--apps` | 50, 10 | fake agent instances, spread round robin over apps; env alternates prod/stg |
| `--tps` | 5,000 | transactions per second in total; Zipf services (`--services` 100, s = 1.1), log-normal elapsed (median 80 ms, sigma 1.0) with 1 % outliers of 2-30 s, `--error-rate` 0.01 |
| `--profile-rate`, `--steps-per-tx` | 0.12, derived | steps per captured profile; without `--steps-per-tx` derived from `--step-rows` (50,000/s): 83. 18 §8's 15 steps give 9,000 step rows/s and contradict N-06 (WO-29 PM question 1) |
| `--stats-rows` | 10,000/s | `SqlStat` + `ExtStat` rows per second (90 % / 10 %) in 10 s windows, within 6,000 rows per instance and window (C-157 (3)) |
| `--metrics` | 100 | metric series per instance and 5 s |
| `--warm-up`, `--duration`, `--drain-timeout` | 5m, 1h, 15m | |
| `--late-arrival`, `--late-min`, `--late-max` | 0.5 %, 1m, 30m | transactions held back and sent late (sent at the end at the latest) |
| `--duplicate` | 0.1 % | transaction batches sent twice with the same seq (expect `ACK_DUPLICATE`) |
| `--clock-skew`, `--skewed-instances`, `--skew-outlier` | 2s, 2, 60s | agent clocks within +-2 s; two instances 60 s ahead (D-15) |
| `--attack` | all | `gzip-bomb`, `batch-overflow`, `dict-flood`, `instance-flood`, `unauth-flood` (comma separated, `all` or `none`). Run after the measured window while the steady load continues. Sizes: full = 18 §8 (dictionary flood 100,000 services, instance flood 1,000 Hellos, 10 oversized batches), smoke 10,000 / 200 / 3, mini 2,000 / 150 / 3; 3 gzip bombs (1 MB to 1 GiB) in every profile. `unauth-flood` runs last: it blocks the loopback address of every fake agent for 10 minutes |
| `--users`, `--user-interval` | 10, 5s | VIEWER users signed in through the login API, each polling dashboard 1 h, XLog 5 min and SQL Top N 1 h |
| `--ch-memory`, `--pg-memory`, `--server-heap`, `--max-direct-memory` | 7.5g, 1.5g, 4g, 512m | stack limits |
| `--seed` | 42 | generator seed |

## How it works

- Stack: `ServerTestEnvironment` (PostgreSQL 16 and ClickHouse in Testcontainers, generated
  passwords, the profile's container limits) and the boot jar as a child JVM
  (`io.argos.server.testing.load.LoadServerJvm`: 17 §7.2 options, `-Xms` = `-Xmx`, G1,
  `MaxDirectMemorySize`, `ExitOnOutOfMemoryError`, NMT summary, a JFR recording of pinned virtual
  threads, a metrics token generated at run time, HTTP and management on loopback).
- Fake agents: one session per instance, TLS channels shared by ten sessions, per-instance epoch,
  `tx_id` node and agent clock; every batch is retried with the same seq; what the server
  acknowledged (items minus `dropped_items`) is the D-13 reference.
- Samplers every 5 s: `/actuator/prometheus` (ingest lag and WAL fsync histograms, rejected and
  dropped counters, sessions, writer queue, JVM memory and threads), ClickHouse `system.parts`,
  `system.events`, `system.metrics`, `system.asynchronous_metrics` (as `argos_reader`), `docker stats`
  of both containers, the server's RSS (Linux `/proc/<pid>/status`, Windows working set; the report
  names the source).
- End checks in every profile: D-13 (acknowledged = `count(xlog)` = `uniqExact(tx_id)` =
  `sum(service_stat_1m.count)` for the load apps, after the drain), OOM 0 (server exit, `hs_err`,
  `OutOfMemoryError`, ClickHouse `MEMORY_LIMIT_EXCEEDED` in the error log, docker `OOMKilled`), and
  the N-18 ClickHouse settings (`max_server_memory_usage` per `MemoryBudget`, `mark_cache_size`,
  `uncompressed_cache_size` 0, reader 3 GiB share / 10 s / 4 threads).
- Phases after the measured window (report sections, rows in the verdict table): the attacks
  under load (`AttackPhase`: codes and counters of T-SEC-11, T-SEC-13, T-SEC-14 as invariants;
  another app's receive-to-`argos_reader` latency, the steady load's ingest lag and the server's
  resident set growth as measurements; the dictionary flood's rows, relation size and Caffeine
  heap, C-238 (14)), the idle sessions after the load (`SessionPhase`, C-157 (1): smoke 1,000,
  full 5,000 minus the live sessions, one TLS connection each; heap after a full GC, NMT and
  resident set before and after, bytes per session and the extrapolation to 5,000), and the
  unauthenticated Hello flood last (`UnauthFloodPhase`, T-SEC-15). The run raises the per-token
  session limits (`new-per-minute`, `max-per-token`) so that the per-app hourly instance cap
  stops the instance flood; the server-wide cap of 5,000 sessions stays. The proposal sections
  (12 open items 3 and 4, 17 §7.3 and open item 9) are computed from the end-of-run facts.
- The generator, the server and the databases share one machine (managed mode). 18 §2.1 asks for a
  separate load generator; an external-target mode is a follow-up (WO-29 PM question 2).

Unit tests (`src/test`, part of `check`, no Docker): parsers, histogram quantiles, verdict rules and
report (T-NFR-06a), the ClickHouse settings rule (T-NFR-18a), options and profiles.

See [test/README.md](../../README.md) for every test layer and how to run it.
