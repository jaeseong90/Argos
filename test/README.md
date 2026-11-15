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
| `server/src/contractTest` | OpenAPI lint and diff, permission matrix, schema and config-key snapshots (from WO-20/WO-23) |
| `server/src/testFixtures` | `ServerTestEnvironment` (WO-20): PostgreSQL 16 and ClickHouse 26.8 in Testcontainers (digest-pinned images of `deploy/*/image.properties`), roles from `deploy/postgres/roles.sql`, generated passwords in owner-only files, Spring properties with `*password-file` keys only; used by the server `integrationTest` and `securityTest`, reused by WO-21, WO-23 and WO-24. The server test JVMs use `server/build/tmp/<task>` as `java.io.tmpdir`, so nothing they create lands in the system temp directory that the agent harness watches during attach runs |
| `<module>/src/securityTest` | SEC suite (server, agent-testing); UI security checks are Playwright tests tagged `@security` |
| `agent/agent-testing/src/matrixTest` | Compatibility matrix (sample apps x JDKs, 18 §6) |
| `agent/agent-testing/src/main` | The agent test harness (AppLauncher, FakeCollector, assertions, baselines; see its README) |
| `agent/agent-dist` | `verifyAgentJar`: shading, relocated strings, major version 52, module index (T-BLD-001..006) |
| `test/bench` | Overhead bench harness, `benchTest` (18 §7) |
| `test/load/fake-agents` | Server load generator, `serverLoadTest` (18 §8; runner: WO-29) |
| `test/storage/*` | Storage PoC: schema tests, generator (`storageGenerate`, `storageBench`), queries Q01..Q20 (18 §9) |
| `test/soak` | 72-hour soak, `soakTest` (18 §11.2; runner: WO-52) |
| `test/chaos` | Chaos scenarios, `chaosTest` (18 §11.1; scenarios: WO-52) |
| `test/agent` | `check-shading.sh`, the Unsafe allow-list, the WO-02 transport PoC (the WO-02 overhead harness moved to `test/bench`) |
| `test/lint` | `check-spdx.sh` and its exclusions (T-BLD-007) |
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
| `matrixTest` | `agent/agent-testing/src/matrixTest` | **no** | nightly on self-hosted `argos-matrix` only | `./gradlew matrixTest -Pmatrix.combo=<id>` or `-Pmatrix.group=<group>` |
| `benchTest` | `test/bench` | **no** | weekly on `argos-bench-app` + `argos-bench-load` | `./gradlew :test:bench:benchTest -Pbench.machine-profile=<id>` |
| `serverLoadTest` | `test/load/fake-agents` | **no** | `argos-storage`, manual | `./gradlew serverLoadTest -Pbench.machine-profile=<id>` (WO-29) |
| `storageBench` | `test/storage/generator` | **no** | `argos-storage`, manual | `./gradlew :test:storage:generator:storageBench -Pbench.machine-profile=<id> ...` |
| `soakTest` | `test/soak` | **no** | release gates, bench machines | `./gradlew soakTest -Pbench.machine-profile=<id>` (WO-52) |
| `chaosTest` | `test/chaos` | **no** | release gates, `argos-matrix` | `./gradlew chaosTest -Pbench.machine-profile=<id>` (WO-52) |
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

## 3. Rules the build enforces

| Rule | Where | What happens |
|---|---|---|
| Composition of `check` (18 §2.2) | `argos.test-layers` (root), task-graph guard | Every build whose graph contains `check` (dry runs included) fails before any task runs if a `check` depends on a task that never belongs in it, if a project's `check` does not run its `test`, `archTest`, `contractTest` or `verifyAgentJar`, or, on the full `./gradlew check`, if one of the anchors `:agent:agent-core:test`, `:agent:agent-testing:archTest`, `:server:contractTest`, `:agent:agent-dist:verifyAgentJar` no longer exists. Tasks excluded with `-x <name>` or `-x <path>` are not required (abbreviated `-x` names are not recognised). Rules: `build-logic/.../testing/TestLayers.kt`; tests T-BLD-013 |
| Machine profile (18 §2.2) | `argos.test-layers`, and each task again when it runs | `benchTest`, `serverLoadTest`, `storageBench`, `soakTest` and `chaosTest` fail fast without `-Pbench.machine-profile=<id>` (letters, digits, `.`, `_`, `-`). The id names the measurement machine in the report (for example `argos-bench-app`, `argos-storage`) |
| Pending runners | `test/load/fake-agents`, `test/soak`, `test/chaos` | With a machine profile, `serverLoadTest`, `soakTest` and `chaosTest` fail and name the owning work order (WO-29, WO-52). They never measure anything or report a result |
| Matrix skip (18 §2.2, §6.2, O-S05) | `argos.integration-test` | `matrixTest` runs only when `ARGOS_RUNNER_LABELS` contains `argos-matrix` or `-Pmatrix.combo=<id>` / `-Pmatrix.group=<group>` is given; otherwise it is SKIPPED and prints `WARNING: <task> SKIPPED: not on a self-hosted 'argos-matrix' runner ...` |
| Unit tests (18 §2.2) | `argos.java-common`: `checkUnitTestSources` in `check` | A test in `src/test` tagged `@Tag("slow")` or `@Tag("integration")`, or using Testcontainers' `@Testcontainers`/`@Container`, fails `check`: move it to `src/integrationTest` |
| Unit test duration (18 §2.2) | `argos.java-common`: after each `test` run | Unit tests slower than 1 s are listed as a `WARNING` (not a failure: durations depend on the machine and JVM warm-up). Move them to `src/integrationTest` and tag them `@Tag("slow")` |
| `-XX:+BytecodeVerificationLocal` (19 §11) | `argos.integration-test` | `integrationTest` and `matrixTest` JVMs run with it (with `-XX:+UnlockDiagnosticVMOptions`) |
| Server nightly gate (ADR-0010 C-100) | `server/build.gradle.kts` | Nightly server integration tests (T-SV-002: V001 plus generator 1/100 data, then the next-version fixture scripts) run in `:server:integrationTest` only with `-Pserver.nightly=true`; otherwise they are SKIPPED with the reason. Timing tables: `server/build/reports/migration/timings.md`. The nightly job is WO-50 |
| Config-key snapshot (T-API-009, ADR-0010 C-96) | `:server:contractTest` (`ConfigKeySnapshotTest`, `ConfigEnvAliasSnapshotTest`) | `server/src/contractTest/resources/config-keys.txt` lists every `argos.*` key with its type and default; `config-env-aliases.txt` lists the short environment names of 17 §3.8 with the key each one sets, and the defaults of `server.port`, `server.address`, `management.server.port` and `management.server.address`. A removed, renamed or retyped key, a changed default, a retargeted or removed alias or an unlisted new key or alias fails `check` with a diff. Edit the files by hand in the same change and report it; no test regenerates them |
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
- Finer cases take a letter suffix: `T-AG-031a`, `T-AG-031b` (18 appendix A).
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
- `build/reports/traceability.json`: the same data for tools. Nothing in CI generates or keeps
  either file yet: the CI job that generates the report and keeps it as an artifact (18 §15,
  18 §19) comes with WO-53.

It reads the Java and Kotlin sources of every test source set (`src/test` and `src/*Test`, including
`build-logic`), the UI test files (`*.test.*`, `*.spec.*` below `ui/`) and every JUnit XML file in
`build/test-results/<task>/`. "Last result" is the newest of those files; `NOT RUN` means the suite
has not run in this workspace. When it runs together with test tasks it runs after them.

Limits of this skeleton (WO-05): IDs and tags are read from source text, so names built from
constants and tags added at runtime or through meta-annotations are not seen; UI results are not
read (Vitest and Playwright write no JUnit XML); requirements of a UI file apply to all its titled
tests. Parameterized tests whose name template has no literal prefix (`name = "{0}"`), in a class
with several of them, cannot be told apart: their source rows show NOT RUN although they ran, and
their cases appear as rows of the class with the class tags (for example `ServletHelperTest` javax
and jakarta); give such templates a literal prefix. WO-53 adds the comparison with the 18 §16
table, the failing CI job and the catalogue sync.

## 7. Time budget of the pull-request gate (18 §2.4)

The PR gate must finish within **25 minutes**, measured on the longest of the parallel CI jobs of
`.github/workflows/pr.yml` (GitHub-hosted, 4 cores). Layer budgets from 18 §2.1: unit `test` 5 min,
transformation checks (`verifyAgentJar`, `archTest`) 4 min, `integrationTest` 12 min, `contractTest`
3 min, light `securityTest` 8 min, UI E2E 10 min.

| CI job | Command | Reference time, WO-05 integration after review fixes (2026-11-03) |
|---|---|---|
| build-java | `./gradlew build -x integrationTest` | 4 min 18 s (468 tasks, all re-executed, UI included; 690 unit tests, 99 ArchUnit tests, Vitest 456); WO-02 measured the whole job in a 4-CPU Linux container with an empty cache at 11 min 6 s |
| integration | `./gradlew integrationTest -Pqueries.profile=ci` | 6 min 17 s (155 tasks re-executed, 154 tests; test time: storage queries 313 s, schema tests 121 s, generator 97 s, agent-testing 138 s for 48 tests on JDK 8 and 21 + shop-boot3@21, sample apps 81 s) |
| security-light | `./gradlew securityTest` | 42 s (59 tasks; compilation only: no `src/securityTest` sources yet) |
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
