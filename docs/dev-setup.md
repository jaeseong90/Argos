# Development setup

This guide takes a new machine from nothing to a green build. **Goal: the first full build finishes
within 30 minutes** (downloads included) and a cached rebuild within 5 minutes. If it takes longer on
your machine, please note the time and what was slow in an issue; that is a bug in this guide or in
the build.

The commands below are the same ones CI runs (`.github/workflows/pr.yml`).

## 1. Requirements

| Tool | Version | Notes |
|---|---|---|
| git | any recent | Line endings are fixed by `.gitattributes` (LF); no `core.autocrlf` change is needed. |
| JDK | 21 | Runs Gradle and compiles every module. Agent modules are compiled with `--release 8`. Temurin is recommended. |
| JDK 8 | 8 (Temurin) | Runs the agent tests. **Optional to install:** Gradle downloads it automatically through toolchain provisioning (Foojay) on first use. |
| Node.js | 24.x, 24.14.0 or later (`ui/package.json` engines `>=24.14.0 <25`); Node 25+ ships without Corepack and is not supported | Includes Corepack, which provides the pnpm version pinned in `ui/package.json` (`packageManager`). Older 24.x releases bundle a Corepack that cannot start that pnpm version. |
| Docker | 24+, Compose plugin v2.24+ | Testcontainers (integration and security tests), the Compose installation and the E2E stack. Give it at least 4 GB of memory for the tests; the demo profile needs about 7.5 GB (7396 MiB) and the E2E stack up to 5 GB. |

You do not need to install Gradle or pnpm globally: use the Gradle wrapper (`./gradlew`) and Corepack.

What the build actually pins, compared with the outline of docs/19 section 10.1:

| Item | docs/19 section 10.1 | This repository |
|---|---|---|
| Gradle | the wrapper | Gradle 9.8.2 (`gradle/wrapper/gradle-wrapper.properties`); the daemon runs on JDK 21 (`gradle/gradle-daemon-jvm.properties`) |
| JDKs | 8 and 21 | 21 to run the build; 8, 11, 17 and 25 are downloaded by toolchain provisioning when a test needs them (agent tests on 8, attach and sample app matrices on 11, 17, 25) |
| Node.js and pnpm | Node LTS, pnpm through Corepack | Node 24.x from 24.14.0 (not 25), pnpm exactly as `packageManager` in `ui/package.json` |
| Shell | – | bash: Git Bash on Windows (the build and the test scripts run `.sh` files) |

### Linux and macOS

Install a JDK 21 (for example with your package manager or SDKMAN), Node.js 24.x (24.14.0 or later;
Node 25+ ships without Corepack and is not supported) and Docker. Then, from
the repository root after cloning (step 2):

```sh
corepack enable
corepack install -g "$(node -p "require('./ui/package.json').packageManager")"
```

The second command makes the pnpm version pinned in `ui/package.json` the Corepack default. It is
needed because the commands in this guide run `pnpm -C ui ...` from the repository root, which has no
`package.json` of its own; without it Corepack starts its latest pnpm, which refuses to work on `ui/`
(`ERR_PNPM_BAD_PM_VERSION`). Repeat it when `packageManager` changes. CI does the same.

### Windows

Use Git Bash (installed with Git for Windows) for the commands in this guide; `gradlew.bat` works in
PowerShell and cmd as well. Install Temurin 21 and Node.js 24 (24.14.0 or later) with the official
installers and Docker Desktop. Run the two Corepack commands above. `corepack enable` writes shims next
to `node.exe` and may need an administrator shell (otherwise it fails with `EPERM`); without it,
prefix pnpm commands with `corepack`, for example `corepack pnpm -C ui install`.

- Some repository paths are long. Run `git config --global core.longpaths true` before cloning, or
  clone into a short path such as `C:\src\argos`; otherwise git can fail with "Filename too long".
- The build runs the shell scripts in `test/` with Git Bash. Gradle finds it next to `git.exe`, even
  in PowerShell where `bash` may resolve to WSL; set `ARGOS_BASH` to the full path of `bash.exe` to
  choose another one.
- In PowerShell, quote Gradle properties that contain dots, for example
  `.\gradlew.bat build "-Pargos.ui.skip=true"`; unquoted, PowerShell splits the argument.
- When you commit on Windows, keep `gradlew` and the `*.sh` scripts executable (mode 100755) by
  adding them with `git add --chmod=+x`, for example
  `git add --chmod=+x gradlew test/agent/check-shading.sh test/lint/check-spdx.sh .github/scripts/*.sh .github/scripts/test/*.sh`.
  Git for Windows sets `core.filemode=false`, so a plain `git add` records new files as 100644 and
  `./gradlew` then fails on Linux and macOS and in CI with "Permission denied".
  `git ls-files -s gradlew` must show `100755`; `bash .github/scripts/check-file-modes.sh` checks
  every script (the `lint` job runs it).

### Behind a proxy or without internet access

Gradle needs Maven Central and the Gradle Plugin Portal, toolchain provisioning needs
`api.foojay.io`, and Corepack/pnpm need `registry.npmjs.org`. If JDK downloads are blocked, install
Temurin 8 and 21 yourself and point Gradle at them in `~/.gradle/gradle.properties`:

```properties
org.gradle.java.installations.paths=/opt/jdk/temurin-8,/opt/jdk/temurin-21
org.gradle.java.installations.auto-download=false
```

## 2. Clone and build

> **Note:** The argos-apm organization and repository do not exist yet (ADR-0002). Until they do,
> get the source from the location the maintainer gives you (for example a git bundle of the
> acceptance commit).

```sh
git clone https://github.com/argos-apm/argos.git
cd argos
corepack enable                          # once per machine (see section 1)
corepack install -g "$(node -p "require('./ui/package.json').packageManager")"
./gradlew build                          # first build: downloads Gradle, dependencies and JDK 8
pnpm -C ui install --frozen-lockfile
pnpm -C ui build && pnpm -C ui test
```

`./gradlew build` compiles every module and runs `check`: unit tests (`test`), ArchUnit rules
(`archTest`), API contract tests (`contractTest`), the agent jar verification (`verifyAgentJar`),
Spotless, Error Prone, SPDX headers and the license allow list. Gradle also builds and checks the UI
through the `:ui:pnpmInstall`, `:ui:build` and `:ui:test` tasks (pnpm from `PATH`, otherwise through
Corepack), so Node.js must be installed. For a quick Java-only iteration you can skip the UI tasks
with `-Pargos.ui.skip=true`; run the full build before opening a pull request.

For UI work, start the Vite dev server:

```sh
pnpm -C ui dev
```

The dev server has no proxy to an Argos server (see [section 12](#12-ui-development) for working
with data).

## 3. Tests

| Command | What it runs | Needs |
|---|---|---|
| `./gradlew test` | Unit tests of all modules (agent tests run on JDK 8) | — |
| `./gradlew :agent:agent-core:test` | Unit tests of one module | — |
| `./gradlew archTest` | ArchUnit rules (agent advice rules, package boundaries) | — |
| `./gradlew contractTest` | Server API contract tests | — |
| `./gradlew integrationTest` | Integration tests with Testcontainers (ClickHouse, PostgreSQL) | Docker |
| `./gradlew securityTest` | Security test suite | Docker |
| `./gradlew :agent:agent-dist:check` | Agent jar checks: shading (`checkShading`), relocated property strings (`checkRelocatedStrings`), class file major version 52 (`checkMajorVersion`) | — |
| `./gradlew verifyAgentJar` | Agent jar verification included in `check` | — |
| `pnpm -C ui test` | UI unit and component tests (Vitest) | — |
| `./gradlew traceabilityReport` | Requirement to test report from the test sources and the last JUnit results (`build/reports/traceability.md`, `.json`); runs no test | — |

`matrixTest` (compatibility matrix) and `benchTest` (overhead benchmark, `test/bench`) are not part
of the pull request workflow. Run a single combination locally with
`./gradlew matrixTest -Pmatrix.combo=<id>` or a group with `-Pmatrix.group=<group>` (needs Docker
and the JDK of that combination, 18 §2.2); the full matrix runs only on trusted self-hosted runners
(`.github/workflows/nightly.yml`), and without a runner label or a selection `matrixTest` is skipped
with a warning. The combinations are the rows of `test/matrix/matrix.yaml`; each is the task
`:agent:agent-testing:matrixTest-<ID>` and writes `build/matrix/<ID>.json`, and
`./gradlew :agent:agent-testing:matrixReport` turns the results into `docs/compatibility-matrix.md`
(test/matrix/README.md: row format, verdicts, runner preparation). On this laptop run one heavy
combination at a time inside `bash test/lock/argos-lock.sh run agent-it --purpose "<purpose>" -- ...`;
the coexistence rows need `bash test/matrix/fetch-coexist-agents.sh` first. `benchTest`,
`serverLoadTest`, `storageBench`, `soakTest` and `chaosTest` require
`-Pbench.machine-profile=<id>` and fail before building anything without it. `soakTest` (72 hours
by default; `-Psoak.duration=15m` for a short INVALID run, about 21 minutes) and `chaosTest`
(`-Pchaos.profile=short` by default, all nine scenarios in about an hour) start the real server,
PostgreSQL, ClickHouse and sample apps with the agent (Docker, about 6 GiB free memory), see
[test/soak/README.md](../test/soak/README.md) and [test/chaos/README.md](../test/chaos/README.md);
run them inside the `server-it` lock. `serverLoadTest` drives fake agents against the server,
PostgreSQL and ClickHouse (Docker); on a shared machine use `-Pserver.load.profile=smoke` (about 15
minutes, every timing verdict INVALID) and run nothing else heavy at the same time, see
[test/load/fake-agents/README.md](../test/load/fake-agents/README.md). Test layers, test IDs and
requirement tags: [test/README.md](../test/README.md).

## 4. Static checks

| Command | Checks |
|---|---|
| `./gradlew spotlessCheck` | Java formatting and SPDX headers (`./gradlew spotlessApply` fixes them) |
| `bash test/lint/check-spdx.sh` | SPDX headers in all source and script files |
| `./gradlew checkLicenses checkDevOnlyLicenses` | Dependency license allow list; test-only exceptions in [docs/licenses/dev-only.md](licenses/dev-only.md) |
| `pnpm -C ui lint` | ESLint (including `react/no-danger`) |
| `pnpm -C ui typecheck` | TypeScript in strict mode |
| `pnpm -C ui format:check` | Prettier |
| `pnpm -C ui check:i18n` | Every English i18n key exists, no orphan Korean keys |
| `pnpm -C ui check:external` | No external resources in the built bundle (run after `pnpm -C ui build`) |
| `pnpm -C ui check:licenses` | UI dependency license allow list |
| `pnpm -C ui check:licenses:dev` | UI build and test tools outside the allow list are listed (`npm:` rows) in [docs/licenses/dev-only.md](licenses/dev-only.md) |

## 5. CI checks you can run locally

Every pull request runs `.github/workflows/pr.yml`. The jobs map to these commands:

| Job | Commands |
|---|---|
| `build-java` | `./gradlew build -x integrationTest` |
| `build-ui` | `pnpm -C ui install --frozen-lockfile`, `pnpm -C ui build`, `pnpm -C ui test`, `pnpm -C ui check:external` |
| `lint` | `bash .github/scripts/check-file-modes.sh`, `./gradlew spotlessCheck`, `bash test/lint/check-spdx.sh`, `pnpm -C ui lint`, `pnpm -C ui typecheck`, `pnpm -C ui format:check`, `pnpm -C ui check:i18n`, `bash .github/scripts/check-workflows.sh`, actionlint and shellcheck (below), `bash .github/scripts/test/run-all.sh`, `REQUIRE_DOCKER=1 bash deploy/compose/test/run-all.sh` |
| `license` | `./gradlew checkLicenses checkDevOnlyLicenses`, `pnpm -C ui check:licenses`, `pnpm -C ui check:licenses:dev` |
| `agent-dist-checks` | `./gradlew :agent:agent-dist:check verifyAgentJar` |
| `integration` | `./gradlew integrationTest` |
| `security-light` | `./gradlew securityTest -Psecurity.pii.requests=2000` (nightly and the release gate: the default 10,000 PII seed requests) |
| `e2e` (4 shards, not yet required) | `./gradlew :server:bootJar :agent:agent-dist:agentJar :sample-apps:shop-boot3:sampleAppJar :sample-apps:ext-stub:sampleAppJar :sample-apps:smoke:sampleAppJar`, then `pnpm -C ui e2e:docker -- --shard=<i>/4` (stack project in the pinned Linux Playwright image, screenshots compared there); shard 1 also `pnpm -C ui exec playwright install --with-deps chromium` and `pnpm -C ui e2e:preview`. Locally `cd ui && corepack pnpm e2e` (preview + stack on the host, needs Docker, JDK 21 and, on Windows, Git Bash); details in [ui/e2e/README.md](../ui/e2e/README.md) |
| `openapi-diff` | `bash .github/scripts/check-openapi-diff.sh origin/main` |
| `changelog` | `bash .github/scripts/check-changelog.sh origin/main HEAD` |
| `dco` | `bash .github/scripts/check-dco.sh origin/main HEAD` |
| `traceability` | `./gradlew --continue checkTraceability traceabilityReport gate1Report -Pargos.ui.skip=true` (no test results in CI, so every test is NOT RUN; it fails only on catalogue and mapping differences or a required requirement without a test, [test/README.md](../test/README.md) section 6). Locally add `-Ptraceability.results=none` to see what CI sees |

Workflow linting uses the same pinned image as CI:

```sh
docker run --rm -v "$PWD:/repo:ro" -w /repo \
  rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667 -color
shopt -s globstar
docker run --rm -v "$PWD:/repo:ro" -w /repo --entrypoint shellcheck \
  rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667 \
  -x .github/scripts/*.sh .github/scripts/test/*.sh test/agent/*.sh test/lint/*.sh \
  deploy/compose/**/*.sh deploy/compose/test/container/fake-bin/* deploy/compose/test/container/fake-df/*
```

Secret scanning (also run by `.github/workflows/secrets-scan.yml`):

```sh
bash .github/scripts/run-gitleaks.sh git "origin/main..HEAD"   # your commits
bash .github/scripts/run-gitleaks.sh dir                       # the working tree
```

On Windows Git Bash, prefix the `docker run` commands with `MSYS_NO_PATHCONV=1` and use a Windows
path for the volume (for example `-v "$(cygpath -w "$PWD"):/repo:ro"`); the scripts in
`.github/scripts` do this automatically.

## 6. Running Argos locally

The single-node Docker Compose installation is in `deploy/compose/`; the operator guide is
[install/compose.md](install/compose.md). From this tree:

```sh
./gradlew :server:jibDockerBuild    # local image argos-server:<version>; -Pargos.ui.skip=true leaves the UI out
cd deploy/compose
# place tls/server.crt and tls/server.key first (guide section 4; option B creates an evaluation CA)
./argos-env-init.sh --public-url http://localhost:8080   # with sudo on a Linux Docker Engine
./argos-compose.sh up
./smoke.sh
./argos-compose.sh down --volumes   # deletes all data of the installation
```

Tests of the installation: `bash deploy/compose/test/run-all.sh` (Docker, about 40 seconds) and
`./gradlew :deploy:composeSmoke` (end to end with a real agent, about 4 minutes plus the image
build; it uses host ports 8080 and 9100 and up to about 6 GB of Docker memory, and is not part of
`check`). The demo profile is in [section 10](#10-demo-profile) and a sample application with the
agent attached by hand in [section 11](#11-a-sample-application-with-the-agent). There is no
separate development Compose file for running the server from the IDE against local databases; use
the Compose installation above, the demo profile, or the seeded E2E stack of
[section 12](#12-ui-development).

## 7. Dependency changes

The build verifies every downloaded artifact against `gradle/verification-metadata.xml`, and
`pnpm install --frozen-lockfile` refuses lockfile drift. When you add or update a Gradle dependency
(including Dependabot pull requests), regenerate the checksums and lock state in the same pull
request:

```sh
./gradlew resolveAndLockAll --write-locks --no-configuration-cache -Pargos.ui.skip=true   # lock state
pnpm -C ui install                       # updates ui/pnpm-lock.yaml for UI dependency changes
```

Regenerate the checksums with an **empty Gradle cache** in a Linux container. A warm cache misses
parent POM and BOM files that a clean CI runner downloads, and on Windows Gradle rewrites the file with
CRLF line endings:

```sh
docker run --rm -v "$PWD:/work" -w /work -e GRADLE_USER_HOME=/tmp/gradle-home eclipse-temurin:21-jdk \
  ./gradlew --no-daemon --write-verification-metadata sha256 --write-locks --no-configuration-cache \
  help resolveAndLockAll build checkDevOnlyLicenses --continue -Pargos.ui.skip=true
```

On Windows Git Bash the command above fails because MSYS rewrites the container paths (`working
directory ... is invalid`). As in section 5, disable the path conversion and pass a Windows path for
the volume:

```sh
MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -w "$PWD"):/work" -w /work \
  -e GRADLE_USER_HOME=/tmp/gradle-home eclipse-temurin:21-jdk \
  ./gradlew --no-daemon --write-verification-metadata sha256 --write-locks --no-configuration-cache \
  help resolveAndLockAll build checkDevOnlyLicenses --continue -Pargos.ui.skip=true
```

On a Windows bind mount this takes about 13 minutes (an empty cache downloads everything again and
file access through the mount is slow).

Gradle only adds entries to `gradle/verification-metadata.xml`. When a dependency is removed or
replaced, also delete its old `<component>` entries. The `protoc` and `protoc-gen-grpc-java` entries
for other platforms (origin "Maven Central, SHA-1 cross-checked") are maintained by hand: add the new
version's checksums for all five platforms when you update protobuf or gRPC.

Check the new dependency's license against the allow list in [CONTRIBUTING.md](../CONTRIBUTING.md).

Never weaken verification to get a build through: no `--dependency-verification lenient|off`, no
`org.gradle.dependency.verification` in `gradle.properties`, no `--write-verification-metadata` in a
script or workflow, and every `pnpm install` in CI keeps `--frozen-lockfile`.
`bash .github/scripts/check-dependency-verification.sh` checks this (it runs in the CI script tests).

Dependabot pull requests for Gradle fail dependency verification until a maintainer regenerates the
lock files and checksums on the Dependabot branch with the commands above, reviews that the
metadata diff only touches the bumped coordinates, and pushes a signed-off commit. The full
procedure, and the pins that are updated by hand each month, are in
[docs/security/supply-chain.md](security/supply-chain.md#dependabot-pull-requests).

## 8. IDE

- **IntelliJ IDEA**: open the repository root as a Gradle project; set the Gradle JVM to JDK 21. The
  Error Prone compiler plugin is configured by the build; no IDE plugin is required.
- **VS Code**: recommended for `ui/` with the ESLint and Prettier extensions.

## 9. Before you open a pull request

- Sign off every commit: `git commit -s` (see [CONTRIBUTING.md](../CONTRIBUTING.md)).
- Add a line under `## [Unreleased]` in [CHANGELOG.md](../CHANGELOG.md).
- Run `./gradlew build` and `pnpm -C ui build && pnpm -C ui test`.

Rules for AI coding tools are summarized in [CLAUDE.md](../CLAUDE.md); the completion criteria are in
[docs/03-definition-of-done.md](03-definition-of-done.md) (Korean).

## 10. Demo profile

The demo profile runs the server with two sample applications, their agents and generated traffic,
so every screen has data about a minute after the start. From the repository root:

```sh
./gradlew :server:jibDockerBuild :sample-apps:demo-app:jibDockerBuild :sample-apps:load:jibDockerBuild
cd deploy/compose
./argos-env-init.sh --demo                 # with sudo on a Linux Docker Engine
docker compose --profile demo up -d
cat secrets/admin_password                 # sign in as admin at http://localhost:8080
docker compose --profile demo down -v      # removes the demo and its data
```

It needs about 7.5 GB of Docker memory and ports `8080` and `9100` on `127.0.0.1`. The full
description, what each service does and the troubleshooting table are in
[install/compose.md, section 14](install/compose.md#14-demo-profile).

The end-to-end test of the demo builds the three images, starts the demo, checks the first XLog
point, the traces, the batch and the alert rules, and removes everything again:

```sh
./gradlew :deploy:demoSmoke          # about 2 minutes plus the image build; not part of check
```

`-Pdemo.smoke.enforce=true` turns the first-XLog time into a verdict (60 seconds; only meaningful on
a quiet machine) and `-Pdemo.smoke.long=true` also waits for the error-rate alert (about 9 minutes).
`ARGOS_DEMO_KEEP=1` leaves the demo running for a look. The task takes the `server-it` lock itself
([section 13](#13-shared-working-tree-locks)).

## 11. A sample application with the agent

To watch the agent outside the test suites, attach the development agent jar to the reference
sample application `shop-boot3` (JDK 17 or later) and send its data either to the FakeCollector, a
development-only collector that counts what it receives, or to a running Argos server.

```sh
./gradlew :agent:agent-dist:agentJar :sample-apps:shop-boot3:sampleAppJar :agent:agent-testing:fakeCollectorJar
mkdir -p build/dev

# 1. The FakeCollector, plaintext, on a free port; prints its totals every 10 seconds
java -jar agent/agent-testing/build/fake-collector/fake-collector.jar --port 0 \
  --ready-file build/dev/fc.ready --token-file build/dev/fc.token --report-every 10 &
# wait until build/dev/fc.ready exists; its first line is host:port

# 2. shop-boot3 with the agent, on http://127.0.0.1:8080
java -javaagent:agent/agent-dist/build/libs/argos-agent-0.0.0-SNAPSHOT.jar \
  -Dargos.app.name=shop-boot3 -Dargos.app.env=dev \
  -Dargos.server.address="$(head -n 1 build/dev/fc.ready)" -Dargos.server.tls=false \
  -Dargos.server.token-file="$PWD/build/dev/fc.token" \
  -Dargos.log.dir="$PWD/build/dev/agent-logs" \
  -jar sample-apps/shop-boot3/build/sample-app/shop-boot3.jar --server.address=127.0.0.1

# 3. Some traffic; the collector's next report counts the transactions
curl http://127.0.0.1:8080/api/orders/1
```

- Every agent setting can be given as `-Dargos.<key>`, as `ARGOS_<KEY>` or in an
  `argos-agent.yml` ([agent-config.md](agent-config.md)). The agent writes its own log to
  `build/dev/agent-logs/argos-agent.log` here, never to the application's output: the start banner
  `Argos agent <version> (commit ..., protocol 1) started in <ms> ms: app=shop-boot3 env=dev ...`,
  the plaintext WARN and `collector session established` are the lines to look for there
  (`grep -E 'started in|session established' build/dev/agent-logs/argos-agent.log`). The
  application's stdout and stderr carry no agent line by design (T-AG-060, checked by
  `PremainTimeAttachTest`); the only extra line is the JVM's own, on JDK 21 and later: one warning
  on stderr that class data sharing is limited because the agent appends to the boot class path. It
  is harmless.
- `server.tls=false` gives a WARN and is for the FakeCollector only. With `--tls --tls-dir <dir>`
  the FakeCollector uses TLS and writes its CA path as the second line of the ready file; then use
  `-Dargos.server.tls.ca-file=<that path>` instead of `-Dargos.server.tls=false`
  ([agent/agent-testing/README.md](../agent/agent-testing/README.md), "Standalone collector").
- **Against a real server** (the Compose installation of [section 6](#6-running-argos-locally) or
  the demo): create a token with `./argos-compose.sh token create --app shop-boot3 --env dev`, store
  it in a file, and point the agent at the gRPC port with TLS, as in
  [install/compose.md, section 9](install/compose.md#9-connect-the-first-agent). A demo
  installation accepts plaintext gRPC.
- The switches of the sample applications (database `SAMPLE_DB`, pool `SAMPLE_POOL`, virtual
  threads `SAMPLE_VT` and others) and the other applications (`shop-boot2`, `shop-boot4`, WAR
  variants, `shop-batch`) are described in [sample-apps/README.md](../sample-apps/README.md). PII and
  XSS seed traffic: `bash test/seed/seed-traffic.sh --url http://127.0.0.1:8080`
  ([test/seed/README.md](../test/seed/README.md)).
- Before you attach the agent to anything else, `java -jar <agent jar> --check` checks the
  configuration, the connection and the class path
  ([agent-config.md](agent-config.md#pre-flight-check---check)).

## 12. UI development

`pnpm -C ui dev` starts the Vite dev server for `ui/`. `ui/vite.config.ts` configures **no proxy**,
so pages that call `/api/v1` or open `/ws` get no data from the dev server alone. Ways to work on
the UI:

- **Component and page tests** (`pnpm -C ui test`, Vitest with jsdom) render pages with mocked API
  responses; this is where most UI behaviour is tested.
- **A seeded stack**: `pnpm -C ui e2e:stack:up` builds nothing itself; it starts the server boot jar
  of this tree with its own PostgreSQL and ClickHouse containers on random `127.0.0.1` ports, seeds
  generated data, a real agent with `shop-boot3` and three users (`admin`, `e2e-operator`,
  `e2e-viewer`), and leaves the stack running; the URL, the seed manifest and the per-run password
  files are under `ui/e2e/.stack/` (git-ignored). The server serves the UI that was built
  into its boot jar, so rebuild it after UI changes (`./gradlew :server:bootJar`, then
  `node e2e/stack/cli.ts restart-server` from `ui/`). Remove it with `pnpm -C ui e2e:stack:down`.
  The stack needs five jars, listed in [ui/e2e/README.md](../ui/e2e/README.md).
- **The demo profile** ([section 10](#10-demo-profile)) for looking at realistic data in the
  packaged UI.

Commands, i18n rules and the bundle budget: [ui/README.md](../ui/README.md).

## 13. Shared working tree locks

Several people or agents may work in one working tree at the same time. Heavy or conflicting work
is serialized with directory locks under `.gradle/argos-locks/<name>`, taken with
`test/lock/argos-lock.sh`:

| Lock | Take it for |
|---|---|
| `server-it` | anything that runs the real server or rebuilds its boot jar: `:server:integrationTest`, `:server:securityTest`, `:server:bootJar`, `:server:generateOpenApi`, `:server:jibDockerBuild`, `:deploy:composeSmoke`, `pnpm -C ui e2e:stack` and `e2e:docker`, `pnpm -C ui perf` in route mode; also a full `./gradlew build` or `check` when someone else may run the same test tasks |
| `agent-it` | agent integration runs |
| `quiet` | measurements |
| `deps` | edits of lock files and `gradle/verification-metadata.xml` |

```sh
bash test/lock/argos-lock.sh run server-it --purpose "server integration tests" -- ./gradlew :server:integrationTest
bash test/lock/argos-lock.sh status server-it     # 0 free, 1 held, 3 held by a dead owner
```

`run` takes the lock, runs the command and releases the lock when the command ends, also on failure;
it is the form to use from a shell that exits right after the command. `:deploy:composeSmoke`,
`:deploy:demoSmoke` and the E2E stack take `server-it` themselves. A lock whose owner process on the
same host is dead is cleaned up automatically; never delete someone else's lock. Two Gradle runs
of the same test task at the same time overwrite each other's result files, which is why full
builds go inside the lock too. Details and the owner file format are in the script header.

## 14. Windows notes

- **Short `TMPDIR` in POSIX form.** In Git Bash, set a temporary directory as a short POSIX path,
  for example `export TMPDIR=/c/Users/<you>/AppData/Local/Temp/argos`. A Windows form such as
  `C:/Users/...` breaks scripts that put a directory below it on `PATH` (test doubles of the shell
  test suites), because the drive colon splits the entry.
- **Long paths in Docker bind mounts.** Docker Desktop on Windows fails with an I/O error (`EIO`)
  when a non-root container reads a single file bind-mounted from a Windows path longer than about
  180 characters. Keep the repository (and `TMPDIR`) at a short path such as `C:\src\argos`; the
  Compose guide lists the symptom in its troubleshooting section.
- **Path conversion.** Git Bash rewrites Unix-looking arguments of `docker run`; prefix such commands
  with `MSYS_NO_PATHCONV=1` and pass Windows paths for volumes (sections 5 and 7).
- **Long paths in git** and **executable bits** of scripts: see section 1.

## 15. Test suites at a glance

The commands of every suite, with their options and durations, are listed in
[CLAUDE.md](../CLAUDE.md) ("Build and verification commands"); the details are in the README of
each module. The main ones:

| Suite | Command | Needs | Details |
|---|---|---|---|
| Unit, architecture, contract and static checks | `./gradlew check` | – | [test/README.md](../test/README.md) |
| Server integration (startup, ingest, query API, alerts, admin, audit) | `./gradlew :server:integrationTest` | Docker | [server/README.md](../server/README.md) |
| Server security | `./gradlew :server:securityTest` | Docker | [server/README.md](../server/README.md) |
| Agent core integration (ids, heap budget, transport, SQL timing) | `./gradlew :agent:agent-core:integrationTest` | – | [agent/agent-testing/README.md](../agent/agent-testing/README.md) |
| Agent end to end (attach per JDK, sample apps, servlet, JDBC, HTTP clients, async, metrics, commands, batch and logging) | `./gradlew :agent:agent-testing:integrationTest`, narrowed with `--tests '*jdbc*'` and similar, `-Pagent.attach.jdks=...`, `-Pagent.e2e=<app>@<jdk>,...` | JDKs by toolchain, Docker for the container and database variants | [agent/agent-testing/README.md](../agent/agent-testing/README.md) |
| Storage (schema, generator, queries on a real ClickHouse) | `./gradlew :test:storage:queries:integrationTest -Pqueries.profile=ci` | Docker | [test/storage/bench/README.md](../test/storage/bench/README.md) |
| Sample application smoke runs | `./gradlew :sample-apps:shop-boot3:sampleSmoke` | – | [sample-apps/README.md](../sample-apps/README.md) |
| UI unit and component tests | `pnpm -C ui test` | – | [ui/README.md](../ui/README.md) |
| UI end to end (real server, databases, agent) | `pnpm -C ui e2e:stack`, screenshots in `pnpm -C ui e2e:docker` | Docker, the five jars | [ui/e2e/README.md](../ui/e2e/README.md) |
| Compose installation | `bash deploy/compose/test/run-all.sh`, `./gradlew :deploy:composeSmoke` | Docker | [deploy/compose/README.md](../deploy/compose/README.md) |
| Repository and release scripts | `bash .github/scripts/test/run-all.sh`, `bash scripts/test/run-all.sh`, `bash test/release/run-all.sh` | – (some cases use Docker when present) | [RELEASING.md](../RELEASING.md) |
| Server load (fake agents) | `./gradlew serverLoadTest -Pbench.machine-profile=<id> -Pserver.load.profile=smoke` | Docker, a quiet machine | [test/load/fake-agents/README.md](../test/load/fake-agents/README.md) |
| Agent overhead benchmark | `test/bench/bench-run.sh ...`, `./gradlew :test:bench:benchTest -Pbench.machine-profile=<id>` | a dedicated machine | [test/bench/README.md](../test/bench/README.md) |
| Requirement traceability | `./gradlew traceabilityReport` (runs no test) | – | [test/README.md](../test/README.md) |

Measurements (benchmarks, load tests, timing verdicts) on a shared or busy machine are marked
INVALID and are for reference only.
