# Contributing to Argos APM

Thank you for your interest in Argos APM. The project is pre-release: interfaces change often and
there is no support yet (see [ROADMAP.md](ROADMAP.md)). This guide covers what every contribution
needs. The same rules apply to everyone: maintainers, outside contributors, and changes written with
or without AI coding tools.

## Before you start

- **Open an issue first** for anything beyond a small fix (a typo, a broken link, a one-line bug
  fix). Use the issue forms: bug report, feature proposal or compatibility report. Agreeing on the
  approach before writing code saves everyone time, and a maintainer can tell you early whether the
  change touches a core path or a public contract.
- Questions belong in [GitHub Discussions](https://github.com/argos-apm/argos/discussions) (Q&A,
  Ideas, Korean), not in issues.
- Security vulnerabilities must be reported privately; see [SECURITY.md](SECURITY.md). Never open a
  public issue or pull request for one.
- Look for issues labelled `good first issue` or `help wanted` if you are new to the code base.
- Read [GOVERNANCE.md](GOVERNANCE.md) for how decisions are made and
  [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for how we treat each other.

## Developer Certificate of Origin (DCO)

Argos APM uses the [Developer Certificate of Origin 1.1](https://developercertificate.org/) instead
of a contributor license agreement. By signing off a commit you certify that you wrote the change or
otherwise have the right to submit it under the project's open-source license (Apache-2.0).

**Every commit in a pull request must carry a `Signed-off-by` line whose name and email match the
commit author.** Git adds it for you:

```sh
git commit -s -m "Describe the change"
```

The `dco` check on every pull request fails if a commit is missing a matching sign-off. To fix
existing commits:

```sh
git commit --amend -s --no-edit          # last commit only
git rebase --signoff origin/main         # every commit on your branch
git push --force-with-lease
```

A merge commit that only joins other commits is skipped, since those commits are checked on their
own. A merge that carries content of its own (a conflict resolution with new lines, or a change
that is in none of its parents) needs a sign-off like any other commit; amend it with
`git commit --amend -s --no-edit`, or rebase onto the base branch instead of merging. Use your
real name or a consistent identity you can be contacted under; anonymous contributions cannot be
accepted.

Signed commits (GPG or SSH) are welcome but not required; the DCO sign-off is.

## Building and testing

Set up your machine with [docs/dev-setup.md](docs/dev-setup.md): JDK 21 to run Gradle (the Gradle
toolchain downloads JDK 8 and the other test JDKs), Node.js 24 with Corepack for the UI, Docker for
integration tests, and git. Gradle and pnpm do not need a global installation.

Before opening a pull request run:

```sh
./gradlew build                          # compile, unit tests, all static checks, UI build and tests
./gradlew build -Pargos.ui.skip=true     # Java only, for quick iterations
```

Run the integration tests of the area you changed as well, for example
`./gradlew :server:integrationTest`, `./gradlew :agent:agent-testing:integrationTest` or
`pnpm -C ui e2e`. They need Docker and use real databases (Testcontainers); never replace
ClickHouse or PostgreSQL with mocks in an integration test. Each module README and
[test/README.md](test/README.md) list the commands, flags and run times.

A pull request is reviewed only when all required checks are green. The checks are the same for
everyone: build and unit tests, Spotless, Error Prone, ArchUnit, ESLint, `tsc --strict`, SPDX
headers, license allow list, agent jar shading checks, integration tests, security tests, OpenAPI
compatibility, CHANGELOG entry and DCO.

## Coding conventions

The build enforces the conventions below; `./gradlew check` and `pnpm -C ui lint` show every
violation locally.

| Area | Tool and rule |
|---|---|
| Java formatting | Spotless with google-java-format. `./gradlew spotlessApply` fixes formatting and adds the SPDX header. |
| Java static analysis | Error Prone with warnings as errors. |
| Agent rules | ArchUnit (`./gradlew :agent:agent-testing:archTest`): Advice code has no lambdas, `skipOn`, `readOnly = false`, `synchronized`, blocking calls or reflection lookups; package boundaries; log arguments on an allow list. |
| Agent target | Java 8 bytecode (`--release 8`) for everything in the agent jar; all third-party classes relocated under `io.argos.agent.shaded`. |
| UI | ESLint (including `react/no-danger`), TypeScript `strict`, Prettier (`pnpm -C ui format:check`), i18n keys (`pnpm -C ui check:i18n`), no external resources (`pnpm -C ui check:external`). |
| Generated files | `docs/api/openapi.yaml` (`./gradlew :server:generateOpenApi`) and `ui/src/api/generated/schema.ts` (`pnpm -C ui gen:api`) are generated; never edit them by hand, regenerate and commit them with the change. |
| Shell scripts | `#!/usr/bin/env bash` with `set -euo pipefail` (or POSIX `sh` with `set -eu` where the target system may lack bash), checked by ShellCheck, executable bit (`100755`) recorded in git (`bash .github/scripts/check-file-modes.sh`). |

Every new source, script or configuration file starts with an SPDX header (`bash test/lint/check-spdx.sh`
checks all of them):

| Files | Header |
|---|---|
| Java, CSS | `/* SPDX-License-Identifier: Apache-2.0 */` (Java uses the exact form Spotless writes) |
| Kotlin, Gradle Kotlin DSL, proto, TypeScript, JavaScript | `// SPDX-License-Identifier: Apache-2.0` |
| Shell, YAML, TOML, properties, Python | `# SPDX-License-Identifier: Apache-2.0` (after the shebang line, if any) |
| SQL | `-- SPDX-License-Identifier: Apache-2.0` |
| HTML | `<!-- SPDX-License-Identifier: Apache-2.0 -->` |

Language rules:

- Code identifiers, comments, log messages, error messages, user documentation, README files and
  the CHANGELOG are in English. User-visible UI strings come from i18n resources (English is the
  source, Korean is a translation); never hard-code them in components.
- Never assemble collected data (SQL, exceptions, URLs, user ids, headers, thread dumps) into HTML
  strings; no `dangerouslySetInnerHTML` or `innerHTML`.
- Never log, store or print tokens, passwords or channel URLs in plain text, and never keep tokens
  in `localStorage`.

## Definition of done checklist

This is the machine-checkable part of the common definition of done
([docs/03-definition-of-done.md](docs/03-definition-of-done.md), section 2) that every pull request
must meet. The pull request template repeats it.

- [ ] The build and the checks of the changed modules pass (`./gradlew :<module>:check`,
      `pnpm -C ui test`), and all existing tests still pass.
- [ ] Every new behaviour has automated tests that check the acceptance criteria **directly**.
      "It works" without a test is not accepted.
- [ ] No existing test was weakened, skipped or deleted to make the change pass. If a criterion
      itself must change, say so in the issue and let a maintainer decide.
- [ ] Zero new warnings: Spotless, Error Prone, ArchUnit, ESLint, `tsc --strict`.
- [ ] Every new file has the SPDX header; borrowed code keeps its original header and is listed in
      `NOTICE`.
- [ ] New dependencies are on the license allow list and recorded as described below.
- [ ] No secrets or tokens in the change (the `gitleaks` scan is clean).
- [ ] Every input from users, agents or external systems has a size and count limit, and the
      rejection path is tested.
- [ ] No new outbound network calls except to targets the operator configures (collector server,
      alert channels, SMTP). Argos has no telemetry, crash reports or update checks.
- [ ] Documentation is updated where behaviour, configuration or APIs changed (configuration
      reference, OpenAPI specification, user documentation), and `CHANGELOG.md` has an entry.
- [ ] Newly collected or stored fields are added to the data classification table of the
      architecture document; fields that may hold personal data are not collected, or masked, by
      default.
- [ ] Changes to a public contract follow [docs/compatibility.md](docs/compatibility.md).

## Pull requests

- **Keep pull requests small and focused**: one problem per pull request, one instrumentation
  module per pull request. As a guide, aim for a diff a reviewer can read in one sitting (a few
  hundred changed lines excluding generated files and test fixtures); split larger work into a
  series that builds and passes on its own.
- Link the issue (`Closes #123`) and fill in every section of the pull request template, including
  the test evidence: the commands you ran and their results.
- Do not mix refactoring, formatting or dependency updates with a behaviour change.
- Review flow:
  1. The required checks run on GitHub-hosted runners. Workflows of pull requests from outside
     collaborators run after a maintainer approves the run.
  2. Labels are added automatically from the changed paths (`area/*`, `needs-matrix`); a maintainer
     adds `breaking-change`, `no-changelog` or `needs-info` when needed.
  3. A maintainer reviews the change. Core paths are always read by a maintainer (see
     [Core paths](#core-paths-needs-matrix)).
  4. Address review comments with new commits; maintainers may ask you to squash or rebase before
     merging. `main` keeps a linear history.
  5. A maintainer merges. Only people approve and merge pull requests.
- A pull request waiting for the author for 30 days without activity may be closed; you are welcome
  to reopen it when you continue.

## Changelog

[CHANGELOG.md](CHANGELOG.md) follows [Keep a Changelog 1.1](https://keepachangelog.com/en/1.1.0/).

- Add one line under `## [Unreleased]` in the right group (`Added`, `Changed`, `Deprecated`,
  `Removed`, `Fixed`, `Security`), written for users, with the issue number.
- Changes without user impact (tests, internal refactoring, CI) can skip the entry: ask a
  maintainer for the `no-changelog` label, then re-run the failed `changelog` job.
- Changes to a public contract (configuration keys, environment variables, REST API, WebSocket
  frames, metric names, webhook payloads, CLI arguments and exit codes, propagation headers, MDC
  keys; full list in [docs/compatibility.md](docs/compatibility.md)) need a `Changed` or
  `Deprecated` entry. If compatibility breaks, the pull request also needs the `breaking-change`
  label; the `openapi-diff` check fails a breaking REST change without it. Snapshot tests of the
  configuration keys and metric names fail until you update the committed snapshot in the same
  pull request, on purpose.
- Security fixes are recorded under `Security` when the advisory is published (see
  [SECURITY.md](SECURITY.md)).

## Adding an instrumentation module

Instrumentation modules live under `agent/agent-instrumentation/<name>`. Read the agent design
document ([docs/10-agent-design.md](docs/10-agent-design.md), Korean) and an existing module such as
[agent/agent-instrumentation/jdbc](agent/agent-instrumentation/jdbc/README.md) first.

1. **One library or package per pull request.** Open an issue first that names the library, the
   version range and the data the module will record.
2. **Witness first.** The module declares witness classes and methods (resource lookups only) so
   that it activates only when the target library is present in a supported version.
3. **Fail-safe.** Advice code must never block an application thread, change an exception or a
   return value, wrap application objects or create unbounded collections. Errors inside Advice are
   swallowed and counted; repeated transform errors disable the module automatically. Tests inject
   failures and prove the application still answers normally.
4. **Module README.** The README has a `Supported library versions` section with a
   `Version range and witness` table: library, supported range, minimum and maximum verified
   versions, and the boundary tests. `./gradlew :agent:agent-testing:test --tests '*ModuleReadmeLint*'`
   checks that the table exists, that the witness matches the module source and that the named test
   classes and tasks exist.
5. **Boundary version tests.** Run the real library at the minimum and maximum verified versions
   with the real tracer (module test source sets per version range), plus an end-to-end test on a
   sample application in `agent/agent-testing`.
6. **Transform baseline.** New or changed instrumentation changes the list of transformed classes.
   Re-record it with `-Pagent.baseline.record=true` (see
   [agent/agent-testing/README.md](agent/agent-testing/README.md)) and include the baseline diff in
   the pull request so reviewers can see exactly which classes are transformed.
7. **Data.** New fields are added to the data classification table and are not collected, or are
   masked, by default if they can contain personal data. Settings are added to the configuration
   reference ([docs/agent-config.md](docs/agent-config.md)).

A new module is not a core-path change by itself (it gets `area/agent`), but a maintainer still runs
its library versions in the compatibility matrix before it is merged. Changes it needs in
`agent/agent-core` or `agent/agent-bootstrap` are core-path changes and get the `needs-matrix`
label.

## Dependencies and borrowed code

- Dependencies that ship in Argos artifacts must be licensed under a license on the allow list in
  [build-logic/allowed-licenses.json](build-logic/allowed-licenses.json): Apache-2.0, MIT, MIT-0,
  BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, PostgreSQL, Unicode-DFS or CC0-1.0. GPL, LGPL, AGPL, SSPL
  and BSL are never allowed. `./gradlew checkLicenses` fails the build otherwise; changing the list
  needs a maintainer decision recorded as an ADR.
- Test- and sample-only dependencies with other licenses must be listed in
  [docs/licenses/dev-only.md](docs/licenses/dev-only.md) and must never reach a shipped artifact
  (`./gradlew checkDevOnlyLicenses`).
- Dependency versions are locked and verified. After a dependency change, update the lock files and
  `gradle/verification-metadata.xml` as described in [docs/dev-setup.md](docs/dev-setup.md), and
  state the name, version, license and reason in the pull request. Never switch verification to
  lenient or off and never install UI packages without `--frozen-lockfile`;
  `bash .github/scripts/check-dependency-verification.sh` fails such a change.
- Routine updates come from Dependabot once a month as grouped pull requests, security updates as
  soon as an advisory is published. A maintainer completes Gradle updates (lock files and checksums)
  on the Dependabot branch; see
  [docs/security/supply-chain.md](docs/security/supply-chain.md#dependabot-pull-requests).
- A dependency added to an agent module must be relocated by the shading rules and appear in the
  SBOM and the version manifest; attach the `jar tf` listing and the
  `bash test/agent/check-shading.sh <agent jar>` result to the pull request.
- Code copied or adapted from other open-source projects must be under a compatible license, keep
  its original copyright header in the file, and be recorded in [NOTICE](NOTICE) under "Third-party
  source code adapted in this repository" (the file(s), the origin project and URL, the version or
  commit, and the license). `bash test/lint/check-notice.sh` checks that every file with a foreign
  copyright header or a borrowed-code marker is listed. Code, assets or text from commercial products
  or from projects under incompatible licenses must not be copied; they may only be used as a
  reference.

## AI-assisted contributions

Contributions created with AI coding tools are welcome. Argos itself is developed this way (see
[GOVERNANCE.md](GOVERNANCE.md)), and [CLAUDE.md](CLAUDE.md) summarizes the repository rules for AI
coding tools. The rules are the same as for any other contribution:

- **You are responsible.** The person who submits the change signs off the DCO for every commit and
  answers for all of it, including the parts a tool generated. Read and understand the code before
  you submit it.
- **Tests prove the acceptance criteria.** Generated code needs automated tests that check the
  behaviour the issue asks for directly. A report that something "works" without a test that shows
  it is not accepted.
- **Never weaken a test to make it pass.** Do not loosen assertions, raise limits, skip or delete
  tests. If a criterion is wrong, stop and raise it in the issue.
- **Licenses.** New dependencies stay on the allow list
  ([build-logic/allowed-licenses.json](build-logic/allowed-licenses.json)). Generated code that
  reproduces third-party code follows the borrowed-code rules above; do not submit output you
  cannot attribute.
- **No outbound network calls.** Do not add telemetry, usage statistics, update checks or calls to
  any target other than those the operator configures.
- **Stay in scope.** Change only what the issue covers. Propose out-of-scope improvements in the
  pull request description or a new issue instead of including them.
- **Design decisions.** If the change would alter an architecture decision or a design document,
  stop and discuss it in the issue first; accepted changes are recorded as an ADR in
  [docs/adr/](docs/adr/).
- **Disclose AI use.** Say in the pull request (section "AI assistance") whether and how AI tools
  were used.
- **Same review.** AI-assisted pull requests go through the same required checks and the same human
  review. AI tools may help reviewers, but only people approve and merge.
- **Keep secrets out of prompts.** Do not send tokens, passwords, personal data or code you may not
  share to an AI service.

## Core paths (`needs-matrix`)

Changes to core paths (agent bootstrap and core, agent packaging, protocol, server collection
pipeline and WAL, storage and migrations, authentication, security and tokens, secrets, remote
commands, privacy, webhook delivery) get the `needs-matrix` label automatically from
[.github/labeler.yml](.github/labeler.yml), and [.github/CODEOWNERS](.github/CODEOWNERS) requests a
maintainer review. Changes to CI, the build, licensing and the release supply chain (`keys/`,
`scripts/`, release verification) are also reviewed by a maintainer through CODEOWNERS, without the
`needs-matrix` label. For `needs-matrix` pull requests a maintainer then:

1. reads the code of the change (not only the tests);
2. brings the change to a trusted branch and runs the nightly compatibility matrix and the overhead
   benchmark on the trusted self-hosted runners (fork pull requests never run on them);
3. links the results in the pull request and merges only when both pass.

Expect these pull requests to take longer. You can run a single matrix combination locally with
`./gradlew matrixTest -Pmatrix.combo=<id>` (see [docs/dev-setup.md](docs/dev-setup.md)).

## Response times

Maintainers aim to give a first response to new issues and pull requests within **7 days**. This is
a best-effort target of a community project, not a service level. Issues labelled `needs-info` are
closed after 30 days without the requested information; they can be reopened at any time. If you
have not heard back after 7 days, a friendly ping on the issue or pull request is welcome.

## License

By contributing you agree that your contributions are licensed under the
[Apache License, Version 2.0](LICENSE).
