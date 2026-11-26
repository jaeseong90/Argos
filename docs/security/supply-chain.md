# Supply chain controls

How Argos keeps its dependencies, build and CI trustworthy: what runs where, how self-hosted runners
are kept away from untrusted code, how Dependabot pull requests are handled, which pins are updated
by hand, and which GitHub settings a maintainer applies. Requirements: SEC-70, SEC-71, SEC-73 and
O-S04, O-S05 of `docs/06-security-requirements.md` and `docs/01-product-plan.md`; design: sections
10.1 to 10.5 of `docs/16-security-design.md` and sections 6.1 to 6.8 of
`docs/19-repo-build-release-design.md` (Dependabot cadence: ADR-0003 C-30).

## What runs where

All workflows below run on GitHub-hosted runners unless the table says otherwise. Every workflow
starts with read-only permissions; a job gets a write scope only when
`.github/scripts/check-workflows.sh` lists it with a reason (rule W4).

| When | Workflow and job | What it checks |
|---|---|---|
| Every pull request, every push to `main` and `release/**` | `pr.yml` (build, lint, license, agent jar, integration, e2e, `security-light`, `openapi-diff`, `changelog`, `dco`) | build and tests, workflow policy (`check-workflows.sh`), dependency verification policy (`check-dependency-verification.sh` through the script tests), license allow list, SPDX headers |
| same | `pr.yml` job `sbom-scan` | CycloneDX SBOMs of agent, server and UI scanned with Trivy (Critical and High with a fix fail unless listed in [cve-exceptions.md](cve-exceptions.md)); the server image is scanned too |
| same | `codeql.yml` job `analyze` | CodeQL for Java/Kotlin and JavaScript/TypeScript |
| same | `secrets-scan.yml` job `gitleaks` | gitleaks on the new commits and on the checked-out tree |
| After `pr.yml` is requested | `pr-labeler.yml` (`workflow_run`, base repository privileges, never checks out pull request code) | path labels `area/*` and `needs-matrix` |
| Weekly (Monday) | `codeql.yml`, `secrets-scan.yml` | CodeQL on the default branch; gitleaks on the **full history** |
| Weekly (Tuesday) | `sbom-rescan.yml` | rescan of the current SBOMs and images and of the latest release's SBOMs with the newest vulnerability database; fails on exceptions past their review date; a failure opens or updates a `kind/security` issue |
| Weekly (Saturday), manual | `bench.yml` job `bench` on **self-hosted** `argos-bench` runners, then a GitHub-hosted `publish` job | overhead benchmark; the report pull request is opened by the GitHub-hosted job |
| Daily, manual (planned, WO-50) | `nightly.yml` on **self-hosted** `argos-matrix` runners | compatibility matrix, full `securityTest` |
| Tag `v*` | `release.yml` | build, reproducibility, SBOM scan of the release assets, signing, draft release |
| Monthly, and immediately for advisories | Dependabot (`.github/dependabot.yml`) | grouped version updates; security updates as soon as an advisory affects a dependency |

Reproduce the local parts:

```sh
bash .github/scripts/check-workflows.sh                  # workflow policy W1..W8
bash .github/scripts/check-dependency-verification.sh    # verification metadata and lock files
bash .github/scripts/test/run-all.sh                     # all CI script tests (REQUIRE_DOCKER=1 for the Docker cases)
bash .github/scripts/run-gitleaks.sh git                 # full-history secret scan (Docker)
```

## Runner isolation

Fork pull requests run only on GitHub-hosted runners, and no workflow uses `pull_request_target`.
`.github/scripts/check-workflows.sh` enforces this on every pull request:

- W1: `pull_request_target` appears in no workflow.
- W2: a workflow that a pull request, `workflow_run` or `workflow_call` can start runs every job on
  `ubuntu-latest` and never mentions `self-hosted`.
- W8: a self-hosted job (any `runs-on` label that is not a GitHub-hosted image, for example
  `self-hosted`, `argos-matrix`, `argos-bench-*`, or a runner `group:`) is allowed only in a workflow
  triggered by `push` (branches `main` and `release/**`, or tags `v*`), `schedule` and
  `workflow_dispatch`. The job needs a job-level `if:` that is exactly one of these trusted-ref guards
  (`TRUSTED_REF_GUARDS` in the script), so a manual run on a feature branch never reaches the runner:

  ```
  github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/heads/release/')
  startsWith(github.ref, 'refs/tags/v')
  ```

  Its `actions/checkout` sets `persist-credentials: false` and does not override `ref:` or
  `repository:`; a job that uses secrets declares `environment: trusted`; and it has no write
  permission (a write, such as opening the benchmark pull request, happens in a GitHub-hosted
  follow-up job).

The simulation shows which jobs an event would start, with the same parser:

```sh
bash .github/scripts/check-workflows.sh --simulate pull_request --fork
bash .github/scripts/check-workflows.sh --simulate workflow_dispatch --ref refs/heads/feature/x
bash .github/scripts/check-workflows.sh --simulate schedule
```

Each line is `<workflow>  <job>  <github-hosted|self-hosted>  <runs|skipped:<reason>|may-run>`. For a
fork pull request and for a manual run on a feature branch no self-hosted job may show `runs` or
`may-run`; `.github/scripts/test/runner-isolation.test.sh` asserts this on the repository and on
modified copies (T-BLD-027, proposed).

On GitHub, in addition: self-hosted runners are registered in the organization runner group
`argos-trusted`, which only this repository's trusted workflows may use; they are ephemeral (a clean
work directory per job) and sit in an isolated network segment; secrets live only in the `trusted`
environment, never in runner environment variables.

## Dependency verification

- Gradle verifies every downloaded artifact and POM against `gradle/verification-metadata.xml`
  (`verify-metadata` true, SHA-256). A changed or unlisted artifact fails the build
  (`build-logic/src/test/kotlin/io/argos/buildlogic/DependencyVerificationFunctionalTest.kt`,
  T-SEC-73a proposed).
- Every Gradle configuration is locked (`gradle.lockfile`); the UI installs with
  `pnpm install --frozen-lockfile`, which refuses lock file drift.
- `.github/scripts/check-dependency-verification.sh` fails when the metadata is weakened (no
  `verify-metadata`, an artifact without SHA-256, a trusted artifact without a listed reason) or when a
  build file, script or workflow switches verification to `lenient` or `off`, regenerates the metadata,
  or installs UI packages without `--frozen-lockfile` (V1 to V5 in the script header). Documentation
  is not checked: it shows the maintainer commands below.
- Regenerating the metadata is never done by CI or by a script. It is a maintainer step, with an
  empty Gradle cache in a Linux container ([docs/dev-setup.md](../dev-setup.md) section 7), whose diff
  is reviewed like code.

## Dependabot pull requests

`.github/dependabot.yml` opens grouped version updates once a month (one pull request for the minor
and patch updates of each ecosystem: Gradle, npm, GitHub Actions, the sample application
Dockerfiles; majors separately) after a 7-day cooldown. Security updates do not wait for the
schedule: with "Dependabot alerts" and "Dependabot security updates" enabled, GitHub opens them as
soon as an advisory affects a dependency, grouped per ecosystem.

Dependabot cannot run the Linux-container regeneration of the verification metadata, so a Gradle
pull request fails its build by design until a maintainer completes it:

1. Fetch the Dependabot branch and check it out locally (`git fetch origin <branch>` and
   `git switch <branch>`).
2. Regenerate the lock state and the checksums with the commands of
   [docs/dev-setup.md](../dev-setup.md) section 7 (`resolveAndLockAll --write-locks`, then the
   `--write-verification-metadata sha256` run in the `eclipse-temurin` container with an empty Gradle
   cache). Remove the `<component>` entries of the versions that were replaced.
3. Review the diff: `gradle/verification-metadata.xml` and the lock files may change only for the
   coordinates the pull request bumps and their new transitive dependencies. Any other changed
   checksum is a stop signal: do not commit it, find out why first.
4. Check the licenses of new modules (`./gradlew checkLicenses checkDevOnlyLicenses`) and, for an
   agent module, the shading result.
5. Commit with `Signed-off-by` (`git commit -s`) and push to the same branch. The `dco` check
   exempts only the commits Dependabot itself created.

For npm pull requests Dependabot updates `ui/pnpm-lock.yaml` itself, and the frozen install verifies
it. A group that bumps `@playwright/test` also needs `PLAYWRIGHT_IMAGE` in `ui/e2e/stack/images.ts`
updated to the same version (`images.test.ts` fails otherwise). GitHub Actions updates replace the
commit SHA and its `# vX.Y.Z` comment; `check-workflows.sh` (W3) keeps both required.

A maintainer adds a CHANGELOG line (always for security fixes) or the `no-changelog` label.

## Pins outside Dependabot (monthly, by hand)

Some pins live in files Dependabot does not read or must not change alone. A maintainer reviews them
once a month, together with the Dependabot pull requests, and updates each with its tests:

| Pin | Where | Kept in step with |
|---|---|---|
| Trivy image | `test/release/trivy-scan.sh` | the release and pull request `sbom-scan` jobs |
| gitleaks image | `.github/scripts/run-gitleaks.sh` | `.github/scripts/test/gitleaks-config.test.sh` |
| actionlint image | `.github/workflows/pr.yml` (lint job), CLAUDE.md | |
| buf image | `.github/scripts/check-proto.sh` | |
| oasdiff image | `.github/scripts/check-openapi-diff.sh` | |
| Playwright image | `ui/e2e/stack/images.ts` | `@playwright/test` in `ui/package.json` |
| Jib base image of the server and demo images | `build-logic/src/main/kotlin/io/argos/buildlogic/server/ServerImage.kt` | the E2E stack reads it from there |
| ClickHouse and PostgreSQL images | `deploy/clickhouse/image.properties`, `deploy/postgres/image.properties` | `deploy/compose/compose.yaml` (`deploy/compose/test/compose-files.test.sh` requires equality) |
| Testcontainers images of tests | test sources (for example the JDBC suite's database images) | |
| JDK base images of the sample Dockerfiles | `ARG JDK_IMAGE` defaults in `sample-apps/*/Dockerfile` | covered here as well, in case Dependabot does not resolve an `ARG` default |

Resolve a new digest from the multi-architecture index (for example
`docker buildx imagetools inspect <image>:<tag>`) and note the date in the comment next to the pin.
An air-gapped CI without Dependabot follows the same list weekly (16 section 10.2).

## GitHub settings (applied by a maintainer)

`.github/scripts/branch-protection.sh` applies the scripted settings; run it with `--dry-run` first.
Checklist:

- [ ] `branch-protection.sh`: rulesets for `main` and `release/*`, Discussions, private
      vulnerability reporting, approval for workflows of all outside contributors, Dependabot alerts,
      Dependabot security updates, secret scanning with push protection, read-only default
      `GITHUB_TOKEN`.
- [ ] `create-labels.sh`: the label set, including `dependencies` and `kind/security`.
- [ ] Organization runner group `argos-trusted` restricted to this repository's trusted workflows;
      self-hosted runners (`argos-matrix`, `argos-bench-*`) registered only there, ephemeral.
- [ ] Environment `trusted` with required reviewers holds the secrets of self-hosted jobs; environment
      `release` for the release workflow.
- [ ] "Allow GitHub Actions to create and approve pull requests": a maintainer decision.
      `branch-protection.sh` leaves it unchanged. The weekly benchmark report pull request
      (`bench.yml`, job `publish`, `gh pr create` with the workflow token) needs it; while it is off,
      that step fails after the report branch `bench/weekly-*` has been pushed, and a maintainer
      opens the pull request from that branch.
- [ ] Code scanning (CodeQL) results appear for the default branch after the first weekly run.

`sbom-scan` and the four `e2e` shards become required checks only after their first green run
(`--include-sbom-scan`, `--include-e2e`; ADR-0010 C-249 (2)).

## In-house self-hosted runners (docs/19 open item 10)

Assumption: the in-house self-hosted runners for the compatibility matrix and the benchmark can open
outbound HTTPS connections to GitHub (`github.com`, `api.github.com`, the Actions service and artifact
hosts), which the runner application needs to receive jobs. Nothing connects to them from outside.

Fallback when the network policy does not allow it: the matrix and the benchmark run in the in-house
CI from a checkout of `main` or `release/*`, and only their results (the compatibility status table,
`docs/bench/` reports) are committed through a normal pull request. This is still to be confirmed by
a person for the in-house network.
