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
| `launcher` | `AppLauncher`, `RunningApp` (`ProcessApp`, `ContainerApp`), `ExitedApp`, `Jdk`, `JvmOptions`, `SampleApp`, `TomcatStaging` (with `TomcatStaging.Shutdown`), `AgentSettings`, `LoadResult`, `SelfDiagnostics`, `TempWatch`, `HttpResult` | §5.2, §5.10 |
| `collector` | `FakeCollector`, `FaultInjector`, `Collected` (`JsonlCollected`, collector view), `ReceivedBatch`, `SessionInfo`, `HeartbeatRecord`, `ByeInfo`, `KindCounts`, `ReceivedToken`, `ConfigApplyOutcome`, `CommandOutcome`, `ClockEstimator` (11 §9), `Deduplicator` (11 §6), `ContractVectors`, `FakeCollectorMain` (standalone), `CollectorProtocol` (wire seam), `v1.V1Protocol` (argos.v1 of `:proto`) | §5.3 |
| `export` | `FileExporterReader` (JSONL), `TransactionRecord`, `RecordSource`, `ExporterSettings` | §5.4 |
| `assertion` | `ArgosAssertions`, `CollectedAssert`, `TransactionsAssert`, `TransactionAssert`, `StepsAssert`, `StepAssert`, `MetricAssert`, `ValueAssert` (missing vs zero), `Unsupported` | §5.5 |
| `baseline` | `TransformLog`, `TransformBaseline` (record and diff), `TransformList`, `PremainClassBudget` (C-79) | §5.6 |
| `leak` | `LeakProbe`, `LeakReport`, `TomcatRedeployDriver` | §5.9 |
| `log` | `AgentLogAssert` (allow-list fixture), `AppOutput` (console), `TomcatLogCheck` (standalone Tomcat log of WAR runs) | §5.9, §5.10 |
| `jvm` | `JcmdClient`, `ClassHistogram`, `NmtSummary`, `ThreadCounter`, `UnsafeScanner`, `AgentJars` | §5.9, §5.10 |

Other directories: `baselines/transformed-classes/<app>-<jdk>[-vt].txt` (T-AG-005),
`baselines/premain-classes/trivial-release-<jdk>.txt` (premain class budget, ADR-0009 C-79),
`fixtures/log-allowlist/agent-info.txt` (T-AG-060), `src/integrationTest`, `src/matrixTest`,
`src/probeModule` (test-only agent modules), `src/trivialApp`, `src/preloadAgent`,
`src/servletFixtureJakarta` and `src/servletFixtureJavax` (the Spring-free servlet WARs of WO-12,
below), `src/archTest` and `src/archFixtures` (ArchUnit).

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
- Agent settings (`AgentSettings`): harness defaults `app.name` (the sample app's name), `app.env=test`
  (the required keys of 10 §4.3 P4, so a normal run logs no configuration WARN), `log.dir`,
  `debug.transform-list`, `debug.transform-log`, `test.selfdiag-file=<workDir>/out/selfdiag.json`
  (ADR-0010 C-95), `test.exporter=file:<workDir>/out` (unless a collector is set), then the
  test's settings, all as `-Dargos.<key>`. `agentConfigFile(true)` also writes `argos-agent.yml`
  and passes `-Dargos.config` (the agent's own bounded YAML subset parser, WO-10).
  `harnessDefaults(false)` passes only the test's own settings (and the collector's, below).
- Collector (`collector(fakeCollector)`, 18 §5.2): the agent sends through its default `grpc`
  exporter (no `test.exporter`) to `server.address=127.0.0.1:<port>` (container mode:
  `host.docker.internal:<port>`), `server.tls` as the collector runs (TLS unless it was started
  plaintext), `server.tls.ca-file=<workDir>/collector/ca.pem` (a copy of `trustPem()`) and
  `server.token-file=<workDir>/collector/token` (the collector's per-collector test token,
  owner-only where the file system has POSIX permissions). The token never goes on the command
  line (SEC-06: `-Dargos.server.token` would also make the agent WARN). `RunningApp.collected()`
  is then the collector's view. These settings apply with or without the harness defaults.
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
  copied, `server.xml` generated with one connector on `127.0.0.1:<port>` and a shutdown port on
  `127.0.0.1` with a random 128-bit command per run (`TomcatStaging.Shutdown`, never printed), the
  WAR copied once per context plus the extra web applications of `webapp(context, war)`, Tomcat's
  JULI log in `catalina-base/logs` only, not on the console), started as `java ...
  org.apache.catalina.startup.Bootstrap start` with the `--add-opens` of catalina.sh on JDK 9+ and
  an English locale (`-Duser.language=en -Duser.country=US`: Tomcat ships translated messages and
  JULI translates level names, the log judgement below matches the English ones). Presets, agent,
  `tmp/`, environment rules and output files are those of jar apps. Readiness: the port and
  `/<context>/actuator/health` = 200 for every context of the app (Tomcat deploys every web
  application, extra ones included, before it opens its connector). Request paths carry the
  context (`app.get("/shop-a/api/orders/1")`). Tomcat: `tomcat(key)`, else
  `SampleApp.defaultTomcat(jdk)`: the descriptor's first Tomcat up to the app's build JDK, the
  newest above it (shop-war-jakarta: Tomcat 10.1 on 17, Tomcat 11 on 21 and 25; shop-war-javax:
  Tomcat 9). Container mode runs jar apps only.
- Stopping a WAR run: `gracefulStop()` and `close()` first send the shutdown command, so Tomcat
  stops its web applications (its leak detection runs and logs) and the JVM runs its shutdown hooks,
  on Windows too; only after 60 s without exit is the process terminated. `tomcatLogLines()` reads
  every line of `catalina-base/logs/*` (empty for jar apps); `log.TomcatLogCheck.judge(lines)` is
  the pure judgement of WO-05 r1-new-1 (unit-tested in `TomcatLogCheckTest`): SEVERE records,
  WARNING records that name the agent (`io.argos.agent`, `argos-agent`, `argos-<thread>`,
  `argos.<key>`, `argos:`; not the sample apps' `io.argos.sample` or paths that contain the
  repository name) and Tomcat's ThreadLocal leak warnings (`TomcatRedeployDriver.
  threadLocalLeakWarnings`). `Findings.beyond(baseline)` drops the SEVERE records and leak
  warnings that the same run without the agent logs too (timestamps and hash codes ignored), never
  an agent warning. `WarAppAgentTest` asserts zero agent warnings and zero SEVERE records and leak
  warnings beyond a no-agent run after a graceful stop.
- Container mode: `container(true, cpus, memory)` runs the app in `eclipse-temurin:<jdk>-jdk`
  pinned by its multi-arch index digest (`ContainerApp.IMAGES`, JDK 8, 11, 17, 21, 25, looked up
  with `docker buildx imagetools inspect`; another JDK fails unless
  `-Pagent.container.image.<jdk>=<image@sha256:...>` gives one), Testcontainers `GenericContainer`
  with the Ryuk image pinned in `src/main/resources/testcontainers.properties`, the work directory
  bind-mounted at `/argos/run`, port 8080 published, memory 4g unless given. `pid()` is -1,
  `jcmd()` runs inside the container, the temp directory is the tree at the time of the call.
  `ContainerModeTest` runs with `-Pagent.container=true`. With a collector the container gets
  `--add-host host.docker.internal:host-gateway` and the agent sends to
  `host.docker.internal:<port>` (the collector's TLS certificate names that host too). The
  collector then listens beyond the loopback: `FakeCollector.builder().bindAddress("0.0.0.0")`
  (tests only: the collector is reachable on every interface of the machine while the test runs;
  `host-gateway` is the Docker bridge address on a native Linux host). `CollectorWiringTest`
  checks this end to end (run on Docker Desktop for Windows; not yet run on a native Linux Docker
  host). `tokenFileMode("0644")` (container mode with a collector only) makes the container's
  entrypoint copy `collector/token` to `/argos/secret/token` (`ContainerApp.TOKEN_FILE`, outside
  the bind mount), `chmod` it to that mode and `exec` the unchanged command; `server.token-file`
  names the copy, so the permissions the agent checks (16 §2.6 WARN) do not depend on how the
  Docker host shows the bind mount (`TokenTransportTest`: 0644 WARN, 0600 none).
- `selfDiagnostics()` reads the agent's self-diagnostics snapshot (18 §19, ADR-0010 C-95): a
  development build with `argos.test.selfdiag-file=<file>` (a harness default) rewrites `<file>`
  atomically every 2 s on `argos-watch` and at shutdown with `threadlocal_contexts_live`,
  `active_registry_size`, `weakmap_size`, `agent_threads_cpu_seconds_total`, `direct_memory_bytes`,
  `premain_ms`, every `argos_agent_*` counter and the state and error count of every module.
  `awaitSelfDiagnostics(after, timeout)` waits for a snapshot written after an instant (after a GC,
  for example). A gauge the agent did not measure is absent from the file, and the harness throws
  `NotAvailableException` for it instead of returning 0 (C-4); a run without the file (release
  build, harness defaults off, no agent) is `notAvailable` with the reason. The flat fields
  `sql_outside_tx_total` and `orphan_steps_total` come from the counters
  `argos_agent_sql_outside_tx_total` and the sum of `argos_agent_orphan_steps_total{type=...}`.
  Two weak-map gauges (both after the agent expunged collected keys): `weakmap_size` is the 18 §5.9
  leak gauge and counts only the fallback context-store entries that hold transaction state (a
  `TraceContext` or `Scope` value, `ContextStore.contextEntries()`), so it must be 0 after GC;
  `weakmap_entries` (`SelfDiagnostics.WEAKMAP_ENTRIES`, not an 18 §19 field) counts every live
  entry of every store (`ContextStore.totalEntries()`), including caches keyed by long-lived
  objects (a connection's database id, per-class-loader state), so it is not 0 after GC and is
  checked for growth instead (`LoadLeakE2eTest`). Narrowing `weakmap_size` this way awaits a PM
  decision (WO-10 report).
  `ExitedApp.selfDiagnostics()` reads the snapshot the agent wrote at shutdown and
  `ExitedApp.premainClasses()` the sorted list `<file>.premain-classes` (the classes the agent class
  loader defined on the premain thread, C-79). WO-17 adds the `SELF_DIAGNOSTICS` command as a second
  source of the same snapshot.
- Not yet: the 50 WAR redeploys of `TomcatRedeployDriver` against a real Tomcat (manager app,
  matrix, WO-50); the staged Tomcat has no manager app.

### Spring-free servlet fixture (WO-12, T-AG-020)

`src/servletFixtureJakarta` (jakarta.servlet 5.0 API) and `src/servletFixtureJavax` (javax.servlet
3.1 API), both `compileOnly` with the agent modules' catalog entries and compiled to Java 8
bytecode, are two tiny web applications without Spring: one `EchoServlet` class under a prefix
(`/items/*`), an exact (`/plain/exact`) and an extension (`*.do`) mapping, an async streaming
`StreamServlet` (`/stream`: `startAsync`, three chunks flushed 150 ms apart from a container thread)
and a `ForwardServlet` (`/forward/*` forwards to `/plain/exact`), declared in `webapp/WEB-INF/web.xml`.
`./gradlew :agent:agent-testing:servletFixtureJakartaWar servletFixtureJavaxWar` builds
`build/servlet-fixture/servlet-fixture-{jakarta,javax}.war`; an integration run with a WAR app gets
their paths as `argos.test.servletFixtureWar.{jakarta,javax}`, and the WO-12 WAR runs deploy them
as `/servlet-fixture` next to the sample app (`AppLauncher.webapp`). Test-only, never shipped.

## FakeCollector (`collector`, 18 §5.3)

```java
try (FakeCollector collector = FakeCollector.builder().tls(true).heartbeatIntervalS(1).start()) {
  collector.faults().latency(Duration.ofMillis(200)).clockOffset(Duration.ofSeconds(3));
  try (RunningApp app = AppLauncher.of(...).withAgent(agentJar).collector(collector).start()) {
    collector.awaitBatches(BatchKind.TRANSACTIONS, 10, Duration.ofSeconds(10));
    assertThat(collector.maxConcurrentUnaryCalls()).isLessThanOrEqualTo(4);        // T-AG-071
    assertThat(collector.clockOffsetMs(1)).hasValueSatisfying(o -> ...);             // T-ACC-02
    ConfigApplyOutcome applied = collector.pushConfig(Map.of("enabled", "false")).get(10, SECONDS);
    CommandOutcome dump = collector.command("THREAD_DUMP", Map.of()).get(10, SECONDS);
    assertThat(app.collected()).stats().sqlCount("select ...").isEqualTo(1000);
  }
}
```

- A grpc-netty server of argos.v1 (`:proto`, ADR-0010 C-104) on 127.0.0.1 in the test JVM (an
  agent in another JVM connects over loopback). Every data call is recorded as `ReceivedBatch`
  (kind, instance, epoch, seq, receive time, body, size, outcome, duplicate flag, dropped items,
  session id, masked token, `sent_at_ms`) before the answer is decided; no limits or quotas (the
  inbound message and metadata limits are lifted from gRPC's 4 MiB default, 18 §5.3): it is the
  "sent count" reference (DSN-18-04). Refused calls, retries and duplicates are recorded too;
  `Collected` (transactions, statistics, metrics, counters) reads only written batches (`ACKED`),
  so a retried or duplicated batch counts once. `address()` is `127.0.0.1:<port>` with and
  without TLS.
- The 11 §16 contract (each behaviour cites its clause in the javadoc of `FakeCollector` and
  `FaultInjector`; `contract.ProtocolContractTest` replays the vectors below):
  - 11 §5 session: `AgentHello` → `ServerHello` with an instance id (from `firstInstanceId`, reused
    for the same `(app_name, env, instance_name)` in every session, also after `serverRestart()`,
    11 §5 "재기동 연속성"), a new UUID `session_id`,
    `server_time_ms`, the 11 §4.2 `Limits` defaults (`ProtocolLimits.defaults()`),
    `heartbeat_interval_s` (`heartbeatIntervalS(n)`, default 30), `config(...)`, `user_id_salt`
    (`userIdSalt(...)`, over TLS only). Hello checks in the order of the 11 §5 table: 1 token, 2
    app, 3 plaintext (`plaintextRejected()`), 4 protocol version in `[1, ProtocolVersion.CURRENT]`,
    5 registration, 6 limits; `rejectHello` injects a reason at its own check. An instance has one
    session (12 §4.1): a hello for an instance with an open session sends the old stream
    `SessionClose{REPLACED}` whatever its `agent_epoch` (check 7; an agent that reconnects has
    already cancelled that stream), and the old session id is refused from then on, also when its
    stream had already ended.
  - 11 §2/§3 data calls need `authorization: Bearer argos_<base64url>` (any well-formed token, or
    exactly `requireToken(t)`), `argos-instance-id` and `argos-session-id` of an accepted session:
    otherwise `UNAUTHENTICATED` (trailer `argos-reject-reason: TOKEN_INVALID`) or
    `FAILED_PRECONDITION` with description `SESSION_REQUIRED` (before a hello, after
    `serverRestart`, after `sessionClose`, after a new hello of the instance); another app's token
    on a session is `PERMISSION_DENIED`. The token check comes first (12 §3.1): a token revoked by
    `sessionClose(TOKEN_REVOKED_CLOSE)` is `UNAUTHENTICATED` with `argos-reject-reason:
    TOKEN_REVOKED`. A session stays valid after its stream ends, so the agent's shutdown flush after
    `AgentBye` is acknowledged (11 §6 "종료").
  - 11 §6 deduplication (`Deduplicator`): key `(instance_id, agent_epoch, kind, seq)`, the 1,000
    most recently written seqs per `(instance, epoch, kind)`; `ACK_DUPLICATE` with `last_seq`, the
    seq most recently written for the key (11 §4.3, 12 §3.2: the batch's own seq on `ACK_OK`, so a
    retry of an older seq lowers it); an error answer writes nothing, so its retry is `ACK_OK`. The
    windows survive `serverRestart()` (12 §3.4: the server rebuilds them from the WAL), so a seq
    written before a restart is `ACK_DUPLICATE` after it (D-13).
  - 11 §9 clock (`ClockEstimator`): every heartbeat is answered with t2/t3 and completes the round
    of its `prev_t1`/`prev_t4`: `offset = ((t2 - t1) + (t3 - t4)) / 2` (server minus agent),
    `rtt = (t4 - t1) - (t3 - t2)`, median of the last 5 accepted offsets, a round with rtt > 3 x the
    median rtt of the last 5 rounds (discarded ones count, so a lasting rtt change cannot freeze
    the estimate) discarded, `server_time_ms - sent_at_ms` before the first round.
    `HeartbeatAck.clock_offset_ms` carries the estimate (the §9 value, server minus agent, ADR-0010
    C-139) and `state` is `WARN` at
    |offset| >= 1 s.
    `clock(LongSupplier)` injects the collector's wall clock (18 §19).
- `FaultInjector` (status codes and trailers of 11 §5 and §10): `latency(Duration)`,
  `failNext(n, Status[, retryAfter])` (`argos-retry-after-ms`), `hang()` (no answer to data calls,
  hellos or heartbeats), `rejectAuth()`, `requireToken(token)`, `plaintextRejected()`
  (`PLAINTEXT_NOT_ALLOWED`, `FAILED_PRECONDITION`), `rejectHello(reason[, retryAfter])` (the 11 §5
  status of each `RejectReason`: `TOKEN_*` `UNAUTHENTICATED` with 300 s as the server sends it
  (ADR-0010 C-140; the agent waits a fixed 5 minutes anyway), `APP_MISMATCH` `PERMISSION_DENIED`,
  version and plaintext `FAILED_PRECONDITION`, `APP_NOT_REGISTERED` completes, `INSTANCE_LIMIT`
  and `RATE_LIMITED` `RESOURCE_EXHAUSTED` with 60 s), `partialDrop(n)` (`ACK_PARTIAL_DROP`),
  `walFull()` (`ACK_OK` with every item dropped and nothing written, 11 §6 "서버 드롭", DSN-11-09),
  `throttle(rate, backoff)` (`Ack.throttle`), `clockOffset(Duration)`, `pauseSessionReads()` and
  `resumeSessionReads()` (the collector stops reading the session streams, so the client's stream
  stops being ready, T-AG-071; `FakeCollector.builder().flowControlWindow(bytes)` makes that
  happen after little data, the default window is gRPC's 1 MiB and up), `closeStream()` (connection
  loss, `UNAVAILABLE`), `sessionClose(reason, retryAfter)` (`SessionClose`, then
  `TOKEN_REVOKED_CLOSE` `UNAUTHENTICATED`, `SERVER_SHUTDOWN` `UNAVAILABLE`, others OK; sessions
  forgotten; `TOKEN_REVOKED_CLOSE` also revokes the sessions' tokens, 12 §4.1: data calls get
  `UNAUTHENTICATED`/`TOKEN_REVOKED`, hellos `ServerHello{TOKEN_REVOKED, retry_after_s 300}`, until
  `clear()` or another token; `SERVER_SHUTDOWN` does not imitate the 12 §3.9 drain, so data calls
  get `SESSION_REQUIRED` where a real server answers `UNAVAILABLE`),
  `serverRestart()` (same port, sessions forgotten, instance ids and dedup windows kept as a real
  restart keeps them, 11 §5, 12 §3.4), `serverRestart(newInstanceId)` (a restart that also lost
  the instance registry: the next hellos get new ids from `newInstanceId`), `plaintextOnly()`,
  `wrongHostCertificate()` (TLS for `wrong.example`), `clear()` (also lifts revocations).
- The collector view reads like the JSONL one (`V1Protocol`, same assertion DSL, 18 §5.5): the field
  names of the JSONL exporter, step `seq` and `parent` in its numbering (0-based, -1 for a root; the
  wire is 1-based with 0 for a root), `SUMMARY` steps as `summary[]` entries. Not on the wire, so
  `unsupported` in the DSL: `service_fixed`, `summary_overflow`, a step's `thread`. Only on the wire
  (the full list is `V1Protocol.SOURCE`): `headers`, `request_id`, `sql_ids`, `dbc_wait_ms`,
  `dispatch_count`, `timed_out`, a step's `failure` and `batch_size`, among others. `thread` is the
  normalized name (the JSONL's `thread_name`), and
  `truncated` and `total_steps` come with the profile, so a transaction that was not sampled has
  none. Steps and dictionary texts travel apart from the record (11 §3, §7): `awaitTransactions`
  also waits up to 2 s for what its matches still miss, so read through it, not `transactions()`,
  right after a request. An unknown dictionary id reads `#<id>`. `CollectorWiringTest` compares the
  two views on the same request.
- Server messages: `pushConfig(...)` and `command(...)` complete with the agent's
  `ConfigApplyResult` and joined `CommandResult` chunks; `requestDictionary(kind, hashes...)`
  (11 §7 step 3, the answer lands in `dictionary()`); `sendThrottle(rate, backoff)` (11 §6).
- Accessors: `batches()`, `seqs(kind)` (arrival order, retries and duplicates included),
  `batchesAfter(instant)`, `counts()` (`KindCounts` per kind), `maxConcurrentUnaryCalls()` (the
  high-water mark of data calls between arrival and answer, never above the client's own
  outstanding calls; `resetMaxConcurrentUnaryCalls()`), `heartbeats()` (`HeartbeatRecord`: t1,
  prev_t1, prev_t4, t2, t3, completed round, estimate, `dropped_total`), `clockOffsetMs(instance)`,
  `byes()` and `flushAfterBye(bye)` (time from `AgentBye` to the last batch after it, T-AG-067),
  `sessions()` (`SessionInfo` with session id, reject reason, epoch, plaintext, masked token),
  `acceptedSessionCount()`, `reconnectCount()`, `openSessionCount()`, `dictionaryEntries()`.
- Tokens: `token()` is a random per-collector test token (`argos_<base64url>`); received tokens are
  `ReceivedToken`s whose `toString` is `argos_****` (ADR-0010 C-94), so records and AssertJ
  failure messages never show a value; compare with `matches(expected)`.
- TLS: a throw-away self-signed certificate for `localhost`, `host.docker.internal` and 127.0.0.1
  made with the JDK's `keytool` at start and deleted at `close()` (`trustPem()` is the client's
  trust anchor, `server.tls.ca-file`). No key material is stored in the repository.
- Wire seam: tests use only `FakeCollector`, `FaultInjector`, `Collected` and the records of the
  package. Everything that touches generated protobuf types is one `CollectorProtocol`
  implementation, `v1.V1Protocol` over `io.argos.proto.v1` (`api(project(":proto"))`; the WO-02
  transport PoC keeps its own snapshot in `test/agent/transport-poc`). `FakeCollectorTest`
  (integrationTest) proves every fault with a real grpc-netty client (`ScriptedAgent`).

## Contract vectors (11 §16, reference for WO-21)

`src/main/resources/contract/argos-v1-vectors.json` (`ContractVectors.load()`) holds fixed inputs
and expected outputs: `clock` (11 §9 heartbeats with t1/prev_t1/prev_t4/t2/t3, the completed
round, the estimate after each heartbeat), `dedup` (11 §6 steps with kind, epoch, seq and the
expected `AckStatus`/`last_seq`, the 1,000 window and a retry after an error), `session` (11 §3/§5
scripted steps: data before a hello, metadata and token checks, a restart that keeps the instance
id and its dedup window, hello check order, REPLACED) and `status_codes` (11 §5/§10: rejections,
failures, quota drop, WAL full, deadline, throttle and session closes with their code,
`retry_after_s` and trailers; `TOKEN_*` hellos carry 300 s, ADR-0010 C-140). `interpretations` lists
the choices 11 leaves open or that 12 settles (window semantics, `last_seq`, rounding, which rounds
count for the median rtt, session validity after the stream ends, REPLACED without an epoch
exception, restart continuity, the `TOKEN_*` retry advice). The vectors carry the design values
(ADR-0010 C-159 (2)), so the server's `ContractVectorsIT` asserts them as they are;
`expected_instance_id` numbers the instances of a scenario from 1 (the FakeCollector's first id),
and a server with its own ids checks that equal numbers are one id. The unit tests
`ClockEstimatorTest` and `DeduplicatorTest` check the arithmetic without network;
`contract.ProtocolContractTest` replays every section over gRPC (the clock with
`FakeCollector.builder().clock(...)`). The server's ingest tests (WO-21, WO-22) can replay the
`session`, `dedup` and `clock` sections (a `fault` step there names the state to set up first) and
the `status_codes` entries without a `fault` field against the real collector; a `status_codes`
entry with a `fault` field pins the FakeCollector's answer to an injected fault (`FaultInjector`)
and is not a server replay. A session close's `expected_data_after` is the answer to a data call
with the closed session's token and ids (code required, description and trailers optional):
`TOKEN_REVOKED_CLOSE` `UNAUTHENTICATED` with `argos-reject-reason: TOKEN_REVOKED` (12 §3.1 token
before session, 12 §4.1 revocation), `IDLE_TIMEOUT` `SESSION_REQUIRED`, and none for
`SERVER_SHUTDOWN` (12 §3.9: the server drains and answers `UNAVAILABLE`; the FakeCollector does not
imitate the drain).

## Standalone collector (bench, development-only)

```
./gradlew :agent:agent-testing:fakeCollectorJar      # build/fake-collector/fake-collector.jar + lib/
java -jar agent/agent-testing/build/fake-collector/fake-collector.jar \
    --port 0 --ready-file build/fc.ready --token-file build/fc.token [--tls --tls-dir build/fc-tls] \
    [--report-every 10] [--duration 0] [--summary build/fc-summary.json]
```

The `FakeCollector` as its own process (`FakeCollectorMain`) for the overhead bench
(`--exporter grpc`, 18 §7) and manual runs; never shipped. It keeps per-kind totals only
(`retainBatches(false)`), follows the same 11 §16 contract, and prints `fake-collector listening on
<host:port> tls=... ca-file=... token=file:<path>` once and `fake-collector t=<s>s sessions=...
max_in_flight=... | transactions batches=... items=... bytes=... acked=... dup=... failed=... | ...`
every `--report-every` seconds (`0`: only at the end) and at the end (`final`). `--ready-file` gets
`host:port` to connect to (and the CA path with `--tls`) once listening; `--token-file` gets the
collector's test token (owner-only) and then only that token is accepted, so the agent uses
`server.token-file=<that file>`; the token is never printed. `--summary` writes the final totals as
JSON. Other options: `--bind`, `--heartbeat-interval-s`. The jar's manifest names `lib/*.jar` (the
runtime classpath), so keep the two together. `StandaloneCollectorTest` runs the jar as a process.

An agent connects with `server.address=<ready file, line 1>`, `server.token-file=<--token-file>` and
either `server.tls=false` or, with `--tls`, `server.tls.ca-file=<ready file, line 2>`.

## Exporters and the test namespace (18 §5.4, §15, T-BLD-010)

| Setting | Effect |
|---|---|
| `-Dargos.test.exporter=file:<dir>` | JSONL in `<dir>/argos-transactions.jsonl` (`FileExporterReader`) |
| `-Dargos.test.exporter=noop` | encode every transaction as the sender does (protobuf `BatchEncoder`, dictionary entries included; 18 §5.4), then drop |
| `-Dargos.test.exporter=drop` | record and drop without encoding (the WO-02 P-2 baseline, formerly `-Dargos.exporter=noop`; ADR-0009 C-80, C-88) |
| `-Dargos.test.exporter=grpc` | the default exporter: the gRPC transport to `server.address` (WO-11) |
| `-Dargos.test.faults=<spec>,...` | `advice-throw:<module>`, `transform-error:<module>`, `helper-nocdef:<module>` (module unit, see below) |
| `-Dargos.test.selfdiag-file=<file>` | self-diagnostics snapshot (ADR-0010 C-95, 18 §19): JSON rewritten atomically every 2 s and at shutdown, plus `<file>.premain-classes` once after the start (C-79); `SelfDiagnostics`, `ExitedApp.premainClasses()` |
| `-Dargos.debug.transform-log=<file>` | transform log (below); a diagnostic key, not test-only |

The WO-02 PoC keys `argos.exporter` and `argos.exporter.file.*` are gone (ADR-0009 C-88): the
agent reports them as unknown keys and uses the `grpc` slot. Every test key is a `TEST` entry of
the registry `io.argos.agent.core.config.ConfigKeys`. Release builds ignore `argos.test.*` and
`argos.testing.*` (system properties and `ARGOS_TEST_*`) and log one WARN naming them: the gate is
in `ConfigLoader`, the single place where the agent reads settings, and the release marker is the
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
  with `unsupported: ...`: on the JSONL exporter `headers`, `request_id`, `dbc_wait_ms`, `sql_ids`,
  `dispatch_count`, statistics, metrics, dictionary and counters; on the collector view the fields
  listed under FakeCollector, among them step threads (`noStepFromThread`). Nothing passes on data
  that cannot exist.

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
`trivial-probe-<jdk>` for JDK 8, 11, 17, 21, 25, `shop-boot2-{8,11,17,21}`, `shop-boot3-{17,21,25}`,
`shop-boot4-21` (WO-12).

Premain class budget (ADR-0009 C-79, N-30, `PremainBudgetAttachTest`): the release agent jar on the
trivial app with `-Dargos.debug.transform-timing=true`; the agent writes the classes its class
loader defined on the premain thread to `<selfdiag-file>.premain-classes`, and `PremainClassBudget`
compares them with `baselines/premain-classes/trivial-release-<jdk>.txt`: more classes than the
recorded count fail (a class that was not there before is named), fewer print a hint to re-record,
and any class of the shaded transport (`io.argos.agent.shaded.` + `io.grpc.`, `io.netty.`,
`io.perfmark.`, `com.google.protobuf.`, `com.google.common.`, `com.google.thirdparty.`,
`com.google.gson.`, `com.google.rpc.`, `com.google.type.`, `com.google.longrunning.`,
`com.google.api.`: the transport packages of `AgentShading.RELOCATED_PACKAGES`, kept in step with
it) on the premain thread fails whatever the budget (10 §4.3 P7: the transport of WO-11 starts on an
agent thread). ByteBuddy and JCTools are not on the list (premain builds the transformer and may
create the queues). `PremainClassBudgetTest` exercises the rule on fixed lists and
`PremainBudgetAttachTest` enforces it on real runs. The same `-Pagent.baseline.record=true`
re-records the budgets; review the class diff like a transform baseline.

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
- Re-entrance (T-AG-050): `noneFromThread(startsWith("argos-"))` on transactions and
  `noStepFromThread(startsWith("argos-"))` on their steps (every step carries its thread name in
  the JSONL), no `io.argos.*` class (own or shaded) in the transform log, at most 6 `argos-`
  threads (N-26), and the self-diagnostics counters `sql_outside_tx_total` and
  `orphan_steps_total` unchanged by the load (the agent's own threads raise neither). The counter
  part has no producer in the release agent yet (SQL outside a transaction comes with WO-13 jdbc,
  orphan steps with WO-15/WO-18), so on the current build it cannot fail; the measured evidence is
  the thread checks and the transform log. The counter path itself is checked with the probe jar:
  `AgentAttachTest` T-AG-040 (`advice-throw:test-probe`) expects exactly 3
  `argos_agent_orphan_steps_total{type="async"}` from `test-probe-jdk`'s `ExecuteAdvice`.
- `LeakProbe.of(app).check()` (T-AG-053): `jcmd GC.run` twice, then ThreadLocal residue, active
  registry and weak map (`weakmap_size`, the context-holding entries) from a self-diagnostics
  snapshot written after the GC (the agent expunges
  collected weak keys before it measures; a gauge it does not report is NOT_AVAILABLE, never
  passed) and heap residency from `GC.class_histogram` (`io.argos.agent.` bytes, shaded classes
  included, <= 64 MB, ADR-0009 C-87; the sample apps' `io.argos.sample.*` are not counted;
  NOT_AVAILABLE, not PASS, when the histogram has no rows or, for a run with the agent, no
  `io.argos.agent.` instance).
  `assertNoFailure()` fails on FAIL; `assertAllPassed()` also fails on NOT_AVAILABLE.
  `isNotFromThread`/`noneFromThread` fail on a transaction without a thread name. `TomcatRedeployDriver` (manager text
  API, `ParallelWebappClassLoader` count, ThreadLocal leak warnings) and `NmtSummary` (`Internal +
  Other` <= 16 MB, threads <= 6) are the WAR and native parts, unit-tested; the redeploy cycles and
  NMT run with the WAR matrix (WO-50), while the ThreadLocal leak warnings are judged on every WAR
  run of the launcher (`TomcatLogCheck`, `WarAppAgentTest`).

## Logs and Unsafe (18 §5.10)

- `AgentLogAssert.assertThatAgentLog(log).hasNoErrors().hasOnlyAllowedWarnings()` (T-AG-060):
  every WARN must match a regular expression of `fixtures/log-allowlist/agent-info.txt` (the goal is
  an empty list); `hasNoWarnings()` is the stricter 03 §3.1
  rule the normal-scenario tests use. `assertQuietConsole(stdout, stderr)`: only the application's
  log lines on stdout, only the JVM's CDS notice on stderr. `doesNotContainSeedSentinel()`
  (T-SEC-26), `doesNotContainText(token)` (T-SEC-06).
- `UnsafeScanner` (T-BLD-004): constant-pool scan of every class and `inst/**.classdata` entry for
  `sun/misc/Unsafe` and `sun.misc.Unsafe`, the two patterns of `test/agent/check-shading.sh`. One
  allow-list for both: `test/agent/unsafe-allowlist.txt` (ADR-0009 C-74: a shaded class that loads,
  with a proof test and a reason on its line; `JvmToolsTest` checks the reason and the shaded
  prefix). 18 §5.10 calls it `fixtures/unsafe-allowlist.txt`; the repository file is the single
  source of truth.

## Agent rules (`archTest`, part of `check`)

`./gradlew :agent:agent-testing:archTest` checks the classes of every agent module
(`agent-bootstrap`, `agent-core`, every `agent-instrumentation` module):

| Rules | Source | Classes |
|---|---|---|
| T-AG-001a..d, T-AG-002a..d, T-AG-003a..e, ADV-04 | 19 §5.3 ADV-01..04, 10 §5.3 A-1..A-6, C-9 | `src/archTest/.../AdviceRules.java` |
| AGT-01..03, B-1..B-4, T-AG-004 | 19 §5.3, 10 §2 | `src/archTest/.../AgentBoundaryRules.java` |
| LOG-01, A-7 (T-AG-003f), A-9 (T-AG-004a) | 10 §14 (SEC-26), 10 §5.3 A-7, A-9 | `src/archTest/.../AgentCodeRules.java` |

- AGT-02 allows stdout/stderr only in `core.log` (the stderr fallback) and `core.check`, plus one
  exception: the entry point `AgentPremain` may write the single stderr line `argos: agent
  disabled: <reason>` of a start that failed before the agent's logger existed (ADR-0009 C-78),
  never stdout or a stack trace.
- LOG-01: the arguments of `AgentLogger.info/warn/error/warnOnce` calls are constants, numbers,
  enums or values from the reviewed producers of `src/archTest/resources/log-argument-allowlist.txt`
  (identifiers that are not collected data), so collected data cannot reach INFO logs (10 §14
  static check); DEBUG is not checked.

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
./gradlew :agent:agent-testing:integrationTest --tests '*transport*' \
    -Pagent.jdk8u252=<JRE home> -Pagent.jdk8u242=<JRE home>                  # gRPC transport (WO-11) with the old JREs
```

The old JREs (AdoptOpenJDK `jdk8u242-b08` and `jdk8u252-b09.1`, docs/poc/agent-poc.md 9.8) are
not Gradle toolchains: `-Pagent.jdk8u252`/`-Pagent.jdk8u242` (or the environment variables
`ARGOS_TEST_JDK8U252`/`ARGOS_TEST_JDK8U242`) name their homes, and without them
`transport.OldJreTransportTest` is skipped and says so. On Windows unpack them outside `%TEMP%`.

The classes are split by requirement: a dynamic test carries only the tags of its factory and class
(test/README.md §5), so each class has one factory and class-level `req:` tags. Shared launch and
assertion code is in `attach.AttachRuns` and `e2e.E2eRuns`; each app start of the WO-02 classes
happens once, as before.

| Class | Tags | Tests (per JDK / combination) |
|---|---|---|
| `attach.AgentAttachTest` | F-A31, F-A35 | trivial app on `-Pagent.attach.jdks`: T-BLD-006/T-AG-060/T-AG-005 release jar, T-AG-060 `-Xshare:off`, T-AG-010 probe jar, T-AG-070 preloaded JDK classes, T-AG-043 kill switches (2), T-AG-040 advice fault (and 3 orphan ASYNC steps counted in the self-diagnostics), F-A35 Unsafe code paths |
| `attach.ReleaseGateAttachTest` | DSN-18-15 | T-BLD-010 release gate |
| `attach.FailSafeAttachTest` | F-A15 | T-AG-041 transform errors (WARN and self-diagnostics), T-AG-040 helper-nocdef (plus two specs that inject nothing and are reported), T-AG-044 initialization failure (ERROR in the log although the JVM exits at once, no keep-alive), T-AG-044/C-78 the same without a writable log directory and before the logger exists (exactly one `argos: agent disabled:` stderr line), T-AG-042 `instrumentation.exclude-classes`, T-AG-045 stderr fallback (10 lines, WARN and above) |
| `attach.PremainBudgetAttachTest` | N-30 | premain class budget and no transport class on the premain thread (C-79), the step timing printed as a reference value |
| `e2e.SampleAppAgentTest` | F-A01, F-A02, F-A03, F-A05, F-A07, F-A28, F-A31 | per jar combination of `-Pagent.e2e`: T-AG-010/T-AG-070/T-AG-005; first combination: T-BLD-006 baseline |
| `e2e.FailSafeE2eTest` | F-A15 | first jar combination: T-AG-040 servlet and data-module faults, T-AG-041 jdbc transform errors, T-AG-040 helper-nocdef jdbc |
| `e2e.LoadLeakE2eTest` | F-A15, N-02 | first jar combination: T-AG-050/T-AG-053 10,000 requests (18 §5.9) in two batches of 5,000: no transaction or step from an `argos-` thread, counters unchanged (no producer before WO-13/15/18), ThreadLocal / registry / weak map (`weakmap_size`) 0 after GC, `weakmap_entries` after GC not higher after batch 2 than after batch 1, heap <= 64 MB (C-87); every check measured (no NOT_AVAILABLE) |
| `e2e.WarAppAgentTest` | F-A01, DSN-18-03 | per WAR combination of `-Pagent.e2e` (none by default: the class is then skipped), default Tomcat of the JDK: one request to the first context is one transaction (status 200, `<context> GET /api/orders/{id}`), no agent ERROR, quiet console; after a graceful stop the Tomcat log has no WARNING or SEVERE line naming the agent, and no SEVERE line or ThreadLocal leak warning beyond the same run without the agent (started only when needed: shop-war-jakarta's own WebClient leaves Netty's `InternalThreadLocalMap` behind) (WO-05 r1-new-1) |
| `servlet.RequestBoundaryTest` | F-A01, F-A02, F-A28, D-04, D-21 | WO-12, per shop jar app (`shop-boot2/3/4` of `-Pagent.e2e`): T-AG-010; T-AG-011/T-ACC-04/T-ACC-21 (exception, 404, sendError, forward, async dispatch x 100 each = exactly 100 transactions each, final status, no `/error`; `argos_agent_transactions_unanchored_total` in the self-diagnostics +100 for exception, 404 and sendError, whose ERROR dispatch runs after the transaction ended, +0 for forward and async); T-AG-011 `dispatch_count` over gRPC |
| `servlet.IgnoreRulesTest` | F-A23, D-19 | T-AG-012/T-ACC-19: 100 noise requests (health, favicon, `.js`, OPTIONS, `kube-probe` User-Agent) = 0 transactions, exclusion counters +100 by reason in the self-diagnostics |
| `servlet.FallbackNameTest` | F-A29 | T-AG-013: F-A29 fallback names (404, 405), no `/error`; first combination with `instrumentation.service-dictionary-max=5`: `_other_` and `service_dictionary_overflow_total` |
| `servlet.SpringPatternTest` | F-A02 | T-AG-020 Spring part on Framework 5.3/6.2/7.0 (10 U-8): `bestMatchingPattern` names |
| `servlet.StreamingTest` | F-A34, F-A28 | T-AG-016: SSE and WebSocket 101 with the streaming flag, DeferredResult and Callable one transaction each; over gRPC `transaction.max-duration-ms=1000` -> `timed_out`, wire `streaming` |
| `servlet.CaptureTest` | SEC-31, SEC-32, SEC-34, SEC-37 | T-AG-014/T-AG-017 and the agent side of T-SEC-31/32/34/37: privacy defaults (JSONL); query whitelist, IP full, trusted `X-Forwarded-For`, hashed session user id, header whitelist, request id (C-124) over gRPC with a salt; query full, IP off, plain header user id; hashed without salt (C-125: no user id, `no_salt` counter, one WARN) |
| `servlet.SeedLogTest` | SEC-26, SEC-32 | T-SEC-26b (partial T-SEC-26, request path only): the `seed.*` catalog requests and one request with seed values in every capture input (session and header user id, whitelisted header, request id, `X-Forwarded-For`, query string), in the `DEFAULTS`, `CAPTURE` (hashed user id with the salt) and `MODES` (query full, plain user id) runs; the records show the values were read, the INFO agent log has no `ARGOS_SEED`/`ARGOSSEED`, seed PII, plain or hashed user id, header or request id value, and no form of the salt (hex, Base64, Base64url, array text; SEC-32); the console has none of the agent-only values |
| `servlet.WarContextPrefixTest`, `WarDispatchTest`, `WarServletMappingTest`, `WarStreamingTest` | F-A36; F-A28, D-04, D-21; F-A02; F-A34 | per WAR combination (shop-war-jakarta on Tomcat 10.1 with `/shop-a` and `/shop-b`, shop-war-javax on Tomcat 9) plus the Spring-free fixture: T-AG-015 context prefixes, T-AG-011 in a WAR (`ErrorPageFilter`, nothing unanchored), T-AG-020 `HttpServletMapping` names (prefix, exact, extension, forward, default servlet fallback), T-AG-016 servlet streaming and SSE. Each class ends per combination with the Tomcat log of its run after a graceful stop (`WarRuns.judgeTomcatLog`, `TomcatLogCheck`: no WARNING or SEVERE naming the agent in its line or stack, no SEVERE or ThreadLocal leak beyond the same web applications without the agent) |

The `servlet` classes share their app processes (`servlet.ServletRuns`, JUnit root store, at most
6 alive, the least recently used one stopped first; tests run profile by profile): profiles `DEFAULTS` (privacy defaults plus
`instrumentation.ignore-user-agents=kube-probe/*`, JSONL, every shop jar app), `CAPTURE`
(widened capture, TLS FakeCollector with a user id salt, `transaction.max-duration-ms=1000`, every
shop jar app), `MODES` (query full, IP off, plain user id, JSONL, first shop jar app), `LIMITS`
(`service-dictionary-max=5`, hashed user id, TLS FakeCollector without salt, first shop jar app),
`WAR` (privacy defaults plus the servlet fixture). Each test counts only the transactions its own
requests add, so class order does not matter. A `WAR` run serves one class and stops at its end
(Tomcat log judgement), so each WAR class starts its own Tomcat; the no-agent run of a WAR
combination starts at most once, only when a Tomcat log is not clean. A process started again gets
a new directory and evidence prefix (`wo12-<app>-jdk<N>-<profile>-<n>-*`, n = 2, 3, ...; the
no-agent run `wo12-<app>-jdk<N>-war-noagent-tomcat.log`). Every factory carries its test IDs in
its `@DisplayName`, so a factory skipped by its assumption (no shop jar or WAR app in
`-Pagent.e2e`) still shows its IDs as SKIPPED in `traceabilityReport`. Commands:

```
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21,shop-boot4@21 --tests '*servlet*'
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-war-javax@8,shop-war-jakarta@21 --tests '*War*'
```
| `BytecodeVerificationFlagTest` | T-AG-006: the test JVM and the preset on every attach JDK |
| `collector.FakeCollectorTest` | DSN-18-04: recording and acks, `ServerHello` contents and instance reuse, `latency`, `failNext` (retry-after trailer), `hang`, `rejectAuth`, `requireToken` (masked tokens), session round trips (config, command, dictionary request, throttle), `closeStream`, `serverRestart` (`SESSION_REQUIRED`; instance id and dedup window kept, or a new id with `serverRestart(id)`), `sessionClose` (per reason) and the revoked token (`TOKEN_REVOKED` on data calls and hellos, `clear()` lifts it), `plaintextRejected`, `rejectHello` and the §5 check order, `clockOffset` (T-ACC-02 collector side), `partialDrop`, `walFull`, `pauseSessionReads`, `throttle`, duplicates, concurrency high-water mark, `AgentBye` and flush, `REPLACED`, large batch, gzip, TLS modes |
| `collector.StandaloneCollectorTest` | DSN-18-04: `fake-collector.jar` as a process (TLS, token file, report, summary); `--report-every 0` sleeps, wildcard `--bind` |
| `collector.CollectorWiringTest` | DSN-18-04, SEC-06: `collector(c)` settings, token file and CA copy, no token on the command line or in the agent's output; end to end over gRPC, with the JSONL view of the same request as the reference (process; container with `-Pagent.container=true`) when the release jar has the gRPC exporter, else skipped |
| `contract.ProtocolContractTest` | DSN-11-01, DSN-11-02: the contract vectors over gRPC (11 §3/§5 session order, §6 dedup and acks, §9 clock, §5/§10 codes and trailers) |
| `jvm.AgentJarScanTest` | T-BLD-004 on the release jar |
| `transport.*` (14 classes, shared code in `TransportRuns` and `TcpProbe`) | SEC-06, SEC-07, SEC-08, N-26, F-A35 and others per class | the gRPC transport of WO-11 against the `FakeCollector`: T-AG-071 back pressure and seq order, T-AG-067 `AgentBye` and the 1 s flush, T-ACC-02 clock (+3 s), T-ACC-06 drops during `hang()`, T-SEC-06 token (metadata only, never in log, console, self-diagnostics, hello or batches, also at `log.level=DEBUG`), T-SEC-07/T-SEC-08 TLS (host name, unknown CA, skip-verify and plaintext WARN per connection, plaintext refusal), T-NFR-26a threads and direct memory, T-BLD-004a C-74 Unsafe proof (class-load records per JDK), T-BLD-005/006 and C-75 (Windows `socket_<n>` only; Linux in container mode), T-BLD-011 network references and T-BLD-011a no proxy of the application JVM at run time, T-AG-060, session reactions (C-102 token backoff, rejects, REPLACED not retried, a refusal for good releases the connection and `argos-io`, TCP connects follow the session back-off, an unresolvable host logs once per minute, `Ack.throttle`), the shaded protocol descriptors, 8u242/8u252 (old JREs above). `-Pagent.container=true` adds the POSIX token-file WARN, Linux C-75 and Linux T-NFR-26a |
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
