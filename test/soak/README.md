# test/soak

Soak test of [docs/18 §11.2](../../docs/18-test-design.md) (WO-52): shop-boot3 with the agent
under a constant load, shop-batch with the agent and a Spring Batch run every hour, ext-stub, the
real server, ClickHouse and PostgreSQL, with `-XX:NativeMemoryTracking=summary`. It samples the
agent's heap, native memory, threads and file descriptors, the server's memory and WAL,
ClickHouse's parts, merges and disk, runs the nightly consistency check, reads the alert events
and the app's p99, collects OOM evidence and writes a report. Development-only, never shipped.

| | |
|---|---|
| Gradle project | `:test:soak` |
| Task | `soakTest` (not part of `check` or `build`) |
| Work order | WO-52 (chaos and 72-hour soak), docs/04 |
| Tests | T-SOAK-001 (heap slope and the 18 §11.2 table), T-NFR-18 (OOM 0, ClickHouse settings); unit tests T-SOAK-001a and T-NFR-18b (proposed ids) |
| Runs on | self-hosted `argos-bench-app` (18 §2.3), `.github/workflows/soak.yml`, before Gate 1 and Gate 2 |

## Running

```
./gradlew soakTest -Pbench.machine-profile=argos-bench-app                  # 72 hours, 500 TPS
./gradlew soakTest -Pbench.machine-profile=dev-laptop -Psoak.duration=15m   # short run, INVALID
```

`-Pbench.machine-profile=<id>` is required (18 §2.2): without it the root guard fails before
anything is built, and the task checks it again. The task builds the server boot jar, the agent
jar and the sample app jars (shop-boot3, shop-batch, load, ext-stub) first. Wrap it in the
`server-it` lock on a shared working tree (`bash test/lock/argos-lock.sh run server-it --purpose
soak -- ./gradlew soakTest ...`, test/README.md) and never run it next to another heavy run.

| Property | Default | |
|---|---|---|
| `-Psoak.duration` | `72h` | measured time after the warm-up (`15m`, `12h`, `3d`) |
| `-Psoak.tps` | `500` | load generator rate on shop-boot3 |
| `-Psoak.vt` | `false` | `true`: shop-boot3 with `SAMPLE_VT=true` on JDK 21 (virtual threads, ADR-0010 C-232 (9)); the virtual thread overhead itself (T-NFR-28) is the bench's (WO-51) |
| `-Psoak.publish` | `false` | `true`: copies the report to `docs/bench/soak-<date>.md` |
| `-Psoak.args` | | more options: `--ch-memory=6g`, `--pg-memory=1g`, `--server-heap=2g`, `--app-heap=1g`, `--mix=bench`, `--dedicated-profiles=argos-bench-app*` |

Stack sizes: a run of 24 hours or more uses ClickHouse 6 GiB, PostgreSQL 1 GiB, server `-Xmx2g`
and apps `-Xmx1g` (the 16 GB soak machine); a shorter run uses ClickHouse 3 GiB, server `-Xmx1g`
and apps `-Xmx512m`. Every container carries the label `io.argos.test` and is removed at the end.

Exit codes: 0 no criterion failed (INVALID rows included), 1 a criterion failed or the run aborted,
2 bad options.

## What runs

- Testcontainers PostgreSQL and ClickHouse with the installation's configuration
  (`ServerTestEnvironment`), the server boot jar as a child JVM (`LoadServerJvm`: 17 §7.2 options,
  NMT, metrics token), the same as the server load run (test/load/fake-agents).
- shop-boot3 and shop-batch on JDK 21 with the agent (`AppLauncher`): gRPC over TLS to the
  collector with the test CA, a token per application issued like `argos-admin token create`
  (applications `soak-shop` and `soak-batch`, env `soak`; the token files under `work/secrets/`,
  owner-only, are deleted when the stack stops), `-XX:NativeMemoryTracking=summary`,
  the self-diagnostics file, `ErrorFile` and `HeapDumpPath` in the run directory.
- ext-stub for the external calls of the mix.
- The load: the sample-apps/load generator (`load.jar`, bench mix of 18 §7.2) run back to back,
  one run per hourly point, so each run's summary is that point's p99. The first run carries the
  warm-up.
- shop-batch: `POST /batch/run/settlementJob?detached=true` every batch interval (skipped while the
  previous run still runs); its `@Scheduled` and Quartz jobs run on their own schedules.
- The recommended alert templates (`POST /api/v1/alerts/templates/apply`) for `soak-shop` at the
  start and for `soak-batch` after its first BATCH transaction is stored. The server applies them
  on its own as well (recommended rules for an application created by SQL within a minute, the
  batch template at the first BATCH transaction), so the answer mostly lists them as `exists`; the
  report shows what was created and skipped.

## Measurements and verdicts (18 §11.2)

| Measurement | Interval (72 h) | Verdict |
|---|---|---|
| Agent heap (ADR-0010 C-259 (5)): `jcmd GC.run` twice, `GC.class_histogram`, bytes of `io.argos.*` classes, plus the agent's buffer estimate `argos_agent_heap_bytes_estimated` (queues, dictionary, service names; newest value in the `metric` table). The app's live heap is a reference | 1 h | least-squares slope <= +50 KB/h, maximum <= 64 MB |
| NMT committed Internal + Other + Thread | 1 h | growth (max - first) <= 16 MB |
| Agent threads (`argos-*` in `Thread.print`; all JVM threads from NMT reported) | 1 h | flat (±0) |
| App FDs: Linux `/proc/<pid>/fd`; Windows `HandleCount` as a reference | 10 min | flat (±5) |
| Server RSS, heap, non-heap, direct buffers; WAL (`argos_wal_bytes`, else the directory) and its unloaded backlog (ADR-0010 C-259 (4): a read-only scan of the segments, bytes from the oldest data record not yet in ClickHouse to the write position) | 10 min | RSS <= `-Xmx` + 1 GiB; WAL backlog < 100 MB, WAL size <= `argos.wal.segment-bytes` (256 MiB) + 100 MB |
| ClickHouse active parts per partition, running merges, bytes on disk per table | 10 min | parts < 300; disk growth = N-19 formula ±30% |
| `argos-admin consistency check --date <d> --json` | every UTC day (after 00:30 UTC), and at the end for the open days | rollup mismatch 0 (D-12 recorded only) |
| Alert events (FIRING) | whole run | 0 false positives |
| App p99 (one generator run per hourly point) | 1 h | mean of the later points minus the mean of the first 6 <= 2 sd of the first 6 (at least 5% of their mean) |
| OOM evidence: exits, `hs_err`/heap dumps, `OutOfMemoryError` in the server, app and agent logs, ClickHouse `MEMORY_LIMIT_EXCEEDED`, containers `OOMKilled`, new OOM-killer lines of `dmesg` (Linux) | end | 0 (T-NFR-18) |
| ClickHouse memory settings for the container limit (`ClickHouseSettingsRule`, N-18) | start | no violation (T-NFR-18) |

Disk growth: the formula's terms are evaluated with the run's own rows, `Σ rows(table) x
B(table)` with the bytes per row of 13 §14 (xlog 70 B, profile_step 25 B, metric 4 B, statistics
30 B, rollups 60 B, app and service rollups 300 B), against the bytes on disk of those tables
between the first and the last 10-minute sample.

Alert false positives: the bench mix has 5% intended `500` answers, which meets the `error_rate`
WARN threshold of 5% by design, and the collector certificate the harness generates
(`TestCertificates`) is valid for 2 days, which fires the server self rule on certificate expiry
(`argos_server_tls_cert_expiry_days`). Such firings are listed as expected and not counted; any
other FIRING event is a false positive (for example `argos_server_disk_usage_ratio` on a host
whose disk is more than 80% full, which the report shows as a finding).

### Verdict rules

- Invariants (OOM 0, the consistency check, the ClickHouse settings rule) are PASS or FAIL in
  every run; one that could not be checked is a FAIL.
- Time and resource criteria are PASS or FAIL only for a valid run: at least 72 hours, at least
  500 TPS and a dedicated machine profile (`argos-bench-app*` by default, `--dedicated-profiles`).
  Any other run reports them INVALID with the value and its side of the limit. A criterion without
  enough points is INVALID ("not measured"), never PASS.

### Shorter runs

A run shorter than 72 hours scales both intervals by `duration / 72 h` with lower bounds (hourly
60 s, 10-minute 15 s, batch 120 s), so a 15-minute run takes 15 hourly and 60 ten-minute points
and starts shop-batch's job 7 times. The warm-up is 10 minutes for a run of a day or more, else
5% of the run between 30 s and 2 min. The p99 baseline is the first 6 points, or the first half of
a run with fewer than 12. The nightly consistency check is not scaled. The report states the
scaling.

## Output

`test/soak/build/reports/soak/`:

- `report.md`: run facts, the verdict table, series summaries (first, last, min, max, slope per
  hour), the p99 points, the consistency checks, the FIRING events, end-of-run facts (instance
  states, agent log ERROR and retransformation lines (ADR-0010 C-257 (2)), agent drops, stored
  rows), method and deviations, notes, and what a person has to do.
- `results.json`: one row per criterion (`id`, `requirement`, `criterion`, `limit`, `measured`,
  `invariant`, `verdict`, `reason`) and the run's validity.
- `samples.tsv`: every sample (`ts`, `elapsed_s`, `series`, `name`, `value`), written as it comes.
- `work/`: the server's console and GC log, each app's run directory (agent log, stdout, stderr),
  the generator's summaries and log, the consistency check outputs.

`-Psoak.publish=true` copies `report.md` to `docs/bench/soak-<date>.md` (start date, UTC). A run
whose time and resource verdicts are INVALID carries a banner saying so; until a valid run exists
the release notes keep saying that the 72-hour soak has not run (ADR-0010 C-246 (2)).

## Deviations from 18 §11.2

- The load is the sample-apps/load generator (bench mix of 18 §7.2) instead of k6: it already
  reports p99, needs no k6 on the runner and is the demo's generator.
- Testcontainers and child JVMs (the server load run's layout) instead of Compose; a Compose
  `test` profile is a follow-up proposal.
- One host. The split load generator of ADR-0010 C-244 (6) is not built: the generator costs a few
  percent of one core at 500 requests/s and does not enter the agent's measurements.

## Tests

`./gradlew :test:soak:test` (part of `check`, no Docker): least-squares slope, growth and
flatness, noise band, interval scaling, options and the INVALID rule, the N-19 formula, every
verdict (valid pass and fail, INVALID, invariants, not measured), OOM evidence (logs, crash files,
`dmesg` difference, non-UTF-8 logs), the parsers (generator summary, consistency check output,
alert events, batch state, handle count, TSV) and the report and `results.json`.

See [test/README.md](../README.md) for every test layer and how to run it.
