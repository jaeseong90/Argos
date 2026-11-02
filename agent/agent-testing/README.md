# agent-testing

Agent test harness shared by every agent work order (18 §5, WO-05): `AppLauncher` (sample app x
JDK x agent options, local process or container), `FakeCollector` (gRPC `AgentService` in the test
JVM), the assertion DSL, transform baselines, fail-safe injection, re-entrance and leak probes, log
and Unsafe checks, and the ArchUnit rules over all agent modules.

- Gradle project: `:agent:agent-testing`
- Java package: `io.argos.agent.testing`
- Design: [18 test design §5-§6](../../docs/18-test-design.md), agent hooks in
  [10 §19](../../docs/10-agent-design.md)

Never shipped. Compiled and tested on JDK 21 (DSN-19-02); sample apps run on other JDKs as separate
processes. Test suites: `test` (unit, no network), `archTest` (part of `check`), `integrationTest`,
`securityTest`, `matrixTest` (skipped with a warning unless `ARGOS_RUNNER_LABELS` contains
`argos-matrix` or `-Pmatrix.combo=<id>` / `-Pmatrix.group=<group>` is given), `benchTest` (requires
`-Pbench.machine-profile=<id>`; the overhead bench itself is [test/bench](../../test/bench/README.md)).
Test layers, test IDs and requirement tags: [test/README.md](../../test/README.md).

## Layout (18 §5.1)

| Package | Classes | 18 |
|---|---|---|
| `launcher` | `AppLauncher`, `RunningApp` (`ProcessApp`, `ContainerApp`), `ExitedApp`, `Jdk`, `JvmOptions`, `SampleApp`, `TomcatStaging`, `AgentSettings`, `LoadResult`, `SelfDiagnostics`, `TempWatch`, `HttpResult` | §5.2, §5.10 |
| `collector` | `FakeCollector`, `FaultInjector`, `Collected` (`JsonlCollected`, collector view), `ReceivedBatch`, `SessionInfo`, `ConfigApplyOutcome`, `CommandOutcome`, `CollectorProtocol` (wire seam), `poc.PocProtocol` | §5.3 |
| `export` | `FileExporterReader` (JSONL), `TransactionRecord`, `RecordSource`, `ExporterSettings` | §5.4 |
| `assertion` | `ArgosAssertions`, `CollectedAssert`, `TransactionsAssert`, `TransactionAssert`, `StepsAssert`, `StepAssert`, `MetricAssert`, `ValueAssert` (missing vs zero), `Unsupported` | §5.5 |
| `baseline` | `TransformLog`, `TransformBaseline` (record and diff), `TransformList` | §5.6 |
| `leak` | `LeakProbe`, `LeakReport`, `TomcatRedeployDriver` | §5.9 |
| `log` | `AgentLogAssert` (allow-list fixture), `AppOutput` (console) | §5.10 |
| `jvm` | `JcmdClient`, `ClassHistogram`, `NmtSummary`, `ThreadCounter`, `UnsafeScanner`, `AgentJars` | §5.9, §5.10 |

Other directories: `baselines/transformed-classes/<app>-<jdk>[-vt].txt` (T-AG-005),
`fixtures/log-allowlist/agent-info.txt` (T-AG-060), `src/integrationTest`, `src/matrixTest`,
`src/probeModule` (test-only agent modules), `src/trivialApp`, `src/preloadAgent`,
`src/archTest` and `src/archFixtures` (ArchUnit).

## Starting an application (`launcher`, 18 §5.2, DSN-18-04)

```java
try (RunningApp app =
    AppLauncher.of(SampleApp.named("shop-boot3"), Jdk.of(21))   // -Pagent.e2e=shop-boot3@21
        .withAgent(agentJar, Map.of("async.step", "true"))
        .withEnv("SAMPLE_EXT_URL", stub)
        .virtualThreads(false)                                   // SAMPLE_VT=true (18 §4.1)
        .workDir(dir)
        .start(Duration.ofMinutes(3))) {                         // port bound + readiness 200
  app.get("/api/orders/1");
  assertThat(app.collected()).transactions().hasSize(1);
  LoadResult load = app.load(200, Duration.ofSeconds(5), "/api/orders/1");
  ClassHistogram histogram = app.jcmd().classHistogram();
}
```

- `SampleApp.named(app)` reads the artifact path from `argos.test.sampleApp.<app>`, which the
  `integrationTest` task sets for every app of `-Pagent.e2e`. `Jdk.of(n)` reads
  `argos.test.java<n>` (Gradle toolchains of `-Pagent.attach.jdks` and `-Pagent.e2e`), then
  `ARGOS_TEST_JDK_<n>`, then `~/.argos-test/jdks/<n>`.
- Work directory: `tmp/` (`java.io.tmpdir` and `TMP`/`TEMP`/`TMPDIR`, watched for T-BLD-006),
  `agent/argos-agent.jar` (a copy), `logs/` (`argos.log.dir`), `out/` (`argos-transactions.jsonl`,
  `transformed.txt`, `transform.log`), `stdout.log`, `stderr.log`, `argos-agent.yml` (on request).
- JVM presets (`JvmOptions`, 18 §5.10): `-XX:-UsePerfData`; C-27 `-XX:+BytecodeVerificationLocal`
  (plain on JDK 8 and 11, after `-XX:+UnlockDiagnosticVMOptions` on 17+, which reject it
  otherwise); `--sun-misc-unsafe-memory-access=deny` on JDK 25. `bytecodeVerification(false)` turns
  C-27 off (CDS notice checks).
- Agent settings (`AgentSettings`): harness defaults `log.dir`, `debug.transform-list`,
  `debug.transform-log`, `test.exporter=file:<workDir>/out` (or the collector's `server.*`), then the
  test's settings, all as `-Dargos.<key>`. `agentConfigFile(true)` also writes `argos-agent.yml`
  and passes `-Dargos.config`; the agent reads YAML from WO-10 on (until then it logs that the file
  is not supported yet). `harnessDefaults(false)` passes only the test's own settings.
- `RunningApp`: `port()`, `pid()`, `workDir()`, `agentLogDir()`, `call()`/`get()`, `load()` (small
  open-model load, not the benchmark), `collected()`, `transactions()`, `jcmd()`,
  `selfDiagnostics()`, `startupTime()` (spawn to port binding, C-49), `readyTime()`, `kill9()`,
  `gracefulStop()`, `transformList()`/`transformLog()` (and `await...`), `tempCreated()`,
  `stdoutLines()`, `stderrLines()`, `agentLog()`, `describe()`.
- Stop: `close()`/`gracefulStop()` send SIGTERM on Linux and macOS (shutdown hooks and the agent's
  shutdown flush run). On Windows both terminate without shutdown hooks, so tests wait for what the
  agent writes periodically (JSONL about every 100 ms, transform list and log about every 2 s).
- Main-class apps (`SampleApp.mainClass`, the trivial app) run with `runToExit(timeout)` and give an
  `ExitedApp` (exit code, outputs, every temp directory event and leftover, agent-like entries in
  the harness's own temp directory with `watchSystemTemp(true)`). `ExitedApp.agentLog()` fails
  with the log path when a run wrote no agent log (a `doesNotContain` check must never pass on a
  missing log); `agentLogIfPresent()` is for runs where no log is the expected outcome (kill
  switch).
- WAR apps (`shop-war-javax`, `shop-war-jakarta`) run in a standalone Tomcat staged per run
  (`TomcatStaging`, after the smoke runner's `TomcatBase`): CATALINA_HOME is the distribution the
  sample app's build unpacked (`sample-apps/<app>/build/tomcat/<key>`, passed as
  `argos.test.tomcatHome.<app>.<key>`), CATALINA_BASE is `<workDir>/catalina-base` (configuration
  copied, `server.xml` generated with one connector on `127.0.0.1:<port>` and the shutdown port
  off, the WAR copied once per context, Tomcat's JULI log in `catalina-base/logs` only, not on the
  console), started as `java ... org.apache.catalina.startup.Bootstrap start` with the `--add-opens`
  of catalina.sh on JDK 9+. Presets, agent, `tmp/`, environment rules and output files are those of
  jar apps. Readiness: the port and `/<context>/actuator/health` = 200 for every context. Request
  paths carry the context (`app.get("/shop-a/api/orders/1")`). Tomcat: `tomcat(key)`, else
  `SampleApp.defaultTomcat(jdk)`: the descriptor's first Tomcat up to the app's build JDK, the
  newest above it (shop-war-jakarta: Tomcat 10.1 on 17, Tomcat 11 on 21 and 25; shop-war-javax:
  Tomcat 9). Container mode runs jar apps only.
- Container mode: `container(true, cpus, memory)` runs the app in `eclipse-temurin:<jdk>-jdk`
  pinned by its multi-arch index digest (`ContainerApp.IMAGES`, JDK 8, 11, 17, 21, 25, looked up
  with `docker buildx imagetools inspect`; another JDK fails unless
  `-Pagent.container.image.<jdk>=<image@sha256:...>` gives one), Testcontainers `GenericContainer`
  with the Ryuk image pinned in `src/main/resources/testcontainers.properties`, the work directory
  bind-mounted at `/argos/run`, port 8080 published, memory 4g unless given. `pid()` is -1,
  `jcmd()` runs inside the container, the temp directory is the tree at the time of the call.
  `ContainerModeTest` runs with `-Pagent.container=true`.
- `selfDiagnostics()` returns `SelfDiagnostics.notAvailable()` until WO-10/WO-11 add the fields of
  10 §19 and the command; every value query throws instead of returning 0.
- Not yet: `collector(...)` with a real agent exporter (WO-11), and the 50 WAR redeploys of
  `TomcatRedeployDriver` against a real Tomcat (manager app, matrix, WO-50).

## FakeCollector (`collector`, 18 §5.3)

```java
try (FakeCollector collector = FakeCollector.start()) {           // or startTls()
  collector.faults().latency(Duration.ofMillis(200)).failNext(2, Status.UNAVAILABLE);
  ... AppLauncher...collector(collector).start() ...               // server.* settings (WO-11)
  collector.awaitBatches(BatchKind.TRANSACTIONS, 10, Duration.ofSeconds(10));
  ConfigApplyOutcome applied = collector.pushConfig(Map.of("enabled", "false")).get(10, SECONDS);
  CommandOutcome dump = collector.command("THREAD_DUMP", Map.of()).get(10, SECONDS);
  assertThat(collector.collected()).stats().sqlCount("select ...").isEqualTo(1000);
}
```

- A grpc-netty server on 127.0.0.1 in the test JVM (an agent in another JVM connects over
  loopback). Every unary batch is recorded as `ReceivedBatch` (kind, instance, epoch, seq, receive
  time, body, size, outcome) before a fault decides the answer; no limits or quotas (the inbound
  message and metadata limits are lifted from gRPC's 4 MiB default, 18 §5.3): it is the "sent
  count" reference (DSN-18-04). `address()` and `server.address` are `127.0.0.1:<port>` with and
  without TLS.
- `FaultInjector`: `latency(Duration)`, `failNext(n, Status)`, `hang()`, `rejectAuth()` (unary
  `UNAUTHENTICATED`, hello `TOKEN_INVALID`), `closeStream()`, `serverRestart(newInstanceId)` (same
  port), `plaintextOnly()`, `wrongHostCertificate()` (TLS for `wrong.example`), `clear()`.
- TLS: a throw-away self-signed certificate for `localhost`/127.0.0.1 made with the JDK's `keytool`
  at start and deleted at `close()` (`trustPem()` is the client's trust anchor,
  `server.tls.ca-file`). No key material is stored in the repository.
- Session stream: hello (instance ids from 1, `firstInstanceId`), heartbeat answers, dictionary
  entries, `pushConfig(...)` and `command(...)` complete with the agent's `ConfigApplyResult` and
  joined `CommandResult` chunks.
- Wire seam: tests use only `FakeCollector`, `FaultInjector`, `Collected` and the records of the
  package. Everything that touches generated protobuf types is one `CollectorProtocol`
  implementation, today `poc.PocProtocol` over the WO-02 transport PoC protocol
  (`test/agent/transport-poc/src/proto/argos/v1`, a snapshot of 11 §4, generated here in the
  `collectorProto` source set). WO-11 points `collectorProto` at `proto/argos/v1` (or depends on
  the `argos-proto` artifact, 18 §19) and replaces `PocProtocol` with an implementation over those
  stubs; the tests do not change. `FakeCollectorTest` (integrationTest) proves every fault with a
  real grpc-netty client.

## Exporters and the test namespace (18 §5.4, §15, T-BLD-010)

| Setting | Effect |
|---|---|
| `-Dargos.test.exporter=file:<dir>` | JSONL in `<dir>/argos-transactions.jsonl` (`FileExporterReader`) |
| `-Dargos.test.exporter=noop` | encode every transaction (the JSONL encoder until WO-11), then drop |
| `-Dargos.test.exporter=grpc` | the default exporter; no transport before WO-11: one WARN, then noop |
| `-Dargos.test.faults=<spec>,...` | `advice-throw:<module>`, `transform-error:<module>`, `helper-nocdef:<module>` (module unit, see below) |
| `-Dargos.debug.transform-log=<file>` | transform log (below); a diagnostic key, not test-only |
| `-Dargos.exporter=noop\|file` | WO-02 PoC keys (D-13 open): `noop` records and drops without encoding (the P-2 baseline) |

`argos.test.*` wins over the PoC keys. Release builds ignore `argos.test.*` and `argos.testing.*`
(system properties and `ARGOS_TEST_*`) and log one WARN naming them: the gate is in
`AgentConfig.load`, the single place where the agent reads settings, and the release marker is the
jar's `Implementation-Version` without `-SNAPSHOT` (`AgentInfo.isReleaseBuild`; development builds
are `0.0.0-SNAPSHOT`, tag builds `-Pversion=X.Y.Z`). The check fails closed: a packaged jar without
that attribute, or whose manifest cannot be read, counts as a release build (`checkManifestVersion`
and `check-shading.sh` make sure the shipped jar has it). `AgentJars.withVersion` makes a
release-flavoured copy of a development jar, and `ReleaseGateAttachTest` T-BLD-010 shows both
directions on it.

## Assertion DSL (`assertion`, 18 §5.5)

```java
import static io.argos.agent.testing.assertion.ArgosAssertions.assertThat;
import static io.argos.agent.testing.assertion.ArgosAssertions.startsWith;

assertThat(app.collected())
    .transactions().hasSize(1)
    .first()
      .hasKind("HTTP").hasService("GET /api/orders/{id}").hasStatus(500).isError()
      .elapsedMs().isGreaterThan(0)
      .steps().ofType("SQL").single().hasParent(0).rows().isZero()
      .transaction().hasStepTree("METHOD handle", "  SQL select ?")
      .isNotFromThread(startsWith("argos-"));
assertThat(collector.collected()).metrics().gauge("jdbc_pool_active", "pool", "HikariPool-1").exists();
assertThat(collector.collected()).dictionary().serviceCount().isLessThanOrEqualTo(10_000);
assertThat(collector.collected()).counters().counter("sql_outside_tx_total").isZero();
```

- Missing vs zero: `isMissing()` passes only when the field is absent, `isZero()` only when it is
  present and 0 (D-23).
- Unsupported: each record knows its `RecordSource`. A field or part the source cannot carry fails
  with `unsupported: ...` and the work order that adds it: on the JSONL exporter `env`, `version`,
  `cpu_time_ms`, `alloc_bytes`, step threads (`noStepFromThread`), statistics, metrics, dictionary
  and counters. Nothing passes on data that cannot exist.

## Transform baselines (`baseline`, 18 §5.6, DSN-18-05, T-AG-005)

The agent writes `-Dargos.debug.transform-log=<file>` (every ~2 s and at shutdown):

```
# Argos transform log: T <class> <modules> <loader> [retransformed] | E <class> <modules> <loader> <error>
T java.util.concurrent.ThreadPoolExecutor executor bootstrap
E com.example.Broken jdbc app java.lang.IllegalStateException
# summary transformed=18 onError=1 classCircularityError=0
```

`TransformBaseline.verify(directory, app, jdk, vt, log)` requires a complete log with `onError` 0
and `ClassCircularityError` 0 (also those met by type matchers), then compares `<class> <modules>
<loader>` lines with `baselines/transformed-classes/<app>-<jdk>[-vt].txt`: an added class fails, a
missing class prints `[baseline] WARN`. Record with
`./gradlew :agent:agent-testing:integrationTest -Pagent.baseline.record=true` (same `-Pagent.*`
combinations) and review the diff in the PR. Recorded: `trivial-release-<jdk>` and
`trivial-probe-<jdk>` for JDK 8, 11, 17, 21, 25, `shop-boot2-{8,11,17,21}`, `shop-boot3-{17,21,25}`.

## Fail-safe, re-entrance, leaks (18 §5.7-§5.9)

- `argos.test.faults` (agent-core `TestFaults`, development builds only): `advice-throw` (the
  module's `Argos.skip` throws: counted advice errors, T-AG-040), `transform-error` (every
  transformation of the module fails: counted, the module is disabled once at
  `instrumentation.transform-error-threshold`, T-AG-041), `helper-nocdef` (helpers are not
  injected: the inlined advice meets `NoClassDefFoundError`, suppressed and counted).
- The unit of a spec is the module id (`advice-throw:jdbc`): every advice of the module is
  affected. Each injected fault is announced with one WARN (`argos.test.faults: advice of module
  jdbc throws (test fault)`); assert it with `assertThatAgentLog(log).hasInjectedFault("advice-throw",
  "jdbc")`, so that a fault test cannot pass without its fault. A spec that injects nothing gets
  one WARN instead: a point-level spec (`advice-throw:servlet.enter`, `advice-throw:jdbc.exit`, the
  18 §5.7 examples) is rejected (`point-level fault '...' is not supported; use
  advice-throw:<module>`: `Argos.skip` cannot tell an enter advice from an exit advice), a module
  that is not installed is named (`'...' names no installed module (installed: [...])`), and so is
  a module turned off by `instrumentation.disabled`. An unknown kind is reported as before
  (`unsupported fault`).
- Initialization failure (T-AG-044): the probe module throws while the agent builds its transformer
  with `-Dprobe.init-throw=true` (test-only, outside the `argos.` namespace).
- Re-entrance (T-AG-050): `noneFromThread(startsWith("argos-"))` on transactions, no `io.argos.*`
  class (own or shaded) in the transform log, at most 6 `argos-` threads (N-26). Step threads and
  the `sql_outside_tx_total`/`orphan_steps_total` counters need WO-10/WO-11 and are reported as not
  available (a skipped test with the reason).
- `LeakProbe.of(app).check()` (T-AG-053): `jcmd GC.run` twice, then ThreadLocal residue, active
  registry and weak map from the self-diagnostics (NOT_AVAILABLE until WO-10, never passed) and
  heap residency from `GC.class_histogram` (`io.argos.` bytes <= 64 MB; NOT_AVAILABLE, not PASS,
  when the histogram has no rows or, for a run with the agent, no `io.argos.agent.` instance).
  `assertNoFailure()` fails on FAIL; `assertAllPassed()` also fails on NOT_AVAILABLE.
  `isNotFromThread`/`noneFromThread` fail on a transaction without a thread name. `TomcatRedeployDriver` (manager text
  API, `ParallelWebappClassLoader` count, ThreadLocal leak warnings) and `NmtSummary` (`Internal +
  Other` <= 16 MB, threads <= 6) are the WAR and native parts, unit-tested; they run with the WAR
  matrix (WO-50).

## Logs and Unsafe (18 §5.10)

- `AgentLogAssert.assertThatAgentLog(log).hasNoErrors().hasOnlyAllowedWarnings()` (T-AG-060):
  every WARN must match a regular expression of `fixtures/log-allowlist/agent-info.txt` (one entry
  today: the C-5 missing gRPC transport, until WO-11); `hasNoWarnings()` is the stricter 03 §3.1
  rule the normal-scenario tests use. `assertQuietConsole(stdout, stderr)`: only the application's
  log lines on stdout, only the JVM's CDS notice on stderr. `doesNotContainSeedSentinel()`
  (T-SEC-26), `doesNotContainText(token)` (T-SEC-06).
- `UnsafeScanner` (T-BLD-004): constant-pool scan of every class and `inst/**.classdata` entry for
  `sun/misc/Unsafe` and `sun.misc.Unsafe`, the two patterns of `test/agent/check-shading.sh`. One
  allow-list for both: `test/agent/unsafe-allowlist.txt` (empty, PM approval per line). 18 §5.10
  calls it `fixtures/unsafe-allowlist.txt`; the repository file is the single source of truth.

## Agent rules (`archTest`, part of `check`)

`./gradlew :agent:agent-testing:archTest` checks the classes of every agent module
(`agent-bootstrap`, `agent-core`, every `agent-instrumentation` module):

| Rules | Source | Classes |
|---|---|---|
| T-AG-001a..d, T-AG-002a..d, T-AG-003a..e, ADV-04 | 19 §5.3 ADV-01..04, 10 §5.3 A-1..A-6, C-9 | `src/archTest/.../AdviceRules.java` |
| AGT-01..03, B-1..B-4, T-AG-004 | 19 §5.3, 10 §2 | `src/archTest/.../AgentBoundaryRules.java` |

- Advice classes are classes with `@Advice.OnMethodEnter/OnMethodExit` methods; helpers are
  instrumentation classes whose name contains `Helper` (10 §5.4).
- ArchUnit cannot see `invokedynamic`, `monitorenter` or instruction order (19 §13 #8, confirmed in
  `AdviceRulesTest`), so lambdas/method references, synchronization and the `Argos.skip` guard are
  checked on the bytecode with ASM (`AdviceBytecode.java`). Every enter advice method starts with
  `if (Argos.skip(MODULE_ID)) return;` (10 §5.3 A-2). An exit advice method starts with the same
  guard or, per ADR-0003 C-24, with a null check of its `@Advice.Enter` parameter. No other guard
  and no unguarded helper delegation is accepted.
- `readOnly = false`, `@Advice.FieldSetterHandle` and `@Advice.AssignReturned` need an entry in
  [`argos-advice-allowlist.txt`](../agent-instrumentation/src/main/resources/argos-advice-allowlist.txt)
  (10 §5.3 A-5); `@Advice.SelfCallHandle` is never allowed. Instrumentation code uses no
  `net.bytebuddy.implementation` type except `Assigner`/`Assigner.Typing` and never calls
  `DynamicType.Builder...intercept(..)` (A-6).
- `src/archFixtures` holds a compliant miniature agent and one violating class per rule, compiled
  with `--release 8` like agent code (test-only).

## Integration tests (`integrationTest`)

```
./gradlew :agent:agent-testing:integrationTest                               # attach JDK 8,21 + shop-boot3@21
./gradlew :agent:agent-testing:integrationTest -Pagent.attach.jdks=8,11,17,21,25 \
    -Pagent.e2e=shop-boot2@8,shop-boot2@11,shop-boot2@17,shop-boot2@21,shop-boot3@17,shop-boot3@21,shop-boot3@25
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=none              # no sample app
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-war-jakarta@21 --tests '*WarAppAgentTest'
                                                                             # WAR app in Tomcat 11 (about 50 s)
./gradlew :agent:agent-testing:integrationTest -Pagent.baseline.record=true  # re-record baselines
./gradlew :agent:agent-testing:integrationTest -Pagent.container=true        # + container mode (Docker)
```

The classes are split by requirement: a dynamic test carries only the tags of its factory and class
(test/README.md §5), so each class has one factory and class-level `req:` tags. Shared launch and
assertion code is in `attach.AttachRuns` and `e2e.E2eRuns`; each app start of the WO-02 classes
happens once, as before.

| Class | Tags | Tests (per JDK / combination) |
|---|---|---|
| `attach.AgentAttachTest` | F-A31, F-A35 | trivial app on `-Pagent.attach.jdks`: T-BLD-006/T-AG-060/T-AG-005 release jar, T-AG-060 `-Xshare:off`, T-AG-010 probe jar, T-AG-070 preloaded JDK classes, T-AG-043 kill switches (2), T-AG-040 advice fault, F-A35 Unsafe code paths |
| `attach.ReleaseGateAttachTest` | DSN-18-15 | T-BLD-010 release gate |
| `attach.FailSafeAttachTest` | F-A15 | T-AG-041 transform errors, T-AG-040 helper-nocdef (plus two specs that inject nothing and are reported), T-AG-044 initialization failure |
| `e2e.SampleAppAgentTest` | F-A01, F-A02, F-A03, F-A05, F-A07, F-A28, F-A31 | per jar combination of `-Pagent.e2e`: T-AG-010/T-AG-070/T-AG-005; first combination: T-BLD-006 baseline |
| `e2e.FailSafeE2eTest` | F-A15 | first jar combination: T-AG-040 servlet and data-module faults, T-AG-041 jdbc transform errors, T-AG-040 helper-nocdef jdbc |
| `e2e.LoadLeakE2eTest` | F-A15, N-02 | first jar combination: T-AG-050/T-AG-053 1,000 requests and leak probe, the not-available part (skipped with the reason) |
| `e2e.WarAppAgentTest` | F-A01, DSN-18-03 | per WAR combination of `-Pagent.e2e` (none by default: the class is then skipped): one request to the first context is one transaction (status 200), no agent ERROR, quiet console |
| `BytecodeVerificationFlagTest` | T-AG-006: the test JVM and the preset on every attach JDK |
| `collector.FakeCollectorTest` | recording, `latency`, `failNext`, `hang`, `rejectAuth`, session round trips, `closeStream`, `serverRestart`, TLS modes |
| `jvm.AgentJarScanTest` | T-BLD-004 on the release jar |
| `launcher.ContainerModeTest` | container mode (`-Pagent.container=true`, else skipped) |

The probe agent jar (`build/probe-agent/argos-agent-probe.jar`, `probeAgentJar`) is the release jar
plus the test-only modules of `src/probeModule` (`test-probe` on the trivial app with a helper,
`test-probe-jdk` on `java.util.concurrent.ThreadPoolExecutor`), compiled against agent-dist's stage
1 jar (relocated ByteBuddy names) and never shipped. `build/preload-agent/preload-agent.jar` loads
`ThreadPoolExecutor` and `HttpURLConnection` before the Argos premain (T-AG-070).

Sample apps: `shop-boot2`, `shop-boot3`, `shop-boot4`, `shop-heavy`, `shop-batch`,
`shop-war-javax`, `shop-war-jakarta` (their `sampleAppElements` variant; only the requested apps
are built, and for a WAR app its Tomcat distributions are staged). Each jar combination writes
`build/reports/agent-e2e/<app>-jdk<N>-{transformed.txt,transform.log,tx.jsonl,agent.log,
stdout.log,temp-events.txt}` and one `[e2e-run]` line per run. T-BLD-006 run directories of the
agent run and of the no-agent baseline have names of the same length: on Windows the JDK creates a
transient `socket_<n>` file in `%TEMP%` for the wakeup pipe of every NIO selector only when that
path fits the 108-character AF_UNIX limit.

Test names start with the 18 §3 test ID (`T-AG-041 ...`); requirement tags use
`@Tag("req:<id>")`.
