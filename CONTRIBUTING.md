# Contributing to Argos APM

Thank you for your interest in Argos APM. The project is pre-release: interfaces change often and
there is no support. This guide covers what every contribution needs today; it will be extended
before the public beta.

## Before you start

- **Open an issue first** for anything beyond a small fix (bug report, feature proposal or
  compatibility report). Agreeing on the approach before writing code saves everyone time.
- Questions belong in GitHub Discussions, not in issues.
- Security vulnerabilities must be reported privately; see [SECURITY.md](SECURITY.md).
- Maintainers aim to give a first response to issues and pull requests within 7 days.

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

## Building and testing

Set up your machine with [docs/dev-setup.md](docs/dev-setup.md). Before opening a pull request run:

```sh
./gradlew build
pnpm -C ui build && pnpm -C ui test
```

A pull request is reviewed only when all required checks are green. The checks are the same for
everyone: build and unit tests, Spotless, Error Prone, ArchUnit, ESLint, `tsc --strict`, SPDX
headers, license allow list, agent jar shading checks, integration tests, security tests, OpenAPI
compatibility, CHANGELOG entry and DCO.

## What a change must include

- Automated tests that check the behaviour directly. "It works" without a test is not accepted, and
  existing tests must never be weakened to make a change pass.
- No new static-analysis warnings and no formatting drift (`./gradlew spotlessApply` fixes Java
  formatting).
- An SPDX header in every new source or script file:
  `/* SPDX-License-Identifier: Apache-2.0 */` (Java), `// SPDX-License-Identifier: Apache-2.0`
  (TypeScript/JavaScript), `# SPDX-License-Identifier: Apache-2.0` (shell, YAML, TOML, properties).
- A line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md), or ask a maintainer for the
  `no-changelog` label (for changes with no user impact) and re-run the failed `changelog` job.
- Documentation updates when behaviour, configuration or APIs change.
- Size and count limits on every input from users, agents or external systems.
- No outbound network calls (Argos has no telemetry) and no credentials or tokens in the repository.
- For changes to public contracts (configuration keys, REST API, metric names, webhook payloads,
  propagation headers, MDC keys): a CHANGELOG `Changed` or `Deprecated` entry and the
  `breaking-change` label when compatibility breaks.
- Keep pull requests focused and reviewable; one instrumentation module per pull request.

## Dependencies and borrowed code

- Dependencies that ship in Argos artifacts must be licensed under Apache-2.0, MIT, BSD-2-Clause,
  BSD-3-Clause, ISC, 0BSD, PostgreSQL, Unicode-DFS or CC0-1.0. The build fails otherwise.
- Test- and sample-only dependencies with other licenses must be listed in
  [docs/licenses/dev-only.md](docs/licenses/dev-only.md) and must never reach a shipped artifact.
- Code copied or adapted from other open-source projects must be under a compatible license, keep
  its original copyright header in the file, and be recorded in [NOTICE](NOTICE) under "Third-party
  source code adapted in this repository" (the file(s), the origin project and URL, the version or
  commit, and the license). Code, assets or text from commercial products or from projects under
  incompatible licenses must not be copied.

## AI-generated code

Contributions created with AI coding tools are welcome. The person who submits them signs off the
DCO and is responsible for them like for any other code. AI-assisted pull requests go through the
same tests and human review. Please mention AI assistance in the pull request description.

## Core paths (`needs-matrix`)

Changes to core paths (agent bootstrap and core, agent packaging, protocol, server collection
pipeline, storage and migrations, security and tokens, remote commands, masking, webhook delivery)
get the `needs-matrix` label automatically. A maintainer then runs the nightly compatibility matrix
and overhead benchmark on a trusted branch before merging, and reads the code. Expect these pull
requests to take longer.

## License

By contributing you agree that your contributions are licensed under the
[Apache License, Version 2.0](LICENSE).
