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
  test's settings, all as `-Dargos.<key>`. A run whose settings set
  `sampling.profile.normal-rate` to 1.0 also gets `test.overhead-monitor=off` unless it sets that
  key itself (ADR-0010 C-240 (3)): the agent's CPU guard would otherwise halve the rate on a host
  with few cores. `agentConfigFile(true)` also writes `argos-agent.yml`
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
  On Windows `close()` also waits (at most 10 s) until no other process holds a file of the run
  directory open: right after the app exits, a virus scanner can keep its jar and logs open for a
  moment and the `@TempDir` cleanup would fail although the test passed (`ProcessAppReleaseTest`,
  a slow `integrationTest` since it holds a file for about a second, ADR-0010 C-186 (4)).
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
  warnings that the same run without the agent logs too (timestamps, thread names and hash codes ignored), never
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
  The development-only registry members of WO-13 read as `SelfDiagnostics.pools()` and
  `connectors()` (`Gauges`: name, pool kind, the values the agent read from the getters; a value
  the agent could not read is absent and `value(field)` throws `NotAvailableException`), and
  `registry_counters` join `counters()`.
  `ExitedApp.selfDiagnostics()` reads the snapshot the agent wrote at shutdown and
  `ExitedApp.premainClasses()` the sorted list `<file>.premain-classes` (the classes the agent class
  loader defined on the premain thread, C-79). WO-17 adds the `SELF_DIAGNOSTICS` command as a second
  source of the same snapshot.
- Tomcat manager (WO-50): `tomcatManager(true)` (WAR apps) also stages the distribution's
  `webapps/manager` with its own `META-INF/context.xml` (privileged, `RemoteAddrValve` allowing
  127.0.0.1 only), a generated `conf/tomcat-users.xml` with one `manager-script` user
  (`argos-matrix`, a random 128-bit password per run, owner read and write only on POSIX file
  systems, `ManagerAccess.toString()` masks it, the harness never prints it) and the
  `UserDatabase` resource and realm in `server.xml`. `RunningApp.tomcatManager()` gives the access
  of the run, `TomcatRedeployDriver.of(app)` the HTTP manager (`/manager/text/*`, basic auth; any
  status but 200 fails with the command name only). Without the option nothing changes.
- Matrix rows (WO-50, `test/matrix/matrix.yaml`): two system properties of the test JVM apply to
  every launch. `argos.test.matrix.appEnv=K=V;K=V` adds application environment variables before
  the test's own `withEnv` (which wins; for example `SAMPLE_CONTAINER=jetty`, `SAMPLE_DB=postgres`;
  a malformed item fails the launcher), and `argos.test.matrix.tomcat=<tomcat9|tomcat10|tomcat11>`
  picks the Tomcat of every WAR launch whose app has that key, over `tomcat(key)` and the default
  (the row decides the container of every suite it runs). `withExtraAgent(jar, Order.BEFORE |
  AFTER, options)` adds another `-javaagent` before the Argos agent or after it and its settings
  (coexistence rows, 18 §6.4; local processes only). `MatrixLauncherTest` covers the options.

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

## JDBC suite (package `jdbc`, WO-13, 18 §6.3, ADR-0010 C-149)

`io.argos.agent.testing.jdbc` runs the shop jar apps of `-Pagent.e2e` (shop-boot2, shop-boot3,
shop-boot4) with the release agent jar over gRPC to a TLS FakeCollector against real databases
(`JdbcRuns`, `JdbcDatabase`):

```
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21 --tests '*jdbc*'
    # H2 only (the default integrationTest: one real driver through the sample apps' DB path, C-149 (1))
... -Pagent.jdbc.containers=true          # + MySQL 8.4, MariaDB 11.4, PostgreSQL 16, SQL Server 2022
... -Pagent.jdbc.containers=postgres,mysql # a subset
... -Pagent.jdbc.oracle=true               # + gvenzl/oracle-free (Oracle Database Free)
ARGOS_TEST_ORACLE_URL=... ARGOS_TEST_ORACLE_USER=... ARGOS_TEST_ORACLE_PASSWORD=...  # in-house Oracle 19c
```

- Databases (`JdbcDatabase`): Testcontainers `GenericContainer`s with the image pinned by digest,
  one at a time (the next database stops the previous one and every app on it), a memory limit
  (1 GiB; SQL Server and Oracle 4 GiB), the name `argos-wo13-jdbc-<db>-<n>-<pid>` and the label
  `argos.wo=WO-13`, a random password per run passed to the app as `SAMPLE_DB_PASSWORD` (never
  logged or printed); SQL Server gets `CREATE DATABASE shop` through the image's `sqlcmd` with
  its own `MSSQL_SA_PASSWORD`. The JDBC URLs carry no credentials. The container flags are the
  nightly or manual commands of C-149 (2); Oracle has its own flag (C-149 (3)); in-house Oracle
  19c, Tibero, Altibase, CUBRID and DB2 (`ARGOS_TEST_<DB>_URL/USER/PASSWORD`) are human checks and
  skip with the reason when unset (`VendorDatabaseTest`; Tibero, Altibase, CUBRID and DB2 also need
  a `SAMPLE_DB` value and their driver jar, WO-50).
- Runs (`JdbcRuns.Profile`): `DEFAULT` (bind parameters `masked`), `PARTIAL`
  (`capture.sql-parameters=partial`, whitelisted column `email`), `PARTIAL_OTHER_COLUMN`, `FULL`,
  `NESTED` (`SAMPLE_ROUTING_DS=true` + `SAMPLE_DRIVER_WRAPPER=true`, five JDBC levels, ADR-0004
  C-40), `POOL_TOMCAT`, `POOL_DBCP2`, `SECOND_POOL` and `SECOND_POOL_TOMCAT`
  (`SAMPLE_SECOND_POOL=true`: pools `shop-primary` and a restarted `shop-secondary`, sample-apps
  `SampleDataSources`); every run has `sampling.profile.normal-rate=1.0`. At most 4
  runs are alive; their agent log and console are kept as `build/reports/agent-e2e/wo13-<run>-*`.
  The console prints `[wo13-db]` lines (container start time, `docker stats` memory before stop)
  and `[wo13-table]`/`[wo13-sql-ids]` lines (the per-database result table of the report).
- Tests: `JdbcStepsTest` (per database and app: T-AG-030 one SQL step and `sql_count` 1, page
  rows and fetch time; T-AG-032 DBC step named by the pool, COMMIT, ROLLBACK, batch step;
  T-AG-035a `DB_TARGET` = `<vendor>://<host>:<port>/<db>`; T-SEC-3Aa (the agent-side partial
  check of T-SEC-3A, ADR-0010 C-182; `masked`) no `ARGOS_SEED`/`ARGOSSEED` in anything the collector received or the agent log after the SQL seed
  endpoints; T-ACC-24 and T-AG-031 one SQL step and `sql_count` 1 through the five-level nesting;
  T-AG-032c one DBC step named by the pool through the same five levels; then T-ACC-22a the same
  `sql_id` on every database),
  `JdbcParameterModesTest` (T-AG-033 masked/partial/full details), `JdbcOutsideTransactionTest`
  (T-AG-036 startup SQL counted in `argos_agent_sql_outside_tx_total`, never recorded),
  `PoolRegistryE2eTest` (T-AG-062f pool and connector registry values from
  `SelfDiagnostics.pools()`/`connectors()`, `InstanceUpdate` with `host:port`; T-AG-062g two
  pools of one app told apart by name and the restarted one re-registered with its new maximum;
  T-AG-032d the DBC step named after the pool the request borrowed from, per pool library;
  PostgreSQL with the container flag, else H2), `VendorDatabaseTest`.
- Two jdbc module gaps these tests found are fixed in the jdbc module (WO-13 integration): through
  the routing chain Spring's `TransactionAwareDataSourceProxy` hands out a lazy JDK proxy and takes
  the pool connection at its first use, so the lazy handle is no DBC step and the later pool
  acquisition is the one DBC step (T-AG-032c); Tomcat JDBC's `DataSource` inherits
  `getConnection()` from `DataSourceProxy`, which does not implement `javax.sql.DataSource`, so the
  DataSource rule names `DataSourceProxy` (T-AG-032d). The empty `commit()` that H2 and pgJDBC call
  from `setAutoCommit(true)` after a rollback is no COMMIT step any more (`AutoCommitGuardAdvice`),
  and T-AG-032 asserts no COMMIT step in the rolled back transaction on every database.
- Times on this laptop (shared, reference only): H2 with shop-boot2@8 and shop-boot3@21 about
  5 minutes with the build; with `-Pagent.jdbc.containers=true -Pagent.jdbc.oracle=true` about
  9.5 minutes (container starts: MySQL 21 s, MariaDB 11 s, PostgreSQL 2 s, SQL Server 13 s, Oracle
  Free 27 s; `docker stats` memory before stop: MySQL 0.5 GiB, MariaDB 0.13 GiB, PostgreSQL 0.04
  GiB, SQL Server 1.1 GiB, Oracle Free 3.4 GiB of its 4 GiB limit).

## HTTP client suite (package `httpclient`, WO-14, 10 §6.5, §8, SEC-38)

`io.argos.agent.testing.httpclient` runs the shop jar apps of `-Pagent.e2e` with the release agent
jar over gRPC to a TLS FakeCollector, each run with its own in-process ext-stub on 127.0.0.1
(`HttpClientRuns`, at most 3 runs alive). Every client the app reports in `GET /api/runtime` is
exercised through `/api/pay/{id}?client=..` (shop-boot2@8: hurl, hc4, okhttp, resttemplate;
shop-boot3/4: hurl, hc5, okhttp, jdk11, webclient, resttemplate):

```
./gradlew :agent:agent-testing:integrationTest --tests '*httpclient*'          # shop-boot3@21 (default)
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot4@21 --tests '*httpclient*'
```

- Runs (`HttpClientRuns.Profile`): `DEFAULT`, `ARGOS_STATE` (`propagation.argos-state.hosts=127.0.0.1`),
  `EXCLUDE` (`propagation.exclude-hosts=127.0.0.*` plus `argos-state.hosts`); every run has
  `sampling.profile.normal-rate=1.0` and waits for its collector session (the agent's
  `collector session established` line) before the first request. The FakeCollector answers
  `ServerHello.app_id = V1Protocol.appIdOf(app.name)` (1..30000), so the `argos` tracestate entry
  and `caller_app_id` are told apart per app.
- Tests: `HttpClientStepsTest` (T-AG-056, ADR-0010 C-142, 10 U-10/U-11: exactly one HTTP step per
  call named `http://127.0.0.1:<port>`, the transaction's trace id in the `traceparent` ext-stub
  received, `ext_count` 1; `timeout=1` TIMEOUT, `refuse=1` CONNECT_FAIL on `http://127.0.0.1:1`,
  `status=500` HTTP_5XX, `redirect=1` one step with the final 200; `mode=async` for OkHttp
  `enqueue` and JDK 11 `sendAsync`), `PropagationHeadersTest` (T-AG-057, T-SEC-38: no `tracestate`
  by default and an incoming `argos` entry read into `caller_app_id` but not forwarded; the
  application's own `traceparent`/`tracestate` kept; inbound adoption; the `argos` entry first for
  `argos-state.hosts`; neither header for `exclude-hosts`. Spring `WebClient` applies its
  `ClientRequest` headers after the agent's, so for `webclient` the kept `traceparent` is not in the
  step detail and an application `tracestate` gets no `argos` entry: asserted as the documented gap
  of the httpclient-reactornetty README), `WebClientModesTest` (T-AG-055: `block`, `future`,
  `subscribe` one step each; the WebFlux fixture of the shop-boot3 jar
  (`io.argos.sample.boot3.http.webflux.WebFluxFixture`, started from the unpacked jar) gives no
  transaction and no propagation header; its metric batches come with WO-16) and `TwoHopTraceTest`
  (T-ACC-05 agent side, shop-boot3 only: A -> B 100 calls, same trace, B `parent_span_id` = A
  `span_id`, B `caller_app_id` = A's app id, A's HTTP step >= B's transaction 100/100; depth 2
  three-hop 10 calls).
- Evidence: the console of the results (`system-out`) has `[wo14-run]`, `[wo14-table]` and
  `[wo14-acc05]` lines; agent log and console of each run are kept as
  `build/reports/agent-e2e/wo14-<run>-*`.
- Times on this laptop (shared, reference only): about 2.5 minutes for shop-boot3@21 alone and
  2 minutes for shop-boot2@8 with shop-boot4@21, with the agent jar already built.

## Async suite (package `async`, WO-15, 10 §6.6, §8)

`io.argos.agent.testing.async.AsyncPropagationTest` runs each shop jar app of `-Pagent.e2e` with the
release agent jar over gRPC to a TLS FakeCollector (`AsyncRuns`: `async.step=true`,
`sampling.profile.normal-rate=1.0`), first on platform request threads, then (shop-boot3/4 on JDK
21+) with `SAMPLE_VT=true`. Each run starts once, its tests run in order, and it stops after its
T-AG-005 baseline check (`<app>-<jdk>.txt`, `<app>-<jdk>-vt.txt`). The requests are the WO-15
variants of `GET /api/async/executor` (`?work=sql`, `?work=detached`, sample-apps README).

```
./gradlew :agent:agent-testing:integrationTest --tests '*async*'                 # shop-boot3@21 (default)
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@25,shop-boot4@21 --tests '*async*'
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot3@21 --tests '*async*' --tests '*SampleAppAgentTest*' -Pagent.baseline.record=true
```

- Platform run: `work=sql` 20 times, every transaction has exactly one SQL step per async branch
  (`@Async`, `supplyAsync`, scheduled task, app pool, per-task virtual thread on JDK 21+), the
  branch statements, `sql_count` = `asyncSql` (T-AG-052g), each SQL step under an ASYNC step
  (T-AG-052h); `removed`, `cancelled`, `queueAfterCancel` unchanged (T-AG-051m); the
  `newVirtualThreadPerTaskExecutor` branch (T-AG-051n, JDK 21+); two `work=detached` rounds on the
  pool thread that ran request tasks: no step in any transaction and `sql_outside_tx_total` grows by
  exactly their statements (T-AG-052i, no leaked context); CPU and allocation present (T-ACC-23m
  contrast); a batch count for the virtual-thread run.
- `SAMPLE_VT=true` run: the same batch gives as many transactions (T-AG-054m); the async SQL steps
  again, `@Async` on the virtual-thread `SimpleAsyncTaskExecutor` (T-AG-052j); `cpu_time_ms` and
  `alloc_bytes` absent with `virtual_thread` and flag bit 1 (T-ACC-23n/T-AG-054n); request threads
  `tomcat-handler-<n>` normalized to `tomcat-handler-` (T-AG-054o, 10 U-16); 2,000 requests: THREAD
  dictionary at most 1,000 names, no `_other_`, overflow counter 0 (T-AG-054p); an active snapshot
  item of a running request with `virtual_thread` and `tomcat-handler-` (T-AG-054q). Thread dumps
  with virtual threads are WO-17.
- Recording: `SampleAppAgentTest` verifies the same `<app>-<jdk>.txt`; within one recording run the
  first recording of a file replaces it and later ones add their lines (`TransformBaseline.record`),
  so record both test classes together as above.
- Evidence: `[wo15-run]`, `[wo15-sql]`, `[wo15-leak]`, `[wo15-batch]`, `[wo15-vt]` and
  `[wo15-baseline]` lines in the results' `system-out`; agent log and console of each run as
  `build/reports/agent-e2e/wo15-<app>-jdk<N>-{platform,vt}-*`.

## Remote commands and remote configuration (packages `command`, `remoteconfig`, WO-17, 10 §12, 11 §8, 16 §5)

The release agent jar over gRPC to a TLS FakeCollector; the server side of the same round trips
(real bootJar, PostgreSQL, ClickHouse) is `ui/e2e/specs/agent-commands.spec.ts` (`pnpm -C ui
e2e:stack --grep "WO-17"`, needs a host JVM, skipped inside `e2e:docker`).

```
./gradlew :agent:agent-testing:integrationTest -Pagent.attach.jdks=8,11,17,21,25 \
  -Pagent.e2e=shop-boot2@8,shop-boot3@21,shop-boot3@25 \
  --tests 'io.argos.agent.testing.command.*' --tests 'io.argos.agent.testing.remoteconfig.*'
```

- `command` (shop-boot3 entries of `-Pagent.e2e`): `CommandRoundTripTest` (all five command types
  with their content types, multi-chunk results joined, COOLDOWN, unknown type 99 REJECTED, handler
  FAILED, TTL measured from receipt (`issued_at_ms` two minutes old with `ttl_s=1` still runs; the
  EXPIRED path itself is `CommandDispatcherTest` with an injected clock), CONFIG_RELOAD after a
  real yml edit with `CONFIG_FILE_RELOADED`); `EnvDumpSeedTest` (T-AG-072/T-SEC-20: `ARGOS_SEED`
  sentinel secrets in the environment, `-D` and `JAVA_TOOL_OPTIONS`, 0 occurrences in the result
  and the agent log); `ThreadDumpVirtualTest` (T-AG-054 dump item: `SAMPLE_VT=true` on each
  `shop-boot3@N` with N >= 21, a `virtual=true` thread with the running `tx_id`, THREAD_STACK of
  that `tx_id` matches; 10 U-2 on each `-Pagent.attach.jdks` JDK); `SelfDiagCommandE2eTest` (C-95:
  the SELF_DIAGNOSTICS command and the `-Dargos.test.selfdiag-file` snapshot agree on every shared
  value).
- `remoteconfig`: `RemoteConfigE2eTest` (T-AG-069/T-SEC-23 on every shop-boot2/shop-boot3 entry:
  `normal-rate=0` stops profiles, `ignore-paths` stops transactions, a widened allow list applies
  with source `remote`; not allowed, immutable, unknown, invalid and `reduce_masking` keys are
  rejected and SELF_DIAGNOSTICS still shows the local value and source; `InstanceUpdate.config_hash`;
  the WARN names keys only; push to result <= 10 s), `KillSwitchRemoteTest` (T-AG-043 on the first
  shop-boot3: push `enabled=false/true`, no transactions while off, heartbeats and metrics go on,
  `KILL_SWITCH_ON/OFF`; `ServerHello.config enabled=false` at start and across reconnects, C-231
  (4)), `RetransformLimitTest` (T-AG-046: more than 50 classes rejected `retransform_limit` without
  using the minute, 2 classes retransformed, a second change within a minute rejected),
  `CustomMethodPolicyE2eTest` (T-SEC-25: forbidden local items ignored with a WARN, a pushed
  `jakarta.` item `FORBIDDEN_PATTERN`, an item over 500 classes disabled).
- `transport.SessionTransportTest#remoteConfigAndCommandsOnTheSession` replaces the WO-11
  reject-all check; `jdbc.VirtualThreadDbTargetTest` (C-233 (6), `--tests
  '*VirtualThreadDbTargetTest'`, about 40 s) pins the rule that only a cold DBC step that ends
  before the data source's first SQL has no DB target, on virtual and platform request threads
  alike (the target becomes known at the first statement; a designed behaviour, C-143 (4)).
- Evidence: `[wo17-u2]`, `[wo17-vt]`, `[wo17-env]`, `[wo17-selfdiag]`, `[wo17-roundtrip]`,
  `[wo17-n25]`, `[wo17-retransform]`, `[wo17-killswitch]` and `[c233]` lines in the results'
  `system-out`; logs as `build/reports/agent-e2e/wo17-*` and `c233-<app>-jdk<N>-{vt,platform}-agent.log`.
  Timings on a shared machine are references only.

## Slow stacks, error stacks, `--check`, tags and premain time (WO-19, 10 §9.1, §13, §15)

The release agent jar; every class below is part of the default `integrationTest` (shop-boot3@21
and the attach JDKs), and each runs alone with its package filter.

```
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21 \
  --tests '*slowstack*' --tests '*errorstack*' --tests '*check*' --tests '*tags*'
./gradlew :agent:agent-testing:integrationTest --tests '*PremainTimeAttachTest*'
./gradlew :agent:agent-testing:test --tests '*ModuleReadmeLint*'           # unit test, part of check
```

- `slowstack.SlowStackE2eTest` (T-AG-064, F-A19; shop-boot3 entries on JDK 21+, platform and
  `SAMPLE_VT=true` runs): 15 concurrent `GET /api/slow?ms=5000` over gRPC; every transaction has
  STACK steps, at most 10 samples per one-second cycle, samples of one transaction at least
  `interval-ms` apart, at most 64 frames, and the capture cost in the self-diagnostics and the
  `argos_agent_stack_capture_seconds_total` metric. Unit tests: `core.watch.SlowStackWatcherTest`
  (T-AG-064a..e: one batched `getThreadInfo` per cycle, limits, cost, self-disable after 10 cycles
  over 50 ms), `SlowStackVirtualThreadTest` and `VirtualThreadStackCostTest` (`testJdk21`/`25`,
  `[wo19-u13]` reference cost), `OverheadMonitorTest` (T-AG-064f).
- `errorstack.ErrorStackE2eTest` (T-AG-059a..d proposed, F-A08; each shop-boot2/3 entry):
  `GET /api/error-500` gives one ERROR step of at most 30 frames and `error_top_frame_id` of the
  first `io.argos.sample` frame (line-free); a `sendError` 500 gives neither. Unit tests
  `core.trace.ErrorStepTest`, `TopFrameTest`, `core.command.AppPackagesPushTest`,
  `core.transport.ErrorFrameAssemblerTest`. The server side is `ui/e2e/specs/stats.spec.ts`
  T-E2E-errors-4 (`pnpm -C ui e2e:stack --grep "T-E2E-errors"`).
- `check.CheckCliTest` (T-AG-068, F-A27): `java -jar <release jar> --check` against a TLS
  FakeCollector: success exit 0 (one `-check` session and its `AgentBye`), refused token exit 1,
  untrusted CA exit 1, 2 s clock offset WARN exit 0, nothing listening exit 1, usage error exit 2,
  `--version`, `--classpath` with the shop-boot3 jar, `--json` (one object); no token in the
  output. The 8u242 case needs `-Pagent.jdk8u242=<JRE>` (else skipped). Unit test
  `core.check.CheckCommandTest` (T-AG-068a).
- `tags.AppTagsTest` (T-AG-066a..d, F-A21, F-A25; shop-boot3@21): the hello's `app_version` is
  the jar manifest's `0.0.0-SNAPSHOT`, `-Dargos.app.version` wins, `app.env` unset is `default`
  with one WARN, and every JSONL record carries `env` and `version`.
- `attach.PremainTimeAttachTest` (T-NFR-30, N-30; each attach JDK): exactly one start banner
  (`started in N ms`), N <= 500 (best of at most 3 runs on a shared machine), nothing else from
  the agent on stdout or stderr. The N-30 verdict belongs to the dedicated bench machine.
- `readme.ModuleReadmeLintTest` (unit test, T-BLD-017 proposed, O-Q05): every instrumentation
  module README (except `api`) has the "Version range and witness" table (minimum and maximum
  verified versions, existing boundary test classes), the module's `witness()` classes and
  existing `:agent:` tasks; each violation fixture in `src/test/resources/readme-fixtures` is
  caught.
- `agent-core` `integrationTest` `ContextStoreFootprintTest` (C-120 (3)): bytes per
  `ContextStore` entry (`[c120-contextstore]`).
- Evidence: `[wo19-slowstack]`, `[wo19-errorstack]`, `[wo19-check]`, `[wo19-tags]`,
  `[wo19-premain]` and `[wo19-readme]` lines in the results' `system-out`; logs as
  `build/reports/agent-e2e/wo19-*`. [matrix-parameters.md](matrix-parameters.md) maps the 18 §6.1
  matrix ranges (T-MX) to these switches and suites for the matrix runner (WO-50).

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
    11 §5 "재기동 연속성"; an `InstanceUpdate.instance_name` such as the connector's `host:port`
    renames the instance and keeps its id, 12 §4.3, so the next hello with that name gets the
    same id), a new UUID `session_id`,
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
- Server messages: `pushConfig(values[, revert])` and `command(type, args[, ttlS[, issuedAtMs]])`
  (default `ttl_s` 60) complete with the agent's `ConfigApplyResult` (`ConfigApplyOutcome`:
  applied keys, rejected key → `RejectCode`, `message(key)`, `effectiveConfigHash`, `durationMs`,
  `roundTripMs` measured by the collector) and joined `CommandResult` chunks (`CommandOutcome`:
  `ok()`, `chunks()`, `durationMs`, `roundTripMs`); `commandRaw(typeNumber, args)` sends a number
  outside the enum; `helloConfig(map)` replaces the `ServerHello.config` of the next sessions
  (reconnect with `faults().closeStream()`); `requestDictionary(kind, hashes...)` (11 §7 step 3, the
  answer lands in `dictionary()`); `sendThrottle(rate, backoff)` (11 §6). Agent events and config
  hashes: `agentEvents([type])`, `awaitAgentEvents(type, n, timeout)` (`AgentEventInfo`),
  `configHashes()`, `configHashUpdates()`, `awaitConfigHash(hash, timeout)`.
- Self-diagnostics over the command path (C-95): `SelfDiagnostics.fromCommand(collector[,
  timeout])` or `app.selfDiagnostics(collector)` sends SELF_DIAGNOSTICS and parses the result like
  the snapshot file; members the file lacks read as `diagnostics.member("effective_config.<key>.source")`; a
  non-OK answer (COOLDOWN) is not-available with the status as its reason.
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
  Other` <= 16 MB, threads <= 6) are the WAR and native parts, unit-tested (`LeakProbeTest`, the
  HTTP manager against a loopback server in `TomcatManagerHttpTest`); the 50 redeploy cycles run
  against a real Tomcat manager in the matrix (`matrix.WarRedeployTest`, below), while the
  ThreadLocal leak warnings are judged on every WAR run of the launcher (`TomcatLogCheck`,
  `WarAppAgentTest`). NMT stays with the soak run (WO-52).

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

## Compatibility matrix tests (`matrixTest`, package `matrix`, WO-50, 18 §6)

`src/matrixTest/java/io/argos/agent/testing/matrix` holds the tests that only the compatibility
matrix runs; a matrix row (`test/matrix/matrix.yaml`, `./gradlew matrixTest -Pmatrix.combo=<ID>`,
`test/matrix/README.md`) runs them together with the integration suites of its row. They use the
public harness API only and read the row from system properties that the row's Gradle task sets:

| Property | Meaning |
|---|---|
| `argos.matrix.combo` | row ID; every test name starts with it (`T-MX-021 T-AG-054 ...`, test/README.md) |
| `argos.test.e2e` | the row's `<app>@<jdk>` (first jar app for the jar tests, first WAR app for the WAR tests); with `argos.test.sampleApp.<app>`, `argos.test.java<N>`, `argos.test.releaseAgentJar` as for `integrationTest` |
| `argos.test.matrix.vt` | `true` on a virtual-thread row (`SAMPLE_VT=true`) |
| `argos.test.matrix.coexist` | `jacoco-before`, `jacoco-after`, `otel-before`, `otel-after` or `devtools` |
| `argos.test.matrix.agentsDir` | folder of `jacocoagent-*.jar` and `opentelemetry-javaagent-*.jar` (`test/matrix/fetch-coexist-agents.sh`; default `build/matrix-agents`) |
| `argos.test.sampleApp.shop-boot3-devtools` | shop-boot3 built with `spring-boot-devtools` (devtools row) |
| `argos.test.matrix.resultDir` | the tests write `<test>.properties` there (`smoke`, `redeploy`, `jsp-forward`, `coexist-<kind>`, `names-static`, `names-error`, `tls13`: startup ms, transformed classes, transactions, ...) for the row's result JSON; the same values are one `[mx-<test>]` console line |
| `argos.test.matrix.appEnv`, `argos.test.matrix.tomcat` | applied by `AppLauncher` itself (above) |

| Class | Tags | What it proves |
|---|---|---|
| `MatrixSmokeTest` | F-A01, F-A05, F-A07, F-A23, F-A30, F-A31, N-04, N-27 | the 18 §6.1 smoke sequence on one run of a jar app with a TLS `FakeCollector`, as ordered dynamic tests: startup ms; 25 catalog endpoints (`ShopCatalog`) x 20 = one transaction each, no `/error` service, SQL steps 1:1 (N+1 = 21 executions); three outgoing client kinds with the trace id at ext-stub; four async kinds (`/api/async/executor?work=sql`, one SQL step per branch); 100 excluded noise requests (exclusion counter +100 by reason); T-AG-005 against `<app>-<jdk>[-<container>][-vt]-smoke.txt` (`-jetty`, `-undertow` for a `SAMPLE_CONTAINER` row), log allow-list, quiet console, D-23 missing values; T-AG-070 (no `IllegalAccessError` after HttpURLConnection, ThreadPoolExecutor, CompletableFuture); on a VT row T-AG-054 (all on virtual threads, same count, THREAD dictionary <= 1,000); `THREAD_DUMP` with the blocked request (virtual on VT rows, `dumpThreads` on JDK 21+); stop and `AgentBye` (skipped with the reason on Windows, which ends processes without shutdown hooks) |
| `WarRedeployTest` | F-A15, F-A36, N-02 | T-AG-053 / proposed T-AG-053w: 50 undeploy/deploy cycles through the real Tomcat manager, the redeployed context still measured, `ParallelWebappClassLoader` count after `GC.run` x2 = before the cycles, Tomcat's log names no agent and adds no SEVERE or ThreadLocal leak warning beyond the same cycles without the agent (C-162 (1)) |
| `JspForwardTest` | D-04, F-A28, F-A36 | proposed T-AG-012j (C-162 (2)): `GET <context>/api/jsp-forward` forwards to a JSP that the staged Tomcat's Jasper compiles with the distribution's `lib/ecj-*.jar`; one transaction per request named after the request, `dispatch_count` 2, the compiled `forward_jsp.class` in Tomcat's work directory; skipped with the reason when the distribution has no JDT compiler |
| `CoexistenceTest` | F-A32, F-A31, F-A01 | T-MX-110~113 (proposed T-MX-110a~e): Argos alone and Argos with JaCoCo / OpenTelemetry before or after it, or devtools; same requests, same transaction count, coexistence baseline `<app>-<jdk>-coexist-<kind>.txt`, no agent ERROR, nothing from Argos on the console, `traceparent` at ext-stub: OpenTelemetry's own, recorded unchanged in the Argos HTTP step detail (F-A32), else the Argos trace id; skipped with the reason without the agent jars |
| `ServiceNameBoundaryTest` | D-04, F-A29, F-A01 | C-164 with Spring Boot's default `add-mappings=true`: 404s of the `/**` resource handler are one service `GET /**` (proposed T-AG-010s), a client's own `GET /error` is named `GET /error` (proposed T-AG-010e) |
| `Tls13Jdk8Test` | SEC-07, DSN-10-11 | proposed T-SEC-08d (C-250 (8)): the trivial app on the toolchain JDK 8 (8u261+, else skipped with the update) negotiates TLSv1.3 with a TLS `FakeCollector`, batches delivered, no ERROR or WARN |

Transform baselines of the matrix are files of their own next to the integration ones, so that
re-recording one suite never drops another's classes: `<app>-<jdk>[-<container>][-vt]-smoke.txt` and
`<app>-<jdk>-coexist-<kind>.txt`. A row whose baseline is not recorded yet skips that check with
the reason (the table shows the row as not verified, never as passed); record it on the matrix
runner with `-Pagent.baseline.record=true` and review the file like code.

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
| `collector.FakeCollectorTest` | DSN-18-04: recording and acks, `ServerHello` contents and instance reuse, `latency`, `failNext` (retry-after trailer), `hang`, `rejectAuth`, `requireToken` (masked tokens), session round trips (config, command, dictionary request, throttle), `closeStream`, `serverRestart` (`SESSION_REQUIRED`; instance id and dedup window kept, or a new id with `serverRestart(id)`), `InstanceUpdate.instance_name` keeps the instance id under the new name (WO-13), `sessionClose` (per reason) and the revoked token (`TOKEN_REVOKED` on data calls and hellos, `clear()` lifts it), `plaintextRejected`, `rejectHello` and the §5 check order, `clockOffset` (T-ACC-02 collector side), `partialDrop`, `walFull`, `pauseSessionReads`, `throttle`, duplicates, concurrency high-water mark, `AgentBye` and flush, `REPLACED`, large batch, gzip, TLS modes |
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
