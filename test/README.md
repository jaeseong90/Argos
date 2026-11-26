# Tests: layers, IDs and traceability

How Argos is tested: where each suite lives, how to run it, which Gradle task runs it, how tests are
named and tagged, and how the requirement-to-test report is produced. The rules come from
[docs/18 test design](../docs/18-test-design.md) §2 (layers, DSN-18-02), §3 (IDs, DSN-18-01), §15
(data, flaky tests, artifacts) and §16 (requirement to test table), and from
[docs/19](../docs/19-repo-build-release-design.md) §3.6 (task separation, DSN-19-13). The build
enforces what it can (section 3); everything else here is a review rule.

## 1. Where the tests live

| Location | Contents |
|---|---|
| `<module>/src/test` | Unit tests of every Java module (agent modules run them on JDK 8) |
| `<module>/src/archTest` | ArchUnit rules; the agent rules over all agent modules live in `agent/agent-testing/src/archTest` |
| `<module>/src/integrationTest` | Integration tests: Testcontainers, fake collector, sample apps, real processes |
| `server/src/contractTest` | OpenAPI lint and diff, permission matrix, schema and config-key snapshots (from WO-20/WO-23). No Docker: the permission matrix (WO-24) runs on the real web layer (Tomcat, Spring Security, every controller) with stubbed services; its run on real login sessions and databases is in `server/src/securityTest` |
| `server/src/testFixtures` | `ServerTestEnvironment` (WO-20): PostgreSQL 16 and ClickHouse 26.8 in Testcontainers (digest-pinned images of `deploy/*/image.properties`), roles from `deploy/postgres/roles.sql`, generated passwords in owner-only files, Spring properties with `*password-file` keys only; used by the server `integrationTest` and `securityTest`, reused by WO-21, WO-23 and WO-24. The server test JVMs use `server/build/tmp/<task>` as `java.io.tmpdir`, so nothing they create lands in the system temp directory that the agent harness watches during attach runs |
| `<module>/src/securityTest` | SEC suite (server, agent-testing); UI security checks are Playwright tests tagged `@security` |
| `agent/agent-testing/src/matrixTest` | Compatibility matrix (sample apps x JDKs, 18 §6) |
| `agent/agent-testing/src/main` | The agent test harness (AppLauncher, FakeCollector, assertions, baselines; see its README) |
| `agent/agent-dist` | `verifyAgentJar`: shading, relocated strings, major version 52, module index (T-BLD-001..006) |
| `test/bench` | Overhead bench harness, `benchTest` (18 §7) |
| `test/load/fake-agents` | Server load generator and runner, `serverLoadTest` (18 §8, WO-29): fake agents, N-06/N-18/D-13 verdicts, `docs/bench/server-load.md` |
| `test/storage/*` | Storage PoC: schema tests, generator (`storageGenerate`, `storageBench`), queries Q01..Q20 (18 §9) |
| `test/soak` | 72-hour soak, `soakTest` (18 §11.2, WO-52): shop-boot3 and shop-batch with the agent against the real server, heap slope, NMT, threads, FD, WAL, parts, consistency, OOM 0; `docs/bench/soak-<date>.md` |
| `test/chaos` | Chaos scenarios, `chaosTest` (18 §11.1, WO-52): T-CH-001..009 with toxiproxy, server restarts, `docker pause` and the server hook `argos.testing.ingest-fault-file`; `docs/bench/chaos-<date>.md` |
| `test/agent` | `check-shading.sh`, the Unsafe allow-list, the WO-02 transport PoC (the WO-02 overhead harness moved to `test/bench`) |
| `test/lint` | `check-spdx.sh` and its exclusions (T-BLD-007) |
| `test/lock` | `argos-lock.sh`, the working-tree locks `.gradle/argos-locks/<name>` (ADR-0010 C-235 (8), section 2), and its tests `argos-lock.test.sh` |
| `test/seed` | PII and XSS seed traffic with the sentinel `ARGOS_SEED` (18 §4.3) |
| `sample-apps/` | The only agent integration fixtures (DSN-18-03): shop apps, ext-stub, load generator |
| `ui/src/**/*.test.ts(x)` | UI unit and component tests (Vitest) |
| `ui/e2e`, `ui/perf` | Playwright E2E and the XLog frame-time spec |
| `build-logic/src/test` | Tests of the build logic itself (TestKit), run by the root `check` |

## 2. Test layers and Gradle tasks

| Task | Where | In `check` | Runs on | Command |
|---|---|---|---|---|
| `test` | `src/test` of every Java module | yes | every PR (GitHub-hosted) | `./gradlew test`, `./gradlew :agent:agent-core:test` |
| `archTest` | `src/archTest` of every Java module | yes | every PR | `./gradlew archTest` |
| `contractTest` | `server/src/contractTest` | yes | every PR | `./gradlew :server:contractTest` |
| `verifyAgentJar` | `agent/agent-dist` | yes (and after `agentJar`) | every PR | `./gradlew :agent:agent-dist:check verifyAgentJar` |
| `integrationTest` | `src/integrationTest` | **no** | every PR, CI job `integration` | `./gradlew integrationTest -Pqueries.profile=ci` |
| `securityTest` | `src/securityTest` | **no** | every PR (light), nightly (full) | `./gradlew securityTest` |
| `matrixTest` | `agent/agent-testing/src/matrixTest` + the `src/integrationTest` suites; rows in `test/matrix/matrix.yaml` | **no** | nightly on self-hosted `argos-matrix` (`.github/workflows/nightly.yml`, one job per group); one combination or group by hand | `./gradlew matrixTest -Pmatrix.combo=<id>` or `-Pmatrix.group=<group>` (one task `:agent:agent-testing:matrixTest-<ID>` per row, result `build/matrix/<ID>.json`; test/matrix/README.md) |
| `matrixReport` | `agent/agent-testing` (build-logic `MatrixReport`) | **no** | nightly `report` job after the matrix | `./gradlew :agent:agent-testing:matrixReport [-Pmatrix.results=<dir>]` → `docs/compatibility-matrix.md` (○ verified, ✗ failed, △ not verified, ◎ community verified) |
| `benchTest` | `test/bench` | **no** | weekly on self-hosted `argos-bench-app` + `argos-bench-load` (`.github/workflows/bench.yml`: schedule and `workflow_dispatch` only, sets main and startup, report pull request `docs/bench/<date>-<commit7>.md`, WO-51); release candidates add container, vt, worst, otel, grpc | `./gradlew :test:bench:benchTest -Pbench.machine-profile=<id>` (test/bench/README.md) |
| `serverLoadTest` | `test/load/fake-agents` | **no** | `argos-storage` (13 §7.1), manual; a shared machine runs `smoke`/`mini` (verdicts INVALID) | `./gradlew serverLoadTest -Pbench.machine-profile=<id> [-Pserver.load.profile=full\|smoke\|mini] [-Pserver.load.publish=true]` (WO-29, README of the project) |
| `storageBench` | `test/storage/generator` | **no** | `argos-storage`, manual | `./gradlew :test:storage:generator:storageBench -Pbench.machine-profile=<id> ...` |
| `soakTest` | `test/soak` | **no** | release gates, self-hosted `argos-bench-app` (`.github/workflows/soak.yml`, `workflow_dispatch` only); a shared machine runs a short `-Psoak.duration=15m` (verdicts INVALID) | `./gradlew soakTest -Pbench.machine-profile=<id> [-Psoak.duration=72h] [-Psoak.tps=500] [-Psoak.vt=true] [-Psoak.publish=true]` (WO-52, test/soak/README.md) |
| `chaosTest` | `test/chaos` | **no** | release gates, self-hosted `argos-matrix` (`.github/workflows/chaos.yml`: weekly, `workflow_dispatch`, `release/**` push); a shared machine runs `-Pchaos.profile=short` (timing verdicts INVALID) | `./gradlew chaosTest -Pbench.machine-profile=<id> [-Pchaos.profile=short\|full] [-Pchaos.scenarios=T-CH-001,...] [-Pchaos.publish=true]` (WO-52, test/chaos/README.md) |
| `traceabilityReport` | root project | no | any machine, seconds | `./gradlew traceabilityReport` (section 6) |
| `pnpm test`, `pnpm e2e` | `ui/` | `:ui:test` yes, e2e no | every PR | `pnpm -C ui test`, `pnpm -C ui e2e` |

`./gradlew check` runs `test`, `archTest`, `contractTest` and `verifyAgentJar` (plus static checks:
Spotless, Error Prone, license allow-list, SPDX, unit-test layer rules, UI test, lint and typecheck).
It never runs `integrationTest`, `securityTest`, `matrixTest`, the performance and long-run tasks
or the development tools (`sampleSmoke*`, `sampleImage`, `storageGenerate`, `storageQueries`,
`transportPoc*`). CI runs `integrationTest` and `securityTest` as separate parallel jobs
(`.github/workflows/pr.yml`). Skip the UI with `-Pargos.ui.skip=true` for quick Java-only runs.

Development tools that are not test layers: `./gradlew :sample-apps:<app>:sampleSmoke` (sample app
without the agent), `./gradlew :test:agent:transport-poc:transportPoc*` (WO-02 P-3),
`./gradlew :test:storage:queries:storageQueries` (Q01..Q20 runner).

### Shared working tree: locks

Several engineers and agents may share one working tree. They serialise conflicting runs with
directory locks under `.gradle/argos-locks/<name>` (ADR-0010 C-235 (8)):

| Lock | Runs that take it |
|---|---|
| `server-it` | Everything that runs the real server or rebuilds its boot jar: `:server:integrationTest`, `:server:securityTest`, `:server:bootJar`, `generateOpenApi`, `jibDockerBuild`, `:deploy:composeSmoke`, `pnpm -C ui e2e:stack`, `e2e:docker`, and `perf` in route mode |
| `agent-it` | Agent integration runs |
| `quiet` | Measurements |
| `deps` | Dependency lock edits |

The build logic takes no lock. The caller wraps the Gradle command:

```
bash test/lock/argos-lock.sh run server-it --purpose ":server:integrationTest" -- ./gradlew :server:integrationTest
bash test/lock/argos-lock.sh status server-it     # exit 0 free, 1 held, 3 held by a dead owner
```

`compose-smoke.sh` and the E2E stack (`ui/e2e/stack/lock.ts`) take `server-it` themselves. Inside an
enclosing `run` of the same lock, they use the held lock (`ARGOS_LOCK_TOKEN_SERVER_IT`).

- **Lock and owner file**: `mkdir` creates the lock directory atomically. The holder then writes
  `owner.json` into it, one key per line: `version`, `name`, `token`, `pid`, `pid_kind`, `host`,
  `started_at` (ISO-8601 UTC) and `purpose`.
- **PIDs**: `pid` is the process that holds the lock (for `run`, the helper itself). On Windows it is
  the Windows PID (`pid_kind` `windows`): Git Bash takes it from `/proc/<pid>/winpid`, Node uses
  `process.pid`. Elsewhere it is the POSIX PID. Liveness is checked with `tasklist` (Windows) or
  `kill -0` (POSIX) in bash, and with `process.kill(pid, 0)` in Node.
- **Stale locks**: a lock is stale only when its owner file names this host, this host's PID kind,
  and a PID that is not alive. Only a stale lock is removed automatically (renamed aside first,
  then deleted if the token still matches). A lock without `owner.json`, from another host, or with
  an owner of unknown liveness is never removed. The waiter waits up to `--wait-seconds`
  (`ARGOS_LOCK_WAIT_SECONDS`, default 1800 s), then fails with the owner and the `rm -rf` to run by
  hand once you have checked that the owner is gone.
- **Release**: release deletes only a lock whose owner file has the caller's token. `run` releases
  when the command ends, also on failure, Ctrl-C or TERM. `acquire` and `release --token` exist for
  long-lived scripts. An AI agent's tool call ends its shell right away, so agents use `run`.
- **Tests**: `bash test/lock/argos-lock.test.sh` (also run by `.github/scripts/test/run-all.sh`) and
  `ui/e2e/stack/lock.test.ts` (Vitest).

## 3. Rules the build enforces

| Rule | Where | What happens |
|---|---|---|
| Composition of `check` (18 §2.2) | `argos.test-layers` (root), task-graph guard | Every build whose graph contains `check` (dry runs included) fails before any task runs if a `check` depends on a task that never belongs in it, if a project's `check` does not run its `test`, `archTest`, `contractTest` or `verifyAgentJar`, or, on the full `./gradlew check`, if one of the anchors `:agent:agent-core:test`, `:agent:agent-testing:archTest`, `:server:contractTest`, `:agent:agent-dist:verifyAgentJar` no longer exists. Tasks excluded with `-x <name>` or `-x <path>` are not required (abbreviated `-x` names are not recognised). Rules: `build-logic/.../testing/TestLayers.kt`; tests T-BLD-013 |
| Machine profile (18 §2.2) | `argos.test-layers`, and each task again when it runs | `benchTest`, `serverLoadTest`, `storageBench`, `soakTest` and `chaosTest` fail fast without `-Pbench.machine-profile=<id>` (letters, digits, `.`, `_`, `-`). The id names the measurement machine in the report (for example `argos-bench-app`, `argos-storage`) |
| Soak and chaos verdicts (18 §11) | `test/soak`, `test/chaos` | Invariants (OOM 0, consistency, ClickHouse settings; resumed collection, agent ERROR 0, drop accounting D-13, duplicates 0) are always PASS/FAIL. Timing and resource rows are PASS/FAIL only on the dedicated runner with the full profile (`argos-bench-app*` for 72 hours at 500 TPS, `argos-matrix` with `-Pchaos.profile=full`) and INVALID elsewhere |
| Matrix skip (18 §2.2, §6.2, O-S05) | `argos.integration-test` | `matrixTest` runs only when `ARGOS_RUNNER_LABELS` contains `argos-matrix` or `-Pmatrix.combo=<id>` / `-Pmatrix.group=<group>` is given; otherwise it is SKIPPED and prints `WARNING: <task> SKIPPED: not on a self-hosted 'argos-matrix' runner ...` |
| Matrix definition (18 §6.1, WO-50) | `agent/agent-testing` configuration, build-logic `MatrixCatalog` | `test/matrix/matrix.yaml` is parsed when agent-testing is configured: an unknown key, an ID outside its group's range, a JDK the app does not support (02 §9), a duplicate ID, an unknown `-Pmatrix.combo` or `-Pmatrix.group`, or YAML outside the documented subset fails the build with the file and line. `matrixTest-<ID>`, `matrixResult-<ID>` and `matrixReport` never run in `check`. Tests T-MX-001..121 and T-BLD-029 (proposed) in `build-logic/src/test/.../testing/Matrix*Test.kt` |
| Unit tests (18 §2.2) | `argos.java-common`: `checkUnitTestSources` in `check` | A test in `src/test` tagged `@Tag("slow")` or `@Tag("integration")`, or using Testcontainers' `@Testcontainers`/`@Container`, fails `check`: move it to `src/integrationTest` |
| Unit test duration (18 §2.2) | `argos.java-common`: after each `test` run | Unit tests slower than 1 s are listed as a `WARNING` (not a failure: durations depend on the machine and JVM warm-up). Move them to `src/integrationTest` and tag them `@Tag("slow")` |
| `-XX:+BytecodeVerificationLocal` (19 §11) | `argos.integration-test` | `integrationTest` and `matrixTest` JVMs run with it (with `-XX:+UnlockDiagnosticVMOptions`) |
| Server nightly gate (ADR-0010 C-100) | `server/build.gradle.kts` | Nightly server integration tests (T-SV-002: V001 plus generator 1/100 data, then the next-version fixture scripts) run in `:server:integrationTest` only with `-Pserver.nightly=true`; otherwise they are SKIPPED with the reason. Timing tables: `server/build/reports/migration/timings.md`. The nightly job is WO-50 |
| Config-key snapshot (T-API-009, ADR-0010 C-96, 19 §9) | `:server:contractTest` (`ConfigKeySnapshotTest`, `ConfigEnvAliasSnapshotTest`); `:agent:agent-core:test` (`AgentConfigKeySnapshotTest`, `MetricNamesSnapshotTest`) | `server/src/contractTest/resources/config-keys.txt` lists every `argos.*` key with its type and default; `config-env-aliases.txt` lists the short environment names of 17 §3.8 with the key each one sets, and the defaults of `server.port`, `server.address`, `management.server.port` and `management.server.address`. `agent/agent-core/src/test/resources/config/agent-config-keys.txt` lists every PUBLIC and DIAGNOSTIC agent key of `ConfigKeys` (key, type with enum values, default, remote column, kind, `ARGOS_<KEY>` variable; `test.*` keys are dev-only and left out) and the test pins `ARGOS_TOKEN`, `ARGOS_CONFIG` and `argos-agent.yml`; `metrics/metric-names.txt` lists the agent's metric families. A removed, renamed or retyped key, a changed default, remote column or variable, a retargeted or removed alias or an unlisted new key or alias fails `check` with a diff. Edit the files by hand in the same change, add a CHANGELOG entry and report it; no test regenerates them |
| Flyway file names (C-28) | `argos.java-server`: `checkFlywayNames` in `check` | `server/src/main/resources/db/pg` holds only `V<NNN>__<lower_snake>.sql`, numbered from 001 without gaps or duplicates, no subdirectories, no repeatable, undo or baseline files. A released file never changes: a change is the next number |

Review rules the build cannot check cheaply:

- Unit tests use no network and no containers (a test that opens a socket to anything but an
  in-process fake belongs in `src/integrationTest`).
- Integration tests use real processes and real databases (Testcontainers); never mock ClickHouse or
  PostgreSQL (CLAUDE.md).
- Tests use an injectable `Clock` instead of `Instant.now()`; generators use a fixed seed
  (default 42) and absolute time ranges (18 §15). Agent timing is asserted with ranges.
- No automatic retries (DSN-18-13). A test that failed intermittently three times is tagged
  `@Tag("quarantine")` with an issue (`kind/bug`, `area/test`) and an owner, and is fixed or deleted
  within 7 days. Quarantined tests report results but do not gate; more than 10 blocks a release.
  `traceabilityReport` lists them.
- A test is never weakened to make it pass; a criterion that has to change is reported and asked.

## 4. Test IDs (18 §3, DSN-18-01)

Every test that verifies a catalogued behaviour carries its test ID at the start of its display name
or title. IDs are reserved in [18 appendix A](../docs/18-test-design.md#부록-a-테스트-카탈로그-id-예약);
work orders quote them in their acceptance criteria.

| Prefix | Area | Example |
|---|---|---|
| `T-AG-nnn` | Agent unit and integration (instrumentation, core, transport) | T-AG-031 JDBC nesting removed |
| `T-SV-nnn` | Server unit and integration (ingest, alerting, commands, administration) | T-SV-010 WAL written before ack |
| `T-ST-nnn` | Storage (schema, rollups, migrations, PoC) | T-ST-014 percentile error |
| `T-API-nnn` | API contract | T-API-003 source selection rule |
| `T-UI-nnn` | UI unit and component | T-UI-021 XLog coordinate transform |
| `T-E2E-nnn` | Playwright | T-E2E-040 deep link back after login |
| `T-ACC-nn` | Correctness, same number as D-nn | T-ACC-13 no duplicates, no loss |
| `T-NFR-nn` | Non-functional, same number as N-nn (N-10a..d -> T-NFR-10a..d) | T-NFR-01 relative CPU |
| `T-SEC-nn` | Security, same number as SEC-nn | T-SEC-23 hard cap |
| `T-MX-nnn` | Matrix combination | T-MX-017 boot3 x JDK 21 x VT |
| `T-CH-nnn` | Chaos | T-CH-004 disk full |
| `T-SOAK-nnn` | Long run | T-SOAK-001 72 h heap slope |
| `T-BLD-nnn` | Build and packaging | T-BLD-002 shaded class list |
| `T-ACPT-nn` | Human acceptance, same number as S-nn | T-ACPT-07 agent onboarding |

Rules:

- The ID starts the name: `@DisplayName("T-AG-031 nested JDBC calls produce one step")`,
  `test('T-E2E-040 deep link returns after login', ...)`, `it('T-UI-021 maps x to pixels', ...)`.
  A Kotlin test may use the backtick method name instead (`` fun `T-BLD-007 ...`() ``).
- Several IDs: `T-BLD-006/T-AG-060 release jar: no temp file, no output`.
- Finer cases take a letter suffix: `T-AG-031a`, `T-AG-031b` (18 appendix A). A suffixed ID is a
  partial check of its catalogue ID, an ID that already ends in a capital letter takes a lower-case
  one (`T-SEC-3A` -> `T-SEC-3Aa`), and the traceability report shows the passed results of partial
  checks as partial while no test with the catalogue ID itself has passed (ADR-0010 C-122, C-182).
- Parameterized tests put the ID in the name template: `@ParameterizedTest(name = "T-BLD-003 rejects a jar without {0}")`.
  Dynamic tests (`@TestFactory`) put it in each `DynamicTest` name.
- A test without a catalogued behaviour may have no ID; it still states what it checks.
- IDs are never reused, not even after a test is deleted. A new ID is proposed in the work-order
  report; the PM adds it to 18 appendix A when integrating (T-BLD-013 and T-BLD-014 are such
  proposals from WO-05).

## 5. Requirement tags

The requirement a test verifies is a JUnit tag `req:<ID>`; the ID is a requirement or design ID of
docs/01, docs/02, docs/06 or the design documents (`D-24`, `N-01`, `F-A31`, `SEC-06`, `O-L03`,
`DSN-18-15`, ...), as in the "요구" column of 18 appendix A.

```java
@Tag("req:F-A03")                 // on the class: applies to every test in it (nested classes too)
class JdbcNestingTest {           // illustration
  @Test
  @Tag("req:D-24")                // on the method: adds to the class tags
  @DisplayName("T-AG-031 nested JDBC wrappers produce one SQL step")
  void nestedWrappers() { ... }
}
```

- Several requirements: repeat `@Tag` (or `@Tags({@Tag("req:A"), @Tag("req:B")})`; Kotlin
  `@Tags(Tag("req:A"), Tag("req:B"))`).
- Dynamic tests (`@TestFactory`) carry the tags of their factory method and class only: JUnit has
  no tags per `DynamicTest`, and a `@Tag` on a helper method the dynamic test calls has no effect
  (neither in JUnit nor in the report). A class with one factory gets its runtime cases attributed
  in the report; with several factories, give each a distinct class (or a `@ParameterizedTest` name
  template) so that the report can tell their cases apart.
- Playwright: `test('T-E2E-040 ...', { annotation: { type: 'req', description: 'D-24' } }, ...)` or
  `testInfo.annotations.push({ type: 'req', description: 'N-14' })`.
- Vitest has no tags: put the test ID in the title; a `{ type: 'req', description: '<ID>' }` object in
  the file applies to every titled test of that file in the report.
- Other tags in use: `slow` and `integration` (integration tests only), `quarantine` (section 3).

## 6. Traceability report

```
./gradlew check traceabilityReport          # run the tests, then report their results
./gradlew traceabilityReport                # report the results already in this workspace
```

`traceabilityReport` (root project, seconds, no network, no containers, runs no test) writes:

- `build/reports/traceability.md`: summary; requirement -> test ID -> test (class, name, file:line)
  -> suite -> last result; test IDs without a requirement tag; quarantined tests; tests that failed
  in the last results.
- `build/reports/traceability.json`: the same data for tools.

Sources it reads: the Java and Kotlin sources of every test source set (`src/test` and `src/*Test`,
including `build-logic`), the UI test files (`*.test.*`, `*.spec.*` below `ui/`), the shell test
scripts (`*.test.sh`, `test/container/*-cases.sh` and the smoke scripts; IDs of quoted assertion
descriptions and the header comments `# test-ids: …` and `# req: …`, not IDs in other comments) and
the rows of `test/matrix/matrix.yaml` (T-MX-nnn, requirement N-13). Results it reads (WO-53,
ADR-0010 C-200 (1), C-250 (6)):

| Results | Written by | Suite in the report |
|---|---|---|
| `build/test-results/<task>/*.xml` of every project | Gradle test tasks (JUnit XML) | the task name |
| `ui/build/test-results/vitest/junit.xml` | `pnpm -C ui test` (Vitest JUnit reporter; classname = file, name = `describe > … > title`) | `vitest` |
| `ui/test-results/**/results*.json` | Playwright JSON reporter (`results-<project>[-shard-i-of-n].json`); per-test `{ type: 'req' }` annotations replace the file's requirements, and the `T-E2E-<screen>-<n>` tests of the required YAML exist only here | `playwright:<project>` |
| `build/test-results/shell/<suite>/<script>.tap` | `test/traceability/shell-results.sh` from the `PASS`/`FAIL`/`SKIP` lines of the shell suites (`run-all.sh` of .github/scripts/test, deploy/compose/test, scripts/test, test/release; `deploy/test/verify-release.test.sh`) | `shell:<suite>` |
| `build/matrix/T-MX-*.json` | `matrixTest` (WO-50): failed > 0 FAILED, else passed > 0 PASSED, else SKIPPED | `matrix` |

"Last result" is the newest result; `NOT RUN` means the suite has not run in this workspace. When it
runs together with test tasks it runs after them. `-Ptraceability.results=none` ignores every result
file (what a clean checkout and the CI job see).

Shell result format (one file per script, UTF-8, LF; a re-run overwrites it). A TAP 13 subset:

```
TAP version 13
# argos-shell-results 1
# suite: deploy-compose
# script: deploy/compose/test/backup.test.sh
# started: 2026-11-26T10:00:00Z
# exit: 0
ok 1 - T-SEC-84a: backup.sh exits 0 (caller umask 022)
not ok 2 - <description>
ok 3 - <description> # SKIP <reason>
1..3
```

A test line's IDs are those of its description, else the script's `# test-ids:` header; a non-zero
`exit` without a `not ok` line counts as one failed case.

### 6.1 Catalogue and 18 §16 check (`checkTraceability`)

```
./gradlew checkTraceability -Pargos.ui.skip=true                               # with the results of this workspace
./gradlew checkTraceability -Ptraceability.results=none -Pargos.ui.skip=true   # a clean checkout (CI)
```

It writes `build/reports/traceability-check.md` and `.json` and fails on any of these rules (the
message lists every finding):

| Rule | Fails when |
|---|---|
| R1 | a test ID used in a source, a result or `test/traceability/evidence.yaml` is not in the first column of a table of docs/18 (body or appendix A). A suffixed ID (`T-SV-010a`) needs its own row |
| R2 | an expectation of the 18 §16.1 table has neither a test (source or result) nor an evidence entry: each expected ID, each family named without a number (`T-MX 전부`), and the non-test evidence of design (`(설계 확인, 테스트 없음)`) and human rows (`T-ACPT …`, `ASVS 체크리스트`). Only suffixed IDs of an expected ID count, with a warning; so does an ID that only starts a string literal of a test source (a dynamic test's runtime name such as `"T-ACC-24 " + where`, warning `dynamic-name-only`) |
| R3 | a required requirement is not covered: D-01~D-26 and every N of docs/01 by a tagged test, a 18 §16.1 ID or an evidence entry; every stage-M SEC of docs/06 (stage cell names M) by a T-SEC-nn ID when its verification is `자동` (a test tagged `req:SEC-nn` under another ID also covers it, with the warning `sec-without-id`), otherwise by a tagged test, a T-SEC-nn ID or an evidence entry |
| R4 | `docs/gates/gate1-evidence.md` is missing or names an ID that is not in the catalogue |

Reported, never failing: ID-number rule mismatches (a T-ACC-nn, T-NFR-nn or T-SEC-nn test tagged
with requirements but not D-nn, N-nn or SEC-nn), malformed IDs (number width other than 18 §3, for
example T-SEC-010, when not catalogued), catalogue IDs covered only partly, IDs with both a test and
an evidence entry (the tests are shown first), and FAILED results.

ID expressions of the catalogue (docs/18 appendix A head): one ID, `~` ranges keeping the start's
zero padding (`T-ST-001~020`), `·` lists, suffix-letter ranges and lists (`T-AG-052a~k·p~s`),
named E2E IDs (`T-E2E-stats-1~10`), comma lists in a first column (`T-NFR-01~04, 26~30`); text after
an ID (`T-NFR-30 (부착 시험)`) is a variant note of the same ID.

**Split marker (ADR-0010 C-182).** A suffixed ID checks part of its catalogue ID, so a passed
`T-AG-031a` alone is "partial of T-AG-031". An appendix A row whose description starts with
`(분할)` says that its suffixed IDs together divide the catalogue ID: when every one of them passed
in the last results, the check report shows the catalogue ID as `full (split)`, otherwise as
`partial` with the IDs that did not pass. Without the marker suffixed IDs are always partial.

**Non-test evidence** (`test/traceability/evidence.yaml`, format in its header): entries of kind
`measurement` (dedicated-machine or target-hardware reports), `human` (acceptance checklists,
reviews, rehearsals, non-participant installs), `design` and `deferred` (a later work order or
gate), each with its source, owner work order and gate. An entry covers its requirements (R3) and
IDs (R2); the reports show its kind. Add an entry only for evidence that is not a test.

### 6.2 Gate 1 NFR page (`gate1Report`)

```
./gradlew gate1Report -Pargos.ui.skip=true                       # build/reports/gate1.md
./gradlew gate1Report -Pgate1.publish=true -Pargos.ui.skip=true  # docs/bench/gate1.md
```

One row per NFR of the Gate 1 table (03 §4: N-01~N-07, N-15, N-23~N-30). A performance row is
PASS or FAIL only from a dedicated-machine report in docs/bench whose summary comment
(`<!-- argos-…-summary {…} -->`) says `"approved": true` (ADR-0010 C-252 (5)): the bench fragments
`gate1-agent-*.md` and the reports they cite (N-01~N-04, N-26~N-30), `chaos-*.md` (N-05),
`server-load*.md` (N-06), `api-bench-*.md` from an enforced run (N-07, N-24; both C-180 conditions,
warm median <= target and cold <= 2 x target, for every call). Anything else reads
`no evidence (INVALID)` with the reason. N-15, N-23 and N-25 take the last results of T-SV-020,
T-SV-030 and T-SV-031 (source CI). The page ends with what only a person can close (C-240 (7),
C-244 (7), C-245 (1)). Every Gate 1 row with its evidence: `docs/gates/gate1-evidence.md`.

### 6.3 CI and limits

The pull-request job `traceability` runs `checkTraceability traceabilityReport gate1Report` without
result files (every test NOT RUN, never FAILED) and keeps the three reports as an artifact.

Limits: IDs and tags are read from source text, so names built from constants and tags added at
runtime or through meta-annotations are not seen; requirements of a Vitest file apply to all its
titled tests (Playwright per-test annotations replace them once a results file exists).
Parameterized tests whose name template has no literal prefix (`name = "{0}"`), in a class with
several of them, cannot be told apart: their source rows show NOT RUN although they ran, and their
cases appear as rows of the class with the class tags (for example `ServletHelperTest` javax and
jakarta); give such templates a literal prefix. Checks inside `composeSmoke` and `demoSmoke` write
no result file yet; they stay evidence of their reports.

## 7. Time budget of the pull-request gate (18 §2.4)

The PR gate must finish within **25 minutes**, measured on the longest of the parallel CI jobs of
`.github/workflows/pr.yml` (GitHub-hosted, 4 cores). Layer budgets from 18 §2.1: unit `test` 5 min,
transformation checks (`verifyAgentJar`, `archTest`) 4 min, `integrationTest` 12 min, `contractTest`
3 min, light `securityTest` 8 min, UI E2E 10 min.

| CI job | Command | Reference time, WO-05 integration after review fixes (2026-11-03) |
|---|---|---|
| build-java | `./gradlew build -x integrationTest` | 4 min 18 s (468 tasks, all re-executed, UI included; 690 unit tests, 99 ArchUnit tests, Vitest 456); WO-02 measured the whole job in a 4-CPU Linux container with an empty cache at 11 min 6 s |
| integration | `./gradlew integrationTest -Pqueries.profile=ci` | 6 min 17 s (155 tasks re-executed, 154 tests; test time: storage queries 313 s, schema tests 121 s, generator 97 s, agent-testing 138 s for 48 tests on JDK 8 and 21 + shop-boot3@21, sample apps 81 s) |
| security-light | `./gradlew securityTest -Psecurity.pii.requests=2000` | 42 s (59 tasks; compilation only: no `src/securityTest` sources yet). WO-46 integration (2026-11-26, jars up to date): 9 min 22 s, all of it `:server:securityTest` (218 tests in 36 classes; the PII seed grep class `PiiSeedGrepSecurityTest` with 10,000 requests 105 s, `PermissionMatrixSessionSecurityTest` 48 s, `PasswordSecurityTest` 45 s, `SetupBootstrapSecurityTest` 44 s). Over the 8-minute light budget on this laptop. Since ADR-0010 C-250 (2) the PR job passes `-Psecurity.pii.requests=2000` (the PII class alone about 91 s here); nightly and the release gate keep the default 10,000 (N-10c) |
| agent-dist-checks | `./gradlew :agent:agent-dist:check verifyAgentJar` | 52 s (62 tasks, `checkManifestVersion` included) |
| lint | Spotless, SPDX, ESLint, tsc, Prettier, i18n, workflow policy, actionlint, shellcheck, CI script tests | about 2 min 5 s (the CI script tests take 1 min 17 s of it) |
| license | `./gradlew checkLicenses checkDevOnlyLicenses` + UI license checks | 20 s |
| build-ui | `pnpm -C ui install --frozen-lockfile`, `build`, `test`, `check:external` | 20 s (Vitest 456 tests, warm pnpm store) |

The full agent matrix (`:agent:agent-testing:integrationTest -Pagent.attach.jdks=8,11,17,21,25
-Pagent.e2e=<7 combinations>`, 93 tests of which 3 skip on purpose) took 3 min 1 s; it is
not a PR job. A WAR combination (`-Pagent.e2e=shop-war-jakarta@21`) adds 30 to 50 s per
combination (one standalone Tomcat start) and is not in the default PR configuration.

Reference machine: Windows 11 laptop, Ryzen 5 5600H (6 cores, 12 threads), 15.3 GiB, nothing else
running (`--rerun-tasks`, warm Gradle and pnpm caches). These are reference values, not CI
measurements: the longest job (integration) is under 7 min here, well inside the 25-minute budget,
and the budget is judged on the CI runners (4 cores).
