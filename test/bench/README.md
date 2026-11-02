# test/bench

The agent overhead bench harness of docs/18 §7 (DSN-18-07): the measurement protocol of
docs/01 §7.3 (items 1-11) for the requirements N-01..N-04 and N-26..N-30, with the report template of
18 §7.6 and the regression rule of 18 §7.7. WO-05 formalised it from the WO-02 P-2 harness (formerly
`test/agent/overhead`; its results are in [docs/poc/agent-overhead.md](../../docs/poc/agent-overhead.md)
and [docs/bench/wo02-overhead-2026-10-24](../../docs/bench/wo02-overhead-2026-10-24/README.md)).

Development-only (`ArgosProject.isDevelopmentOnly`): nothing here is shipped. `build` and `check`
compile the harness and run its unit tests; no measurement runs in a build. `benchTest` runs the
full protocol and is meant for a dedicated bench machine only.

## Commands

| Command | What it does |
|---|---|
| `./gradlew :test:bench:check` | Unit tests (statistics, HDR re-aggregation, bootstrap, parsers of `/proc`, cgroup v2, `jcmd` and k6 output, verdicts, report template, regression rule). Fast, no network, no containers |
| `./gradlew :test:bench:benchTest -Pbench.machine-profile=<id>` | The full protocol on a dedicated machine (`bench-protocol.sh --no-build`). Optional `-Pbench.sets=main,startup,container,vt,worst,otel` and `-Pbench.args="--loader user@loadhost --app-address 10.0.0.5"`. Never on a shared or CI machine |
| `test/bench/fetch-k6.sh` | Pinned k6 v2.3.0 binary (SHA-256 checked) to `test/bench/build/k6/` (`--k6 auto` finds it) |
| `test/bench/bench-env.sh --out FILE --machine-profile <id>` | The environment record of 18 §7.1 (JSON). Stops (exit 4) unless the CPU governor is `performance` on every CPU and a machine profile is given; `--allow-invalid-env` records the problems for a smoke run and makes every report made with it INVALID |
| `test/bench/bench-run.sh --app shop-boot3 --jdk 21 --rounds 3 --exporter noop` | One A-B-A-B-A-B load set (18 §7.3) with the options of 18 §7.5, optionally the startup set (`--startup`), and the report |
| `test/bench/bench-protocol.sh --machine-profile <id> --loader user@loadhost --app-address <ip>` | What `benchTest` runs: one environment record, the sets main, startup, container and vt (optional worst, otel), one report over all of them |
| `test/bench/bench.sh [--no-build] <load\|startup\|report\|publish> ...` | The harness itself (`bench-harness.jar`; the WO-02 `run-overhead.sh`). `report --out SET` recomputes a set's `result.json`/`report.md`; `publish --sets A,B --out docs/bench` writes the 18 §7.6 report |

Every command prints its options with `--help` (`bench.sh --no-build load --help` for the harness).
Exit codes: 0 PASS, 3 FAIL, 4 INVALID, 2 usage error, 1 other error. Requirements: Java 21
(`JAVA_HOME`), bash, k6 (native, or the pinned image with `--k6 docker`), Docker for `--container`,
ssh with key authentication for `--loader`.

## The protocol (01 §7.3) and where the harness implements it

| Item | Implementation |
|---|---|
| 1 environment | `bench-env.sh`: CPU model, logical CPUs and physical cores, memory, governor (stop unless `performance`), THP, kernel, OS and an OS image hash (`/etc/os-release` + `uname` + `--os-image-id`), virtualization, `java -version`, agent jar SHA-256 and version, `argos-agent.yml` hash (`--agent-yml`; none: the `-Dargos.*` settings and their SHA-256 are in set.json), repository commit and sample app jar hashes, k6 version, the load machine over ssh (`--loader`) and the ping RTT to it. Load generator on another machine: `--loader user@host --app-address <ip>` (k6 over ssh; its raw output and summary are copied back byte for byte) |
| 2 JVM | `-Xms2g -Xmx2g -XX:+UseG1GC -XX:+AlwaysPreTouch` plus `-Xlog:gc:file=gc.log -XX:NativeMemoryTracking=summary`, identical for off and on |
| 3 workload | `sample-apps/load/bench.js`, constant arrival rate 1,000/s, the mix of 18 §7.2. `--profile default` keeps the agent's profile policy, `--profile worst` sets `sampling.profile.normal-rate=1.0`. Note: the WO-02 agent has no profile policy yet (WO-10, WO-16) and records every transaction, so both measure the worst case until then |
| 4 procedure | A-B-A-B-A-B (`--rounds 3`), the app restarted for every run, a warm-up k6 run of 3 minutes (excluded: it is its own k6 invocation, so "the first 3 minutes discarded" of 18 §7.2 holds and the C-46 drop count is per window) and a measured k6 run of 10 minutes. Off-run CPU coefficient of variation above 2% (sample standard deviation, n - 1): INVALID |
| 5 CPU | Process CPU of the measured window: `/proc/<pid>/stat` utime + stime (clock ticks, `getconf CLK_TCK`) on Linux, the OS process times elsewhere (`ProcessHandle`), cgroup v2 `cpu.stat usage_usec` in containers. Relative = (on - off) / off; peak = largest 1-minute average among windows started every 10 s. Agent thread CPU: `cpu=` of the `argos-*` threads of `jcmd Thread.print` before and after the window (the self-diagnostic `agent_threads_cpu_seconds_total` arrives with WO-10) |
| 6 latency | k6 writes every sample (`--out json=k6-raw.json.gz`); the harness re-aggregates the `http_req_duration` points of the measured phase into HDR-style histograms (`latency-hdr.json`, 3 significant digits, per route too) and reports p50/p95/p99/p99.9. The on - off difference has a 95% bootstrap interval over the requests (1,000 replicates, seed 42, `--seed`) and a Student t interval over the paired rounds; "no difference" when the bootstrap interval contains 0. Sets without raw output (WO-02) use k6's summary |
| 7 memory | After the window `jcmd GC.run` x2, `GC.class_histogram` (sum of `io.argos.agent.*`, N-02), `VM.metaspace` (used, on - off, N-26), `VM.native_memory summary` (Internal+Other, Other as direct memory), threads from `/proc/<pid>/status` (Linux; else NMT). The 72-hour slope is `soakTest` (WO-52) |
| 8 startup | `startup` set (C-49): process creation to the open port, median of 10 starts per app, CDS mode (default, `-Xshare:off`) and variant, shop-boot3 and shop-heavy; premain from the agent log (`premain_ms`, else the start banner) |
| 9 container | `--container` (`--cpus 2 --memory 4g`, heap 2g): the pinned JDK image, ext-stub and k6 in containers on a private network, a side container samples the app's cgroup every second |
| 10 virtual threads | `--vt` (`SAMPLE_VT=true`, shop-boot3 on Boot 3.5, JDK 21+) |
| 11 report | `bench.sh publish`: `docs/bench/<YYYY-MM-DD>-<commit7>.md` (below). OpenTelemetry reference: `--reference otel --otel-agent <jar>` adds a variant with the OpenTelemetry Java agent and every exporter set to `none`; the OTLP black-hole receiver of 18 §7.5 is not implemented (reference point only, no verdict) |

`--exporter` (18 §7.3, §5.4): `noop` is the no-op exporter of 18 §5.4 (`-Dargos.test.exporter=noop`:
every transaction is encoded and dropped; a test-only key, so a release build of the agent is
refused); `drop` is the WO-02 P-2 setting (`-Dargos.exporter=noop`: recorded and dropped without
encoding; use it to reproduce the WO-02 numbers); `grpc` measures the transport and is refused until
the agent jar contains the gRPC exporter (WO-11), then it needs `--collector host:port`. The harness
checks the agent's start banner of every run: an agent that started with another exporter makes
the set INVALID.

## Report (18 §7.6, §7.7)

`publish` writes `<out>/<YYYY-MM-DD>-<commit7>.md` (UTC date of the first set, commit of the
measured tree) and `<out>/<YYYY-MM-DD>-<commit7>/` (graphs as SVG with their data as TSV, and
`summary.json`). Parts: 1 environment, 2 run parameters (with the protocol deviations of each set),
3 raw results per run (CPU s, requests, errors, 5xx, dropped, p50/p95/p99/p99.9), 4 statistics with
confidence intervals, 5 memory, threads and startup, 6 verdicts of N-01..N-04 and N-26..N-30, 7 graphs
(CPU time series, latency CDF), 8 reproduction commands and input hashes. The Markdown ends with a
machine-readable summary comment.

Verdict rules:

- A requirement is **PASS** only when all its checks have numbers within their limits and its sets
  are valid: harness verdict not INVALID (off CV, k6 gate C-46, exporter, missing numbers), no
  deviation from the 01 §7.3 parameters (rounds, windows, rate, JVM options, load machine, container
  limits, 10 starts with shop-heavy and both CDS modes), and a valid environment record.
- **FAIL**: a valid requirement over its limit. **INVALID**: anything else, including "not measured"
  (its set was not run). The report is PASS only when all nine requirements are PASS; FAIL when one
  valid requirement fails; else INVALID. No PASS without numbers.
- **WARN** (18 §7.7): `--baseline <last approved report>` compares the relative CPU (WARN at +1.5
  percentage points or more) and the p99 difference (WARN at +0.3 ms or more). A maintainer approves a
  report in review by setting `"approved": true` in its summary comment. The `docs/bench/latest.md`
  link and the trend graph of the weekly run are WO-51.

## Dedicated machine

The step-by-step procedure (hardware, governor, ssh to the load machine, build, order of the sets,
what to copy into `docs/bench/`) is section 9 of
[docs/poc/agent-overhead.md](../../docs/poc/agent-overhead.md#9-전용-장비-재실행-절차-사람용). In short:

```bash
sudo cpupower frequency-set -g performance
./gradlew -Pargos.ui.skip=true :test:bench:check
./gradlew -Pargos.ui.skip=true :test:bench:benchTest -Pbench.machine-profile=argos-bench-app \
  -Pbench.args="--loader bench@argos-bench-load --app-address 10.0.0.5"
# or, without a Gradle daemon during the measurement:
./gradlew -Pargos.ui.skip=true :test:bench:benchJar :agent:agent-dist:agentJar \
  :sample-apps:ext-stub:sampleAppJar :sample-apps:shop-boot3:sampleAppJar :sample-apps:shop-heavy:sampleAppJar
./gradlew --stop
test/bench/bench-protocol.sh --no-build --machine-profile argos-bench-app \
  --loader bench@argos-bench-load --app-address 10.0.0.5
```

## Smoke run (any machine)

```bash
test/bench/fetch-k6.sh
test/bench/bench-run.sh --smoke --startup --exporter drop --machine-profile <name> \
  --out test/bench/build/smoke/<name> -- --heap 1g
```

1 round, 30 s warm-up, 60 s window, one start of shop-boot3: about 6 minutes. It proves the chain
(environment record, A-B runs, HDR re-aggregation, startup, report) and its report is INVALID by
construction (protocol deviations, environment override). It stays under `test/bench/build`.

## Still needed from a person

- WO-05 manual acceptance: a person reproduces the WO-02 numbers once with this runner on the
  dedicated machine (same result within the confidence intervals). The WO-02 sets were INVALID on a
  shared laptop, so the first valid run on dedicated hardware is the reference; the reproduction
  criterion proposed in docs/poc/agent-overhead.md 9.4 (same Gate 0 verdict, relative CPU within the
  first set's 95% interval, startup increase within ±5%) still needs the PM's confirmation.
- Bench machines (19 U-10, U-11; 18 §18 #2) and the noise threshold (off CV 2%, 18 §18 #3) are open
  items of the design; the first dedicated run calibrates them.
- `--k6-ssh` (two machines) has never run: the 9.3 step 0 pilot comes first (WO-02 H-4).

## Tests

`./gradlew :test:bench:test`: the WO-02 tests (statistics, JSON, options, parsers of real `jcmd` and
cgroup output, the C-46 gate, load and startup verdicts) and the WO-05 tests: off CV at exactly 2%
(valid) and just above (INVALID), no PASS without numbers (load set, startup set, report), the
exporter banner check, HDR histogram precision and merging, bootstrap determinism with a fixed seed
and its equivalence with plain resampling, the k6 raw parser, `/proc/<pid>/stat`, `/proc/<pid>/status`,
`/proc/stat` and cgroup v2 `cpu.stat`, `VM.metaspace`, the startup median of 10, the protocol
deviations, the environment record, the 8-part report and its summary, and the 18 §7.7 WARN
boundaries.
