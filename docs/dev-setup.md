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
| Docker | 24+ | Testcontainers (integration tests) and, later, Docker Compose. Give it at least 4 GB of memory. |

You do not need to install Gradle or pnpm globally: use the Gradle wrapper (`./gradlew`) and Corepack.

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
and the JDK of that combination, 18 §2.2); the full matrix runs only on trusted self-hosted runners,
and without a runner label or a selection `matrixTest` is skipped with a warning. `benchTest`,
`serverLoadTest`, `storageBench`, `soakTest` and `chaosTest` require
`-Pbench.machine-profile=<id>` and fail before building anything without it; `serverLoadTest`,
`soakTest` and `chaosTest` are placeholders until WO-29 and WO-52. Test layers, test IDs and
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
| `lint` | `bash .github/scripts/check-file-modes.sh`, `./gradlew spotlessCheck`, `bash test/lint/check-spdx.sh`, `pnpm -C ui lint`, `pnpm -C ui typecheck`, `pnpm -C ui format:check`, `pnpm -C ui check:i18n`, `bash .github/scripts/check-workflows.sh`, actionlint and shellcheck (below), `bash .github/scripts/test/run-all.sh` |
| `license` | `./gradlew checkLicenses checkDevOnlyLicenses`, `pnpm -C ui check:licenses`, `pnpm -C ui check:licenses:dev` |
| `agent-dist-checks` | `./gradlew :agent:agent-dist:check verifyAgentJar` |
| `integration` | `./gradlew integrationTest` |
| `security-light` | `./gradlew securityTest` |
| `openapi-diff` | `bash .github/scripts/check-openapi-diff.sh origin/main` |
| `changelog` | `bash .github/scripts/check-changelog.sh origin/main HEAD` |
| `dco` | `bash .github/scripts/check-dco.sh origin/main HEAD` |

Workflow linting uses the same pinned image as CI:

```sh
docker run --rm -v "$PWD:/repo:ro" -w /repo \
  rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667 -color
docker run --rm -v "$PWD:/repo:ro" -w /repo --entrypoint shellcheck \
  rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667 \
  -x .github/scripts/*.sh .github/scripts/test/*.sh test/agent/*.sh test/lint/*.sh
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

Not available yet. The Docker Compose setup (`docker compose -f deploy/compose/compose.yaml --profile
demo up`), running the server with `./gradlew :server:bootRun` against local ClickHouse and PostgreSQL
containers, and running a sample application with the agent attached are added in later work and will
be documented here.

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
