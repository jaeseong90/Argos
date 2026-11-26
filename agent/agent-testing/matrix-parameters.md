# Matrix parameters for the compatibility runner

Hand-over sheet for the compatibility matrix runner (WO-50, [18 §6](../../docs/18-test-design.md)):
for every `T-MX` range of 18 §6.1, how the agent harness of this project runs that combination,
which switches it needs, where its results land, what it costs on the development laptop and what
was missing before the runner existed. The ID numbering inside each range is matrix.yaml's.

**Runner (WO-50).** [`test/matrix/matrix.yaml`](../../test/matrix/matrix.yaml) now defines every
combination as a row with exactly these switches (`app`, `jdk`, `props`, `app-env`, `tomcat`, `vt`,
`coexist`) and the suites below; each row is the task `matrixTest-<ID>` of this project, wired by
the same `wireHarness` function as `integrationTest`, with the result file `build/matrix/<ID>.json`
and the page `docs/compatibility-matrix.md` (`matrixReport`). Run one with
`./gradlew matrixTest -Pmatrix.combo=<ID>`; format, verdicts and runner preparation are in
[test/matrix/README.md](../../test/matrix/README.md). The matrix-only tests of `src/matrixTest`
(package `io.argos.agent.testing.matrix`) close the gaps the "Missing" column lists: the 18 §6.1
smoke sequence (`MatrixSmokeTest`), real Tomcat manager redeploys (`WarRedeployTest`), the Tomcat
choice (`argos.test.matrix.tomcat`), coexistence (`CoexistenceTest`), JSP forward, service-name
boundaries and JDK 8 TLS 1.3. The columns below keep the state at hand-over.

All commands run from the repository root with JDK 21 as `JAVA_HOME`; Gradle downloads the other
JDKs (toolchains). Heavy runs take the shared lock: `bash test/lock/argos-lock.sh run agent-it
--purpose "<purpose>" -- ./gradlew ...`.

## Harness switches

| Switch | Values | Effect |
|---|---|---|
| `-Pagent.e2e=<app>@<jdk>,...` | apps `shop-boot2`, `shop-boot3`, `shop-boot4`, `shop-heavy`, `shop-batch`, `shop-war-javax`, `shop-war-jakarta`; any JDK the toolchains provide; `none` | The sample-app combinations of every end-to-end suite (default `shop-boot3@21`). WAR apps run in a staged standalone Tomcat on `127.0.0.1` |
| `-Pagent.attach.jdks=8,11,17,21,25` | JDK list | The attach and transport tests on the trivial app (default `8,21`) |
| `-Pagent.gc.jdks=...` | JDK list | `metric.GcLabelsTest` (default: the attach JDKs) |
| `-Pagent.container=true` | | Container mode tests (Docker, `eclipse-temurin` pinned by digest; `-Pagent.container.image.<jdk>=<image@sha256:...>` replaces one) |
| `-Pagent.jdbc.containers=true` or `=postgres,mysql,...` | `mysql`, `mariadb`, `postgres`, `mssql` | Database containers of the JDBC suite, one at a time, random password (ADR-0010 C-149 (2)) |
| `-Pagent.jdbc.oracle=true` | | `gvenzl/oracle-free` (4 GiB, C-149 (3)) |
| `ARGOS_TEST_<DB>_URL`, `_USER`, `_PASSWORD` | `ORACLE`, `TIBERO`, `ALTIBASE`, `CUBRID`, `DB2` | In-house databases (trusted hosts only); unset means skipped with the reason |
| `-Pagent.baseline.record=true` | | Records the transform baselines (`baselines/transformed-classes/<app>-<jdk>[-vt].txt`) and premain class budgets instead of comparing |
| `SAMPLE_DB` | `h2`, `postgres`, `mysql`; `mariadb`, `mssql`, `oracle` in shop-boot3 | Set by `jdbc.JdbcRuns` per database; the other suites run on H2 |
| `SAMPLE_POOL` | `hikari`, `tomcat`, `dbcp2` | Set by `jdbc.JdbcRuns` profiles `POOL_TOMCAT`, `POOL_DBCP2` (`jdbc.PoolRegistryE2eTest`) |
| `SAMPLE_ROUTING_DS`, `SAMPLE_DRIVER_WRAPPER` | `true` | Set together by profile `NESTED` (`jdbc.JdbcStepsTest`, T-AG-031, five JDBC levels); `SAMPLE_DRIVER_WRAPPER` alone by `custom.CustomMethodStepsTest` |
| `SAMPLE_SECOND_POOL` | `true` | Profiles `SECOND_POOL`, `SECOND_POOL_TOMCAT` (`jdbc.PoolRegistryE2eTest`, T-AG-062g) |
| `SAMPLE_VT` | `true` | Not a Gradle switch: the suites add a virtual-thread run themselves for Boot 3/4 apps on JDK 21+ (`async.AsyncPropagationTest`, `command.ThreadDumpVirtualTest`, `batch.BatchTransactionsTest`, `slowstack.SlowStackE2eTest`) |

Results: JUnit XML under `agent/agent-testing/build/test-results/integrationTest/`, result lines
(`[wo12-...]` to `[wo19-...]`) in the XML `system-out`, per-run logs under
`agent/agent-testing/build/reports/agent-e2e/` (`<app>-jdk<N>-transform.log`, `wo<NN>-<app>-jdk<N>-...`
agent, stdout and Tomcat logs). Durations below are the ones the work-order runs reported on the
development laptop (Ryzen 5 5600H, 15 GiB, shared): INVALID reference values, not re-measured for
this sheet.

## Combinations (18 §6.1)

| T-MX range | Axis | Run today | Results | Laptop duration | Missing for the runner |
|---|---|---|---|---|---|
| T-MX-001~012 | shop-boot2 x JDK 8, 11, 17, 21 (25: unverified, run only) | `-Pagent.e2e=shop-boot2@8,shop-boot2@11,shop-boot2@17,shop-boot2@21[,shop-boot2@25]` with the default suites (`e2e.SampleAppAgentTest`, servlet, jdbc on H2, httpclient, async, metric, logging) | `[wo12-...]`, `[wo13-table]`, `[wo14-table]`, `[wo15-...]`, `[wo16-...]`; baselines `shop-boot2-<jdk>.txt` (8, 11, 17, 21 recorded) | servlet suite about 30 min per default run (4 app starts per app); WO-14 boot2@8 + boot4@21 about 2 min | No baseline for JDK 25; the 18 §6.1 smoke sequence as one per-combination run (catalog 25 endpoints x 20, exclusion noise 100, thread dump, AgentBye) does not exist: its steps are spread over the suites listed in the next table |
| T-MX-020~031 | shop-boot3 x JDK 17, 21, 25 x VT off/on (JDK 25 with `--sun-misc-unsafe-memory-access=deny`, a harness preset) | `-Pagent.e2e=shop-boot3@17,shop-boot3@21,shop-boot3@25`; VT runs come from the async, thread dump, batch and slow-stack suites on 21 and 25 | baselines `shop-boot3-<jdk>[-vt].txt` (17, 21, 21-vt, 25, 25-vt) | async suite shop-boot3@21 about 1.5 min; WO-17 command/remote-config suites about 8.5 min for 8/21 + shop-boot2@8, shop-boot3@21, shop-boot3@25 | `e2e.SampleAppAgentTest` has no `SAMPLE_VT` run of its own: the VT axis exists per suite, not as a whole combination |
| T-MX-040~051 | shop-boot4 x JDK 17, 21, 25 x VT off/on | `-Pagent.e2e=shop-boot4@17,shop-boot4@21,shop-boot4@25` | baselines `shop-boot4-21[-vt].txt` only | async 8 combinations (boot2/3/4) about 4 min | Baselines for JDK 17 and 25 not recorded (`-Pagent.baseline.record=true` first) |
| T-MX-060~063 | shop-heavy x JDK 17, 21, 25 (startup time, N-27) | `-Pagent.e2e=shop-heavy@21` is accepted by the launcher; startup time through `test/bench/bench-run.sh --startup` (bench, not the matrix) | none yet | not run | No transform baseline `shop-heavy-<jdk>.txt` (record first); no suite records N-04/N-27 startup per combination (`RunningApp.startupTime()` exists) |
| T-MX-070~075 | shop-batch x JDK 17, 21, 25 (kind=BATCH counted apart) | `-Pagent.e2e=shop-batch@17,shop-batch@21,shop-batch@25 --tests '*batch*'` (`batch.BatchTransactionsTest`, `BatchStatsTest`, `BatchSeedLogTest`; VT run on 21+) | `[wo18-batch]`, `[wo18-u19]`, `[wo18-seedlog]`; logs `wo18-*` | not reported separately (part of the WO-18 run) | shop-batch is not in the default `-Pagent.e2e`: the batch suite skips with the reason unless it is listed |
| T-MX-080~087 | shop-war-javax x Tomcat 9 x JDK 8, 11, 17, 21, with 50 redeploys | `-Pagent.e2e=shop-war-javax@8,shop-war-javax@11,shop-war-javax@17,shop-war-javax@21 --tests '*War*'` (standalone Tomcat 9.0.123, `e2e.WarAppAgentTest`, `servlet.War*Test`, `TomcatLogCheck` against a no-agent run) | `wo12-<app>-jdk<N>-...-tomcat.log`, no-agent baseline `wo12-<app>-jdk<N>-war-noagent-tomcat.log` | about 30-50 s per combination for `WarAppAgentTest`; `shop-war-javax@8` + `shop-war-jakarta@21` with `--tests '*War*'` about 10 min | **50 redeploys**: `leak.TomcatRedeployDriver` exists but the staged Tomcat has no manager app, so no redeploy runs against a real Tomcat |
| T-MX-090~095 | shop-war-jakarta x Tomcat 10.1, 11 x JDK 17, 21, 25 (two web applications, context prefix) | `-Pagent.e2e=shop-war-jakarta@17,shop-war-jakarta@21,shop-war-jakarta@25 --tests '*War*'` (contexts `/shop-a`, `/shop-b`, `servlet.WarContextPrefixTest`) | as above | as above | **Tomcat choice**: `-Pagent.e2e` has no Tomcat axis; `SampleApp.defaultTomcat(jdk)` picks Tomcat 10.1 (10.1.61) on JDK 17 and Tomcat 11 (11.0.27) on 21 and 25, so 10.1 x 21/25 and 11 x 17 are not reachable (`AppLauncher.tomcat(key)` exists in code; the runner needs a combination parameter for it) |
| T-MX-100~108 | JDBC: 9 databases x shop-boot3 on JDK 21; HikariCP on all, Tomcat JDBC and DBCP2 on PostgreSQL only | `-Pagent.e2e=shop-boot3@21 --tests '*jdbc*' -Pagent.jdbc.containers=true -Pagent.jdbc.oracle=true` (`jdbc.JdbcStepsTest` per database with HikariCP; `jdbc.PoolRegistryE2eTest` runs Tomcat JDBC and DBCP2 on PostgreSQL when its container is enabled, else on H2); in-house Oracle 19c, Tibero, Altibase, CUBRID, DB2 through `ARGOS_TEST_<DB>_URL` (`jdbc.VendorDatabaseTest`) | `[wo13-table]`, `[wo13-db]`, `[wo13-sql-ids]`; logs `wo13-*` | H2 about 6 min; containers + Oracle about 10 min (one container at a time, at most 4 GiB each, Oracle about 3.1 GiB) | Tibero, Altibase, CUBRID, DB2: vendor driver jars and a `SAMPLE_DB` value with its schema are not in the sample apps (skip with the reason even when the URL is set); these and in-house Oracle 19c are human checks on trusted hosts |
| T-MX-110~113 | Coexistence: JaCoCo agent before/after, OpenTelemetry javaagent before/after, Spring Boot devtools | not possible today | | | **Gap**: no harness option to add another `-javaagent` in a chosen order to a sample app (only the test-only `src/preloadAgent` of the attach tests), no JaCoCo or OpenTelemetry agent in the build (JaCoCo is EPL: a dev-only entry in `docs/licenses/dev-only.md` would be needed), no devtools variant of a sample app; the `traceparent` preservation check needs a second hop |
| T-MX-120~121 | Routing DataSource and nested wrappers (boot2, boot3), D-24 | `-Pagent.e2e=shop-boot2@8,shop-boot3@21 --tests '*jdbc*'` (profile `NESTED`: `SAMPLE_ROUTING_DS=true` + `SAMPLE_DRIVER_WRAPPER=true`, T-AG-031 on H2, and on PostgreSQL with `-Pagent.jdbc.containers=postgres`) | `[wo13-table]` | part of the JDBC run above | none for the two combinations |

## The 18 §6.1 smoke sequence, step by step

At hand-over the per-combination sequence did not exist; WO-50 runs it as
`matrix.MatrixSmokeTest` in every Boot, heavy and container row of matrix.yaml. Each step also
has a suite of its own, which the rows run next to it:

| Step | Covered today by | Note |
|---|---|---|
| Startup, N-04 recorded | `RunningApp.startupTime()` (C-49), `launcher.ContainerModeTest` prints it | Not recorded per combination |
| Catalog endpoints x 20 | `servlet.RequestBoundaryTest`, `servlet.FallbackNameTest`, `e2e.SampleAppAgentTest` (catalog entries per app) | Not the fixed 25 x 20 loop |
| Outgoing calls (3 kinds) | `httpclient.HttpClientStepsTest`, `httpclient.WebClientModesTest`, `httpclient.TwoHopTraceTest` | Clients per app as `/api/runtime` reports them |
| Async (4 kinds) | `async.AsyncPropagationTest` | `@Async`, `supplyAsync`, scheduled pool, application pool, VT executor |
| Exclusion noise 100 | `servlet.IgnoreRulesTest` | |
| Assertions: transaction count, no `/error` service, SQL step 1:1, exclusion counter, transform baseline, log allow-list, missing-value rule | `servlet.*`, `jdbc.JdbcStepsTest`, `e2e.SampleAppAgentTest` (baseline T-AG-005, log allow-list T-AG-060), `ValueAssert` (missing vs zero) | |
| Thread dump command (VT included) | `command.ThreadDumpVirtualTest` | shop-boot3 on 21+ with `SAMPLE_VT` |
| Shutdown, AgentBye received | `transport.ShutdownTransportTest` (attach JDKs, trivial app, T-AG-067) | Not per sample app |

## Rows beyond 18 §6.1 (WO-50, proposed IDs)

| T-MX range | Axis | Switches in matrix.yaml |
|---|---|---|
| T-MX-114 | Coexistence with Spring Boot devtools | `coexist: devtools` (shop-boot3-devtools.jar) |
| T-MX-130~139 | Jetty 9.4 (servlet 3.1), Undertow 2.2 and 2.3, Jetty 12 and 12.1 (C-127) | `app-env: [SAMPLE_CONTAINER=jetty\|undertow]`; Undertow rows `status: unverified` |
| T-MX-140~149 | Old JREs 8u242 and 8u252, JDK 8 TLS 1.3, container mode, Linux transport variants, cgroup v2 | `app: trivial` with `requires: [prop:agent.jdk8u242]` and similar, `props: [agent.container=true]`, `requires: [os:linux]` |
| T-MX-150~159 | httpclient-jdk11 on JDK 17 and 25, custom methods on WAR and JDK 11, 17 (C-240 (10)) | the row's app and JDK with the one suite |

## Not in this sheet

- The nightly workflow `.github/workflows/nightly.yml` and the runner label `argos-matrix` (the
  `matrixTest` task skips without it or without `-Pmatrix.combo`/`-Pmatrix.group`): see
  test/matrix/README.md.
- Real cgroup v1 hosts: a human check outside the matrix.
