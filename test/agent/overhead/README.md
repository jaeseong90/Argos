# test/agent/overhead

WO-02 P-2 agent overhead harness: the measurement protocol of docs/01 §7.3 (items 1-11) for the
agent with the no-op exporter, against the sample app `shop-boot3` (and the startup of
`shop-heavy`). Results and their interpretation: [docs/poc/agent-overhead.md](../../../docs/poc/agent-overhead.md);
the step-by-step procedure for the Gate 0 run on dedicated hardware is its section 9, and the raw
data of the 2026-10-24 shared-machine sets is in
[docs/bench/wo02-overhead-2026-10-24](../../../docs/bench/wo02-overhead-2026-10-24/README.md).

Development-only (`ArgosProject.isDevelopmentOnly`): nothing here is shipped. `build` and `check`
compile the harness and run its unit tests (statistics, parsers, verdicts); no measurement runs in a
build. WO-05 moves the runner to `test/bench` (`benchTest`), WO-51 makes it the weekly benchmark
(18 §7, DSN-18-07).

## Commands

```bash
test/agent/overhead/fetch-k6.sh          # pinned k6 v2.3.0 binary -> test/agent/overhead/build/k6/ (SHA-256 checked)
test/agent/overhead/run-overhead.sh load --out build/overhead/main --java "$JAVA_HOME/bin/java"
test/agent/overhead/run-overhead.sh load --out build/overhead/container --mode container
test/agent/overhead/run-overhead.sh load --out build/overhead/vt --vt
test/agent/overhead/run-overhead.sh startup --out build/overhead/startup
test/agent/overhead/run-overhead.sh --no-build report --out build/overhead/main
```

`run-overhead.sh` builds `:test:agent:overhead:overheadJar`, `:agent:agent-dist:agentJar` and the
sample app jars, then runs `java -jar test/agent/overhead/build/overhead/overhead-bench.jar`. On a
dedicated machine build first, stop the Gradle daemon (`./gradlew --stop`) and run with
`--no-build`. Requirements: Java 21 (`JAVA_HOME`), bash, Docker for `--mode container` or
`--k6 docker`.

## `load`: A-B-A-B-A-B set (01 §7.3 items 2-7, 9, 10)

For each round (default 3) and variant (`off`, `on`; `otel` optional) the harness starts the app
fresh, runs k6 for the warm-up (3 min, excluded) and then the measured window (10 min) as two
separate k6 invocations of `sample-apps/load/bench.js` (constant arrival rate 1,000/s, the mix of
18 §7.2), and records:

| item | how |
|---|---|
| CPU (item 5) | process CPU time of the app over the measured k6 run: `ProcessHandle` (process mode; Windows `GetProcessTimes`, Linux `/proc`), cgroup v2 `cpu.stat usage_usec` (container mode). Timeline every second for the 1-minute peak (18 §7.4) |
| agent thread CPU (item 5) | `cpu=` of the `argos-*` threads in `jcmd Thread.print` before and after the window (the agent has no self-diagnostic metric yet) |
| latency (item 6) | k6 percentiles of the window (p50, p95, p99, p99.9, per route); differences between on and off paired by round, mean and 95% Student-t interval |
| k6 gate (C-46) | per measured window: dropped iterations <= 1% of the planned ones, unexpected outcomes 0%, mean CPU of the load generator's machine < 80% (else INVALID) |
| memory (item 7) | after the window `jcmd GC.run` x2, `GC.class_histogram` (instances of `io.argos.agent.*`), `VM.native_memory summary` (`-XX:NativeMemoryTracking=summary` in both variants: metaspace, Internal, Other = direct memory, threads), `VM.metaspace` |
| machine (item 1) | system CPU and available memory every second, the 5-second load before each run (the run waits up to `--max-wait` for `--max-load`), OS, CPU, power plan or governor, THP, JDK, k6, jar hashes, git commit |

JVM options (item 2): `-Xms<heap> -Xmx<heap> -XX:+UseG1GC -XX:+AlwaysPreTouch` (`--heap`, default
2g; 1g in containers) plus `-Xlog:gc:file=gc.log -XX:NativeMemoryTracking=summary`, identical for
off and on. The `on` variant adds `-javaagent:<agent jar> -Dargos.log.dir=<run>/agent-logs
-Dargos.exporter=noop` (`--agent-config`). The C-27 verification flags of the integration tests are
not used (they disable CDS and verify JDK classes: not a production configuration).

`--mode process` runs the app and ext-stub as java processes on 127.0.0.1 and k6 natively
(`--k6 auto` picks `build/k6/k6[.exe]` from `fetch-k6.sh`, else the pinned image; on Windows use the
native binary, Docker Desktop's container-to-host proxy loses requests at 1,000/s).
`--k6-ssh user@loadhost --app-address <IP of this machine>` (item 1: load generator on another
machine) runs k6 on `loadhost` over ssh (`BatchMode`, key authentication; `--k6` names the binary
there, default `k6`), feeds `bench.js` on stdin, copies the summary back and judges the C-46
saturation with the load machine's `/proc/stat` over the window; the app then listens on that
address. `--mode container` (item 9) runs the app with `--cpus 2 --memory 2g` (swap off) in the pinned
`eclipse-temurin:21-jdk` image with the jars mounted read-only, ext-stub and k6 in containers on a
private network, and a side container that reads the host cgroup tree every second (no sampler
inside the app's cgroup). `--vt` (item 10) sets `SAMPLE_VT=true` (virtual threads, Boot 3.2+ on
JDK 21+). `--variants off,on,otel --otel-agent <jar>` adds the OpenTelemetry Java agent with every
exporter set to `none` as a reference point (item 11).

Shared machines: before each run the harness waits until the 5-second machine CPU load is at most
`--max-load` (default 50%, up to `--max-wait`); a load run needs such checks in a row for
`--quiet` (default 15s) without other work, a start of the startup set one check. A run whose "background" CPU (machine load minus
the app's and k6's own CPU, in % of all logical CPUs) exceeds `--max-background` (default 25%)
during the warm-up or the window, or whose window the C-46 gate marks INVALID, is moved to
`runs/<NN>-<variant>-discarded-<k>/` and repeated, up to `--retries` (default 3) times; the last
attempt is kept whatever it shows. The startup set applies the same rule to each start. Every
run also keeps `processes-before.txt` and `processes-after.txt` (the busiest processes; raw data
only). `--variant-defs "name:key=value;key=value|..."` adds diagnostic agent variants (the `on`
settings plus their own), for example
`nomod:instrumentation.disabled=servlet,spring-webmvc,jdbc,executor,httpclient-jdk|skip:enabled=false`
to separate the cost of loading the agent, of matching and transforming, and of recording.

Set directory: `set.json` (parameters, environment, input hashes), `bin/` (copies of the measured
jars), `runs/<NN>-<variant>/` (`run.json`, `k6.json`, `k6-warmup.json`, `k6*.log`, `machine.json`,
`threads-start.txt`, `threads-end.txt`, `class-histogram.txt`, `nmt-summary.txt`, `metaspace.txt`,
`gc.log`, `command.txt`, `agent-logs/`, `app-log-excerpt.txt`; the full app log, about 250 MB per
run because the 5% intended errors log stack traces, is deleted unless `--keep-app-log`),
`result.json` and `report.md`.

Verdicts (`result.json`): INVALID when the off-run CPU coefficient of variation exceeds 2% (item 4)
or a k6 gate of an off run fails or the loader is saturated; FAIL when an on run fails its k6 gate
or a limit is exceeded: relative CPU <= 5% (container 8%, N-29) and 1-minute peak <= 8% (N-01),
p99 increase <= max(1 ms, 5% of off p99) (N-03), agent thread CPU <= 1% of the host, agent heap
<= 64 MB (N-02), metaspace increase <= 32 MB, NMT Other increase <= 8 MB, threads <= 6 (N-26), NMT
Internal+Other increase <= 16 MB and thread increase <= 6 (item 7).

## `startup`: C-49 startup set (01 §7.3 item 8, N-04, N-27, N-30)

Per app (`--apps`, default `shop-boot3,shop-heavy`) and CDS mode (`--cds default,off`, `off` adds
`-Xshare:off`): `--runs` (default 10) starts of each variant, alternating off/on and on/off. Each
start is measured from process creation to the open port (C-49, jar apps; polled every 10 ms) and to
the first 200 of `/actuator/health`, with Spring Boot's `Started ... in` and the agent's premain time
(`started in N ms` of the banner). Default JVM options `-Xms2g -Xmx2g -XX:+UseG1GC`. Verdicts:
median port increase <= 2 s (N-04, Gate 0, also for the heavy app), heavy app <= +10% (N-04,
N-27), premain median <= 500 ms (N-30).

## Tests

`./gradlew :test:agent:overhead:test`: statistics (CV, t intervals, window rates), JSON, options,
parsers of real `jcmd` and cgroup output, the C-46 gate, and the load and startup verdicts on
synthetic runs.
