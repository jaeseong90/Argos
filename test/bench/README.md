# test/bench

The agent overhead bench harness of docs/18 §7 (DSN-18-07): the measurement protocol of
docs/01 §7.3 (items 1-11) for the requirements N-01..N-04 and N-26..N-30, with the report template of
18 §7.6 and the regression rule of 18 §7.7. WO-05 formalised it from the WO-02 P-2 harness (formerly
`test/agent/overhead`; its results are in [docs/poc/agent-overhead.md](../../docs/poc/agent-overhead.md)
and [docs/bench/wo02-overhead-2026-10-24](../../docs/bench/wo02-overhead-2026-10-24/README.md)).
WO-51 completed it: agent thread CPU, direct memory and premain from the agent's self-diagnostics,
the OpenTelemetry reference with an OTLP black-hole receiver, the `grpc` transport set, automatic
baseline selection, `latest.md` and the trend, the Gate 1 fragment, the noise calibration tool and
the weekly workflow `.github/workflows/bench.yml`. How reports are named, approved and compared is in
[docs/bench/README.md](../../docs/bench/README.md).

Development-only (`ArgosProject.isDevelopmentOnly`): nothing here is shipped. `build` and `check`
compile the harness and run its unit tests; no measurement runs in a build. `benchTest` runs the
full protocol and is meant for a dedicated bench machine only.

## Commands

| Command | What it does |
|---|---|
| `./gradlew :test:bench:check` | Unit tests (statistics, HDR re-aggregation, bootstrap, parsers of `/proc`, cgroup v2, `jcmd`, k6 and self-diagnostic output, verdicts, report template, regression rule, baseline selection, trend, Gate 1 fragment, noise calibration, OTLP receiver, weekly workflow). Fast, no network, no containers |
| `./gradlew :test:bench:benchTest -Pbench.machine-profile=<id>` | The full protocol on a dedicated machine (`bench-protocol.sh --no-build`). Optional `-Pbench.sets=main,startup,container,vt,worst,otel,grpc` and `-Pbench.args="--loader user@loadhost --app-address 10.0.0.5 --baseline auto --latest"`. Never on a shared or CI machine |
| `test/bench/fetch-k6.sh` | Pinned k6 v2.3.0 binary (SHA-256 checked) to `test/bench/build/k6/` (`--k6 auto` finds it) |
| `test/bench/fetch-otel-agent.sh` | Pinned OpenTelemetry Java agent 2.32.0 (Maven Central, SHA-256 checked, deleted on mismatch) to `test/bench/build/otel/` (`--otel-agent auto` finds it). Bench time only: nothing fetches it during a run, and Argos never fetches it at run time |
| `test/bench/bench-env.sh --out FILE --machine-profile <id>` | The environment record of 18 §7.1 (JSON). Stops (exit 4) unless the CPU governor is `performance` on every CPU and a machine profile is given; `--allow-invalid-env` records the problems for a smoke run and makes every report made with it INVALID |
| `test/bench/bench-run.sh --app shop-boot3 --jdk 21 --rounds 3 --exporter noop` | One A-B-A-B-A-B load set (18 §7.3) with the options of 18 §7.5, optionally the startup set (`--startup`), the OpenTelemetry variant (`--reference otel --otel-agent auto`), and the report |
| `test/bench/bench-protocol.sh --machine-profile <id> --loader user@loadhost --app-address <ip>` | What `benchTest` and the weekly workflow run: one environment record, the sets main, startup, container and vt (optional worst, otel, grpc), one report over all of them. `--baseline auto` picks the last approved comparable report, `--latest` also refreshes `docs/bench/latest.md` and the trend |
| `test/bench/bench.sh [--no-build] <load\|startup\|report\|publish\|trend\|noise\|gate1> ...` | The harness itself (`bench-harness.jar`; the WO-02 `run-overhead.sh`). See the table below |

The harness commands of `bench.sh`:

| Command | What it does |
|---|---|
| `load`, `startup` | One load set or startup set (what `bench-run.sh` and `bench-protocol.sh` call) |
| `report --out SET` | Recomputes a set's `result.json`/`report.md` |
| `publish --sets A,B --out docs/bench [--baseline auto\|none\|FILE] [--latest] [--trend]` | The 18 §7.6 report `<out>/<YYYY-MM-DD>-<commit7>.md`. `--baseline auto` picks the most recent report in `<out>` whose summary says `"approved": true` and whose machine profile, app, JDK, exporter and encoding match (below); `--latest` rewrites `<out>/latest.md`; `--trend` regenerates `<out>/trend.md`, `<out>/trend.svg` and its TSV |
| `trend --dir docs/bench [--out DIR]` | The trend over the reports' summaries: relative CPU (mean, peak), p99 difference, heap, startup increase. WO-02 directories and JSONL-era values are listed as "not compared" (ADR-0010 C-145 (4)) |
| `noise --sets DIR[,DIR...] [--out FILE]` | Noise calibration (18 §18 #3): off-run CPU coefficient of variation, bootstrap intervals of the off-to-off (A-A) relative CPU and p99 differences, the minimum detectable effect, and a recommended threshold. An INVALID input marks the result as a reference value |
| `gate1 --reports FILE[,FILE...] --out FILE` | The Gate 1 fragment for T-NFR-01..04 and T-NFR-26..30 (below) |

Every command prints its options with `--help` (`bench.sh --no-build load --help` for the harness).
Exit codes: 0 PASS, 3 FAIL, 4 INVALID, 2 usage error, 1 other error. Requirements: Java 21
(`JAVA_HOME`), bash, k6 (native, or the pinned image with `--k6 docker`), Docker for `--container`,
ssh with key authentication for `--loader`.

## The protocol (01 §7.3) and where the harness implements it

| Item | Implementation |
|---|---|
| 1 environment | `bench-env.sh`: CPU model, logical CPUs and physical cores, memory, governor (stop unless `performance`), THP, kernel, OS and an OS image hash (`/etc/os-release` + `uname` + `--os-image-id`), virtualization, `java -version`, agent jar SHA-256 and version, `argos-agent.yml` hash (`--agent-yml`; none: the `-Dargos.*` settings and their SHA-256 are in set.json), repository commit and sample app jar hashes, k6 version, the load machine over ssh (`--loader`) and the ping RTT to it. Load generator on another machine: `--loader user@host --app-address <ip>` (k6 over ssh; its raw output and summary are copied back byte for byte) |
| 2 JVM | `-Xms2g -Xmx2g -XX:+UseG1GC -XX:+AlwaysPreTouch` plus `-Xlog:gc:file=gc.log -XX:NativeMemoryTracking=summary`, identical for off and on |
| 3 workload | `sample-apps/load/bench.js`, constant arrival rate 1,000/s, the mix of 18 §7.2. `--profile default` keeps the agent's profile policy (10 §7, WO-10 and WO-16), `--profile worst` sets `sampling.profile.normal-rate=1.0`. The WO-02 PoC agent had no profile policy and recorded every transaction, so the WO-02 sets measured the worst case whatever the profile; they are reference material, not a baseline |
| 4 procedure | A-B-A-B-A-B (`--rounds 3`), the app restarted for every run, a warm-up k6 run of 3 minutes (excluded: it is its own k6 invocation, so "the first 3 minutes discarded" of 18 §7.2 holds and the C-46 drop count is per window) and a measured k6 run of 10 minutes. Off-run CPU coefficient of variation above 2% (sample standard deviation, n - 1): INVALID. `bench.sh noise` computes the evidence for this threshold (below) |
| 5 CPU | Process CPU of the measured window: `/proc/<pid>/stat` utime + stime (clock ticks, `getconf CLK_TCK`) on Linux, the OS process times elsewhere (`ProcessHandle`), cgroup v2 `cpu.stat usage_usec` in containers. Relative = (on - off) / off; peak = largest 1-minute average among windows started every 10 s. Agent thread CPU (N-01 auxiliary, at most 1% of the host): the self-diagnostic `agent_threads_cpu_seconds_total`, difference of the snapshots at the start and the end of the window (below); `cpu=` of the `argos-*` threads of `jcmd Thread.print` stays as a cross-check and as the fallback when the self-diagnostics are missing (the report names the source) |
| 6 latency | k6 writes every sample (`--out json=k6-raw.json.gz`); the harness re-aggregates the `http_req_duration` points of the measured phase into HDR-style histograms (`latency-hdr.json`, 3 significant digits, per route too) and reports p50/p95/p99/p99.9. The on - off difference has a 95% bootstrap interval over the requests (1,000 replicates, seed 42, `--seed`) and a Student t interval over the paired rounds; "no difference" when the bootstrap interval contains 0. Per-route p99 is informational (H-5, provisional rule of ADR-0009 C-80). Sets without raw output (WO-02) use k6's summary |
| 7 memory | After the window `jcmd GC.run` x2, `GC.class_histogram` (sum of `io.argos.agent.*`, N-02), `VM.metaspace` (used, on - off, N-26), `VM.native_memory summary` (Internal+Other) and the self-diagnostic `direct_memory_bytes` side by side for direct memory (N-26, at most 8 MB; the self-diagnostic is the JVM-wide direct buffer pool, ADR-0010 C-145 (1)), threads from `/proc/<pid>/status` (Linux; else NMT). The 72-hour slope is `soakTest` (WO-52) |
| 8 startup | `startup` set (C-49): process creation to the open port, median of 10 starts per app, CDS mode (default, `-Xshare:off`) and variant, shop-boot3 and shop-heavy; premain from the self-diagnostic `premain_ms` (`premainSource: selfdiag-file`), else from the start banner of the agent log (`agent-log-banner`). N-30 is judged on the dedicated machine's single run; `PremainTimeAttachTest` (WO-19) is a reference (ADR-0010 C-240 (7)) |
| 9 container | `--container` (`--cpus 2 --memory 4g`, heap 2g): the pinned JDK image, ext-stub and k6 in containers on a private network, a side container samples the app's cgroup every second. The self-diagnostic file lives in the mounted run directory. H-6 (provisional): the container set keeps 1,000/s; an off run that fails the C-46 gate makes the set INVALID with that reason |
| 10 virtual threads | `--vt` (`SAMPLE_VT=true`, shop-boot3 on Boot 3.5, JDK 21+) |
| 11 report | `bench.sh publish`: `docs/bench/<YYYY-MM-DD>-<commit7>.md` (below). OpenTelemetry reference: `--reference otel --otel-agent auto` (or a jar path) adds a variant with the OpenTelemetry Java agent 2.32.0 exporting traces and metrics over OTLP/HTTP protobuf to the harness's black-hole receiver (logs `none`); a reference point, no verdict |

### Self-diagnostic sources

The on variants and the startup set start the agent with `-Dargos.test.selfdiag-file=<run>/selfdiag.json`
(ADR-0010 C-95). The agent rewrites that file every 2 seconds and once at shutdown; `SelfDiagFile`
reads `agent_threads_cpu_seconds_total`, `direct_memory_bytes` and `premain_ms` from it and writes
`measure.selfDiag = {agentThreadsCpuSeconds, directMemoryBytes, premainMs, source: "selfdiag-file"}` into
`run.json`. Like every `argos.test.*` key it works only with a development (`-SNAPSHOT`) build of
the agent (ADR-0009 C-88, T-BLD-010): with a release jar the key is not set and the report falls
back to `jcmd`, NMT and the start banner, naming the source. A gauge the agent leaves out or reports
as `NOT_AVAILABLE` is absent, never 0.

### OpenTelemetry reference and the OTLP black-hole receiver

`OtlpBlackHole` listens on 127.0.0.1 (a random port) and answers `POST /v1/traces`, `/v1/metrics`
and `/v1/logs` with 200 and an empty protobuf message (`application/x-protobuf`). It counts requests
and body bytes per path and stores nothing. The otel variant runs with
`-Dotel.exporter.otlp.protocol=http/protobuf -Dotel.exporter.otlp.endpoint=http://127.0.0.1:<port>`, so
the OpenTelemetry agent pays its whole export path as a real installation would, without a collector
on the machine; `measure.otlp = {requests, bytes}` in `run.json` shows that it exported. The agent
jar (Apache-2.0, 2.32.0, SHA-256 `f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82`) is
fetched only by `fetch-otel-agent.sh`; `--otel-agent auto` resolves
`test/bench/build/otel/opentelemetry-javaagent-2.32.0.jar` and is a usage error when it is missing.

### Exporters and the transport set

`--exporter` (18 §7.3, §5.4): `noop` is the no-op exporter of 18 §5.4 (`-Dargos.test.exporter=noop`:
every transaction is encoded with the sender's protobuf encoder and dropped, ADR-0010 C-145 (4)); `drop` is the WO-02 P-2 setting
(`-Dargos.test.exporter=drop`: recorded and dropped without encoding; use it to reproduce the WO-02
numbers). Both are values of the test-only key `argos.test.exporter` (the WO-02 key
`argos.exporter` is gone, ADR-0009 C-88), so a release build of the agent is refused: measure the
development (`-SNAPSHOT`) build of the same commit. `noop` encodes on `argos-sender` (WO-11) and
`drop` skips that step, so their numbers differ by the encoding cost. The WO-02 sets were encoded as
JSONL, so `noop` numbers are never compared with them (C-145 (4); the report says so in part 2 and in the trend).

Verdicts use `noop` (ADR-0009 C-80). `grpc` measures the transport: the agent's default exporter
sends to `--collector host:port` (required; a jar without the gRPC exporter is refused). The
collector's TLS and token settings come from `--agent-config`. In the protocol the transport is a
set of its own, `bench-protocol.sh --sets main,...,grpc --collector HOST:PORT` (directory
`<out>/grpc/grpc`, `set.json` exporter `grpc`), and the report adds a "transport cost" section:
on(grpc) - on(noop) relative CPU and p99, without a verdict. The standalone FakeCollector
(`agent/agent-testing/README.md`, "Standalone collector") is enough for the agent's cost; run it on
the load machine:

```
./gradlew :agent:agent-testing:fakeCollectorJar
java -jar agent/agent-testing/build/fake-collector/fake-collector.jar --port 0 \
    --ready-file build/fc.ready --token-file build/fc.token --report-every 30 &
test/bench/bench-run.sh --app shop-boot3 --jdk 21 --rounds 3 --exporter grpc \
    --collector "$(cat build/fc.ready)" \
    --agent-config "server.tls=false,server.token-file=$PWD/build/fc.token"
```

Keep the collector on another machine or core set for a verdict run (18 §7.3): on the same CPUs it
is part of the measured load. The harness checks the agent's start banner of every run: an agent
that started with another exporter makes the set INVALID.

### Load generator errors (H-2, provisional)

Socket errors on the k6 side while dialling (`EADDRNOTAVAIL`, `EADDRINUSE`, Windows `WSAEADDRINUSE`
and other local-address errors of the dial phase) are loader problems, not the agent's: they are
counted as "loader error", not as unexpected responses, and make the run INVALID instead of FAIL
(the intent of ADR-0004 C-46; provisional rule of ADR-0009 C-80, re-decided after the first valid
dedicated run).

## Report (18 §7.6, §7.7)

`publish` writes `<out>/<YYYY-MM-DD>-<commit7>.md` (UTC date of the first set, commit of the
measured tree) and `<out>/<YYYY-MM-DD>-<commit7>/` (graphs as SVG with their data as TSV, and
`summary.json`). Parts: 1 environment, 2 run parameters (with the protocol deviations of each set
and the exporter and encoding), 3 raw results per run (CPU s, requests, errors, 5xx, dropped,
p50/p95/p99/p99.9), 4 statistics with confidence intervals, 5 memory, threads and startup, 6 verdicts
of N-01..N-04 and N-26..N-30, 7 graphs (CPU time series, latency CDF), 8 reproduction commands and
input hashes. The Markdown ends with a machine-readable summary comment (with `encoding` and
`sampleAppDrivers`, and `transportCost` when a grpc set ran).

Verdict rules:

- A requirement is **PASS** only when all its checks have numbers within their limits and its sets
  are valid: harness verdict not INVALID (off CV, k6 gate C-46, loader errors, exporter, missing
  numbers), no deviation from the 01 §7.3 parameters (rounds, windows, rate, JVM options, load
  machine, container limits, 10 starts with shop-heavy and both CDS modes), and a valid environment
  record.
- **FAIL**: a valid requirement over its limit. **INVALID**: anything else, including "not measured"
  (its set was not run). The report is PASS only when all nine requirements are PASS; FAIL when one
  valid requirement fails; else INVALID. No PASS without numbers.
- **WARN** (18 §7.7): against the baseline, the relative CPU is worse by +1.5 percentage points or more
  or the p99 difference by +0.3 ms or more (exactly on the boundary is WARN). A FAIL takes precedence
  over a WARN.
- **Baseline**: `--baseline auto` takes the most recent report in the output directory whose summary
  has `"approved": true` and the same machine profile, app, JDK, exporter and encoding. When there is
  none, or a given `--baseline FILE` differs in one of these, the regression verdict is
  NOT COMPARED with the reason. A different JDBC driver list of shop-boot3 (`sampleAppDrivers`) adds
  the footnote "WO-13 driver addition" to startup and transformation comparisons (ADR-0010 C-186 (2)).
- `--latest` rewrites `docs/bench/latest.md`, a short pointer page (headline numbers and a link to
  the newest report; a plain file rather than the symbolic link of 18 §7.7, which Windows checkouts
  do not keep). `--trend` regenerates `docs/bench/trend.md` and `trend.svg`.

A maintainer approves a report in review by setting `"approved": true` in its summary comment
([docs/bench/README.md](../../docs/bench/README.md)).

## Gate 1 fragment

`bench.sh gate1 --reports <report.md>[,...] --out FILE` writes the agent part of the Gate 1 report:
one row per T-NFR-01..04 and T-NFR-26..30 (requirement, limit, numbers, verdict PASS/FAIL/INVALID,
evidence report and set). A row without numbers is never PASS, and an INVALID set makes its rows
INVALID. The fragment ends with the items only a person on the target hardware can close: the
dedicated N-30 single run (C-240 (7)), the 17 open item 9 and §7.3 ratios after the full
`serverLoadTest` run (C-244 (7)), and the alert-window memory recheck from that run's class histogram
(C-245 (1)). By default it goes under `build/`; a dedicated run writes
`docs/bench/gate1-agent-<YYYY-MM-DD>-<commit7>.md`, which WO-53 aggregates into `docs/bench/gate1.md`.

## Noise calibration (18 §18 #3)

`bench.sh noise --sets <set dir>[,...]` computes, from the off runs of load sets: the off-run CPU
coefficient of variation, the bootstrap intervals of the off-to-off (A-A) relative CPU and p99
differences, the minimum detectable effect and a recommended threshold. The open item stays with the
PM: the recommendation is to keep the off CV limit of 2% on the dedicated machine and to report the
95% interval half-width of the relative CPU (a regression comparison can detect the +1.5 point WARN
only when that half-width is at most 1.5 points). The WO-02 sets were measured on a shared laptop
(off CV 26.17% and 4.39%) and are no basis; the threshold is calibrated after the first valid dedicated run.

## Weekly workflow

`.github/workflows/bench.yml` runs every Saturday at 18:00 UTC and on `workflow_dispatch`, never on
pull requests (O-S05). The job `bench` runs on the self-hosted runner with the labels
`[self-hosted, linux, argos-bench-app]` and a read-only token: it builds the harness, the agent and
the sample apps, stops the Gradle daemon, and runs
`bench-protocol.sh --no-build --machine-profile argos-bench-app --sets main,startup --loader <ARGOS_BENCH_LOADER> --app-address <ARGOS_BENCH_APP_ADDRESS> --baseline auto --latest`.
The weekly sets are main and startup (ADR-0009 C-80); container, vt, worst, otel and grpc run per
release candidate through the `sets` input (`collector` for grpc). PASS, FAIL and INVALID are results:
the report, its directory, `latest.md` and the trend are uploaded as the artifact `bench-report`
(the raw sets, without the k6 raw files, as `bench-raw`), and the GitHub-hosted job `publish`, the
only job with write scopes, commits those docs/bench files to the branch
`bench/weekly-<YYYY-MM-DD>-<commit7>` and opens a pull request with the verdicts and the regression
result in its body. It runs no repository code. A person reviews, approves (above) and merges.

Runner preparation: the CPU governor `performance`, Docker Engine for the container set, the runner
user's ssh key authorised on `argos-bench-load` (no GitHub secret), k6 v2.3.0 on the load machine,
and the repository variables `ARGOS_BENCH_LOADER` (`user@host`) and `ARGOS_BENCH_APP_ADDRESS`. Pull
requests opened with the workflow token do not trigger `pr.yml`; they change only docs/bench.

## Dedicated machine

The step-by-step procedure (hardware, governor, ssh to the load machine, build, order of the sets,
what to copy into `docs/bench/`) is section 9 of
[docs/poc/agent-overhead.md](../../docs/poc/agent-overhead.md#9-전용-장비-재실행-절차-사람용). In short:

```bash
sudo cpupower frequency-set -g performance
./gradlew -Pargos.ui.skip=true :test:bench:check
./gradlew -Pargos.ui.skip=true :test:bench:benchTest -Pbench.machine-profile=argos-bench-app \
  -Pbench.args="--loader bench@argos-bench-load --app-address 10.0.0.5 --baseline auto --latest"
# or, without a Gradle daemon during the measurement:
./gradlew -Pargos.ui.skip=true :test:bench:benchJar :agent:agent-dist:agentJar \
  :sample-apps:ext-stub:sampleAppJar :sample-apps:shop-boot3:sampleAppJar :sample-apps:shop-heavy:sampleAppJar
./gradlew --stop
test/bench/bench-protocol.sh --no-build --machine-profile argos-bench-app \
  --loader bench@argos-bench-load --app-address 10.0.0.5 --baseline auto --latest
```

## Smoke run (any machine)

```bash
test/bench/fetch-k6.sh && test/bench/fetch-otel-agent.sh
test/bench/bench-run.sh --smoke --startup --exporter noop --reference otel --otel-agent auto \
  --machine-profile <name> --out test/bench/build/smoke/<name> -- --heap 1g
test/bench/bench.sh --no-build gate1 --reports <the smoke report .md> --out test/bench/build/smoke/<name>/gate1.md
test/bench/bench.sh --no-build noise --sets test/bench/build/smoke/<name>/main
```

1 round, 30 s warm-up, 60 s window, one start of shop-boot3: about 8 to 10 minutes with the otel
variant. It proves the chain (environment record, A-B runs, self-diagnostics, OTLP receiver, HDR
re-aggregation, startup, report, Gate 1 fragment) and its report is INVALID by construction
(protocol deviations, environment override). It stays under `test/bench/build`; never commit a
smoke report to `docs/bench`.

## Still needed from a person

- The bench machines (19 U-10, U-11; 18 §18 #2): the self-hosted runner `argos-bench-app`, the load
  machine `argos-bench-load`, the repository variables, and the first run of the full protocol
  (`benchTest`, about 5 hours) and of the weekly workflow including its pull request.
- Valid numbers and the Gate 1 verdicts of T-NFR-01..04 and T-NFR-26..30 from that run; N-30 from
  its single run (C-240 (7)). On the same target hardware: the full `serverLoadTest` run for
  C-244 (7) and C-245 (1).
- WO-05 manual acceptance: a person reproduces the WO-02 numbers once with this runner on the
  dedicated machine (`--exporter drop`, same result within the confidence intervals); the criterion
  of docs/poc/agent-overhead.md 9.4 is adopted (ADR-0009 C-80).
- The noise threshold (18 §18 #3) and the provisional rules H-2, H-5 and H-6 (ADR-0009 C-80) are
  re-decided from the first valid dedicated run (`bench.sh noise` gives the evidence).
- `--k6-ssh`/`--loader` (two machines) has never run: the 9.3 step 0 pilot comes first (WO-02 H-4).
  The OpenTelemetry reference numbers also need the dedicated machine.

## Tests

`./gradlew :test:bench:test`: the WO-02 tests (statistics, JSON, options, parsers of real `jcmd` and
cgroup output, the C-46 gate, load and startup verdicts), the WO-05 tests (off CV at exactly 2%
(valid) and just above (INVALID), no PASS without numbers (load set, startup set, report), the
exporter banner check, HDR histogram precision and merging, bootstrap determinism with a fixed seed
and its equivalence with plain resampling, the k6 raw parser, `/proc/<pid>/stat`, `/proc/<pid>/status`,
`/proc/stat` and cgroup v2 `cpu.stat`, `VM.metaspace`, the startup median of 10, the protocol
deviations, the environment record, the 8-part report and its summary) and the WO-51 tests
(T-BLD-015a..i, proposed IDs): regression WARN boundaries, FAIL precedence and non-comparable
baselines (a), automatic baseline selection (b), `latest.md` and the trend with the C-145 (4) and
C-186 (2) notes (c), the noise calibration (d), the Gate 1 fragment (e), the self-diagnostic parser
(f), the OTLP black-hole receiver over real HTTP (g), the H-2 loader error rule (h) and the weekly
workflow `BenchWorkflowTest` (i: triggers, runner label, default sets, `--stop` before the
`--no-build` protocol, SHA-pinned actions, write scopes only in the GitHub-hosted publish job).
