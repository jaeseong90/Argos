<!-- SPDX-License-Identifier: Apache-2.0 -->

# Compatibility matrix

The agent compatibility matrix ([docs/18 section 6](../../docs/18-test-design.md), DSN-18-11): every
supported combination of sample application, JDK and variant runs the agent-testing suites with the
release agent jar, and the results become [docs/compatibility-matrix.md](../../docs/compatibility-matrix.md).

| File | Purpose |
|---|---|
| `matrix.yaml` | The single definition of the combinations (rows). Read by build-logic `MatrixCatalog` at configuration time |
| `community-reports.yaml` | Combinations users reported working (◎), curated by maintainers from `kind/compat-report` issues |
| `fetch-coexist-agents.sh` | Downloads the pinned JaCoCo and OpenTelemetry agents of the coexistence rows into `agent/agent-testing/build/matrix-agents` |

The matrix does not have suites of its own beyond `agent/agent-testing/src/matrixTest`
(`io.argos.agent.testing.matrix`: the 18 section 6.1 smoke sequence, WAR redeploys, JSP forward,
coexistence, service-name boundaries, JDK 8 TLS 1.3). Each row runs the existing agent-testing
suites (`src/integrationTest`) with the row's application, JDK and switches, wired exactly like
`integrationTest` (`agent/agent-testing/build.gradle.kts`, `wireHarness`).
[agent/agent-testing/matrix-parameters.md](../../agent/agent-testing/matrix-parameters.md) maps every
row range to those suites and switches.

## Running

```
./gradlew matrixTest -Pmatrix.combo=T-MX-021                 # one combination (aliases work: T-MX-017)
./gradlew matrixTest -Pmatrix.combo=T-MX-021,T-MX-102         # several
./gradlew matrixTest -Pmatrix.group=boot3                     # one group (comma list for several)
./gradlew :agent:agent-testing:matrixTest-T-MX-021            # one combination task by name
./gradlew :agent:agent-testing:matrixReport                   # docs/compatibility-matrix.md from build/matrix
./gradlew :agent:agent-testing:matrixReport -Pmatrix.results=<dir>   # from downloaded nightly artifacts
```

- Without `-Pmatrix.combo` or `-Pmatrix.group`, `matrixTest` runs every row on the self-hosted runner
  (`ARGOS_RUNNER_LABELS` contains `argos-matrix`) and nothing elsewhere (SKIPPED with a warning, 18
  section 2.2). An unknown ID or group fails the build at configuration time.
- Each row is the Test task `:agent:agent-testing:matrixTest-<ID>`; rows run one after another. Its
  finalizer `matrixResult-<ID>` writes `build/matrix/<ID>.json` at the repository root, also when a
  test failed. `--continue` keeps going after a failed combination.
- Heavy runs share the machine: on the development laptop take the lock, one heavy run at a time:
  `bash test/lock/argos-lock.sh run agent-it --purpose "matrix T-MX-021" -- ./gradlew matrixTest -Pmatrix.combo=T-MX-021`.
  Never run the full matrix on a laptop (about 60 rows; WAR redeploys, databases and coexistence
  rows take several minutes each).
- The coexistence rows need `bash test/matrix/fetch-coexist-agents.sh` first; the devtools row
  builds `:sample-apps:shop-boot3:sampleAppDevtoolsJar` itself.
- New transform baselines (`agent/agent-testing/baselines/transformed-classes/`) of a combination
  are recorded with `-Pagent.baseline.record=true` on the combination task; review the diff. The
  smoke sequence has its own file per row, `<app>-<jdk>[-<container>][-vt]-smoke.txt`, where
  `<container>` is a `SAMPLE_CONTAINER` other than Tomcat (`-jetty`, `-undertow`).
- None of these tasks is part of `check` (build-logic `TestLayers`: `matrixTest*`, `matrixResult*`,
  `matrixReport`).

## File format

`matrix.yaml` and `community-reports.yaml` use a YAML subset that build-logic parses itself (no YAML
library in the build): block mappings and sequences (a sequence may sit at the indentation of its
key), plain, `"double quoted"` (escapes `\"`, `\\`) or `'single quoted'` scalars, one-line flow
sequences `[a, "b c"]` and `[]`, comments after `#`. Tabs, anchors and aliases (`&`, `*` at the start
of a value), tags, block scalars (`|`, `>`), flow mappings and multi-line flow sequences are errors
with the file and line. A test pattern starting with `*` must be quoted.

`matrix.yaml`:

```yaml
version: 1
suite-sets:            # named lists of test patterns, used as set:<name> in suites and required
  boot: [io.argos.agent.testing.matrix.MatrixSmokeTest, io.argos.agent.testing.servlet.*]
rows:
  - id: T-MX-022       # T-MX-nnn inside the range of its group
    aliases: [T-MX-017]
    group: boot3
    app: shop-boot3
    jdk: 21
    vt: true
    suites: [set:boot]
    required: [io.argos.agent.testing.matrix.MatrixSmokeTest]
```

### Row fields

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | `T-MX-nnn`, unique, inside the range of `group` (below) |
| `aliases` | no | Other IDs that select the row (`T-MX-017` = `T-MX-022`) |
| `group` | yes | One nightly job; ranges below |
| `app` | yes | `shop-boot2`, `shop-boot3`, `shop-boot4`, `shop-heavy`, `shop-batch`, `shop-war-javax`, `shop-war-jakarta`, or `trivial` (the attach app of the transport tests: no sample app, `-Pagent.attach.jdks=<jdk>`) |
| `jdk` | yes | 8, 11, 17, 21 or 25, only the JDKs of 02 section 9 for the app (JDK 25 runs with `--sun-misc-unsafe-memory-access=deny`, a harness preset) |
| `vt` | no | `true`: the virtual-thread variant (Boot 3/4 apps on JDK 21+). Passed as `argos.test.matrix.vt` |
| `tomcat` | WAR apps | `tomcat9` (javax), `tomcat10` (10.1) or `tomcat11` (jakarta). Passed as `argos.test.matrix.tomcat` |
| `app-env` | no | `[NAME=value, ...]` added to every sample app the suites start (`argos.test.matrix.appEnv`, `K=V;K=V`), for example `SAMPLE_CONTAINER=jetty` |
| `props` | no | Harness switches: `agent.jdbc.containers`, `agent.jdbc.oracle`, `agent.container`, `agent.attach.jdks`, `agent.gc.jdks` (matrix-parameters.md). The row's app and JDK replace `-Pagent.e2e`; the old JRE homes (`-Pagent.jdk8u242`, `-Pagent.jdk8u252`) and container image overrides come from the command line |
| `coexist` | no | `jacoco-before`, `jacoco-after`, `otel-before`, `otel-after` or `devtools` (`argos.test.matrix.coexist`; agents from `argos.test.matrix.agentsDir`) |
| `suites` | yes | Gradle test filter patterns (class names with `*`) of `src/matrixTest` and `src/integrationTest` |
| `required` | yes | Patterns that must pass for ○: a class, or `<class>.<test display name>` with `*` (dynamic tests) |
| `status` | no | `gate` (default) or `unverified`: runs, failures do not fail the build, never ○ (02 section 9 △, untested containers) |
| `requires` | no | `env:NAME`, `prop:<gradle property>` or `os:linux\|windows\|mac` the runner must provide; without them the tests skip with the reason and the row is △ |
| `note` | no | Shown in the Variant column |

Every combination task also gets `argos.matrix.combo=<ID>` and `argos.test.matrix.resultDir=build/matrix/<ID>`
(emptied before the run): tests may write `[mx-...]` result lines (`*.txt`) and `*.properties` with
`startup_ms`, `transformed_classes` and `transactions` there; `[mx-...]` lines in the JUnit
`system-out` are read too. The devtools row also gets
`argos.test.sampleApp.shop-boot3-devtools=<jar>`.

### Groups and ID ranges

| Group | IDs | Rows |
|---|---|---|
| `boot2` | T-MX-001..012 | shop-boot2 x JDK 8, 11, 17, 21; 25 unverified |
| `boot3` | T-MX-020..031 | shop-boot3 x JDK 17, 21, 25, virtual threads on 21 and 25 (T-MX-017 = T-MX-022) |
| `boot4` | T-MX-040..051 | shop-boot4 x JDK 17, 21, 25, virtual threads on 21 and 25 |
| `heavy` | T-MX-060..063 | shop-heavy x JDK 17, 21, 25 (startup time, N-27) |
| `batch` | T-MX-070..075 | shop-batch x JDK 17, 21, 25 (kind=BATCH) |
| `war-javax` | T-MX-080..087 | Tomcat 9 x JDK 8, 11, 17, 21, 50 redeploys |
| `war-jakarta` | T-MX-090..095 | Tomcat 10.1 and 11 x JDK 17, 21, 25, two web applications |
| `jdbc` | T-MX-100..108 | MySQL, MariaDB, PostgreSQL (with Tomcat JDBC and DBCP2), SQL Server, Oracle, Tibero, Altibase, CUBRID, Db2 |
| `coexist` | T-MX-110..119 | JaCoCo before/after, OpenTelemetry before/after, devtools (T-MX-114, proposed) |
| `routing` | T-MX-120..121 | Routing DataSource and nested wrappers, Boot 2 and 3 |
| `containers` | T-MX-130..139 (proposed) | Jetty 9.4 (servlet 3.1), Undertow 2.2, Jetty 12, Undertow 2.3, Jetty 12.1 |
| `transport` | T-MX-140..149 (proposed) | Old JREs 8u242 and 8u252, JDK 8 TLS 1.3, container mode, Linux variants, cgroup v2 |
| `boundary` | T-MX-150..159 (proposed) | httpclient-jdk11 on JDK 17 and 25, custom methods on WAR and JDK 11, 17 |

The ranges outside 18 section 6.1 are proposals awaiting registration in docs/18; the parser
enforces them so that IDs stay stable.

## Verdicts

`matrixReport` (build-logic `MatrixReport`) gives each row one mark from its latest result
(`build/matrix/<ID>.json`; with several files for one ID the newest run wins):

| Mark | When |
|---|---|
| ✗ failed | At least one test failed. On an `unverified` row the details say "not a gate" |
| ○ verified | A `gate` row that ran with every `requires` met, at least one test passed, no test failed, and every `required` pattern has a passed test and no skipped one |
| △ not verified | Everything else, with the reason: not run, `unverified` row, missing requirement, nothing passed, a required test that did not run or was skipped |
| ◎ community verified | A △ row with a report in `community-reports.yaml` (link shown). Never replaces ○ or ✗ |

A combination that did not run is never ○. The page has no clock (the run dates and commits come
from the results), and the generator makes no network calls: community reports are links only.
`agent/agent-testing/build/matrix-report/gate-failures.txt` lists the gate rows that failed; the
nightly report job fails when it is not empty, after it has uploaded the page.

## Nightly

`.github/workflows/nightly.yml` (triggers schedule, `workflow_dispatch` with `scope`, `group`,
`combo` inputs, and pushes to `main` and `release/**`; never pull requests): a GitHub-hosted `plan`
job reads the groups of `matrix.yaml`; one self-hosted `matrix` job per group (at most 4 at once)
runs `./gradlew --continue :agent:agent-testing:matrixTest -Pmatrix.group=<group>` and uploads `build/matrix/` and the
JUnit XML; `report` gathers the artifacts, runs `matrixReport -Pmatrix.results=...` and uploads
`compatibility-matrix` (the page). The same workflow runs the long server suites: `:server:integrationTest
-Pserver.nightly=true` (T-SV-002), `securityTest` with 10,000 PII seed requests, `sampleSmoke`,
`:deploy:composeSmoke` and `:deploy:demoSmoke -Pdemo.smoke.enforce=true`. Every self-hosted job
follows check-workflows.sh W8 (trusted-ref guard, no persisted credentials, no write scope, secrets
only through the `trusted` environment). The GitHub-hosted `publish` job (after a successful
`report`) commits the page to a `matrix/nightly-*` branch and opens a pull request; its
`contents: write` and `pull-requests: write` are in the check-workflows.sh W4 allowlist (ADR-0010
C-256 (4)). It needs the repository setting "Allow GitHub Actions to create and approve pull
requests"; without it the job fails and a maintainer commits the `compatibility-matrix` artifact.

### Runner preparation (a person)

- A Linux x86_64 machine registered as a self-hosted runner with the labels `self-hosted`, `linux`,
  `argos-matrix`, in the repository's trusted runner group; for 4 parallel groups, 4 runner
  instances and about 16 GiB of memory each (a WAR row runs Tomcat plus the test JVM; database
  containers take up to 4 GiB).
- Docker Engine with cgroup v2, usable by the runner user (Testcontainers, database containers,
  container-mode rows, Compose smokes). Host ports 8080 and 9100 free for the Compose jobs.
- Outbound HTTPS to github.com and the Actions services, Maven Central and the Gradle plugin portal,
  services.gradle.org, the Temurin JDK downloads (setup-java), nodejs.org and the npm registry, Docker
  Hub and mcr.microsoft.com (docs/19 section 13, open item 10). The matrix itself contacts nothing
  else; the coexistence agents come from Maven Central with pinned SHA-256.
- JDKs come from `actions/setup-java` (Temurin 8, 11, 17, 21, 25; Gradle finds them through
  `-Porg.gradle.java.installations.fromEnv`). The JDK 8 toolchain must be 8u261 or later (TLS 1.3,
  T-MX-142).
- Repository variables (optional; rows without them are △):
  - `ARGOS_MATRIX_JDK8U242_HOME`, `ARGOS_MATRIX_JDK8U252_HOME`: homes of AdoptOpenJDK 8u242-b08 and
    8u252-b09.1 unpacked on the runner (T-MX-140, T-MX-141).
  - `ARGOS_TEST_JDBC_DRIVER_DIR`: a directory on the runner with the vendor JDBC drivers (Tibero,
    Altibase, CUBRID, Db2), never committed (docs/licenses/dev-only.md `vendor:` rows).
- Environment `trusted` secrets (optional): `ARGOS_TEST_<DB>_URL`, `_USER`, `_PASSWORD` for `ORACLE`
  (in-house 19c), `TIBERO`, `ALTIBASE`, `CUBRID`, `DB2`. The tests never print them; the JDBC URLs
  must not carry credentials.
- Rows that need a Linux host (T-MX-144) are △ elsewhere. Real cgroup v1 hosts are a human check
  outside the matrix.

To reproduce a nightly row locally, set the same variables and run the row with `-Pmatrix.combo`.
