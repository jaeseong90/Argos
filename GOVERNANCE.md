# Governance

This document describes how Argos APM is run while the project is young. It will be revised as
the contributor base grows; changes to it are made by pull request like any other change.

## Maintainers

Argos APM starts with a **single-maintainer model**: the maintainer makes final decisions after
discussion in issues and pull requests.

| Maintainer | GitHub |
|---|---|
| Project founder | [@jaeseong90](https://github.com/jaeseong90) |

Maintainers triage issues, review and merge pull requests, cut releases, respond to security
reports and keep the project documents up to date. Only people approve and merge pull requests.

### Becoming a maintainer

A contributor with a sustained record of meaningful merged pull requests and reviews may be invited
by the existing maintainers. The invitation and its acceptance are recorded in this file by pull
request.

### Stepping down and removal

Maintainers may step down at any time. A maintainer who has been inactive for 12 months is moved to
an emeritus list after a public notice in Discussions.

### Continuity

Project assets (repository, container registry, Maven Central namespace, signing keys, domain)
belong to the GitHub organization, not to individuals. The organization has at least two owners with
two-factor authentication enforced, so the project can continue if one person becomes unavailable.

## Decisions

- Day-to-day decisions happen in issues and pull requests.
- Architectural and project-level decisions are recorded as Architecture Decision Records in
  [docs/adr/](docs/adr/). An ADR is proposed by pull request and accepted by a maintainer.
- **The license will not change.** Argos APM is licensed under Apache-2.0
  ([ADR-0001](docs/adr/0001-license-apache-2.0.md)) and contributions are accepted under the
  Developer Certificate of Origin; there is no contributor license agreement that would allow
  relicensing.

## How the project is developed

Argos APM is planned, specified and reviewed by people and implemented with the help of an AI
coding agent working from written work orders. The definition of done, the tests and the review rules
are the same for AI-assisted and human-written changes and for internal and external contributions.
Core paths (agent core, collection pipeline, authentication and tokens, remote commands, migrations,
masking, webhook delivery) are always read by a maintainer before merging.

AI tools may prepare changes and help with reviews, but people decide: maintainers accept or reject
work, approve and merge pull requests, push release tags and publish releases. The rules for
AI-assisted contributions are in [CONTRIBUTING.md](CONTRIBUTING.md#ai-assisted-contributions).

## Repository settings

GitHub settings are part of the project's controls, so they are kept as reviewed scripts and lists
in this repository and applied by a maintainer with administrator rights. Nothing in CI changes
repository settings. Run the scripts with `--dry-run` first to see the exact `gh` calls.

| Setting | How it is applied |
|---|---|
| Branch protection (rulesets) for the default branch and `release/*` | `.github/scripts/branch-protection.sh [--repo OWNER/NAME] [--approvals N] [--include-e2e] [--include-sbom-scan] [--list-checks] [--dry-run]` |
| Discussions enabled, Private Vulnerability Reporting enabled, approval required before workflows of outside collaborators run | the same script |
| Dependabot alerts and security updates, secret scanning with push protection, read-only default `GITHUB_TOKEN` for workflows | the same script ("Allow GitHub Actions to create and approve pull requests" is left unchanged: a maintainer decision, see [docs/security/supply-chain.md](docs/security/supply-chain.md#github-settings-applied-by-a-maintainer)) |
| Dependabot version updates (monthly, grouped) | [.github/dependabot.yml](.github/dependabot.yml) |
| Label set (`area/*`, `kind/*`, `needs-matrix`, `needs-info`, `breaking-change`, `no-changelog`, `backport/X.Y`, ...) | `.github/scripts/create-labels.sh [--repo OWNER/NAME] [--backport X.Y]... [--dry-run]` |
| Discussions categories | by hand in the repository settings (GitHub has no API for categories) |
| Environments `release` and `trusted` with required reviewers, runner groups | by hand in the organization and repository settings |

### Branch protection

`main` and `release/*` accept changes only through pull requests: the required status checks of
the pull request workflows must pass, approval by a code owner is required, approvals are dismissed
when new commits are pushed, conversations must be resolved, history stays linear, force pushes and
branch deletion are blocked, and nobody bypasses the rules, administrators included. Commits must be
signed off (DCO, checked by the `dco` job); cryptographically signed commits are recommended but not
required.

The default is one approving review. GitHub does not let anyone approve their own pull request, so
under the single-maintainer model the maintainer's own pull requests cannot satisfy that rule until
a second reviewer exists. Whether to apply the rules with `--approvals 0` until then is a maintainer
decision, recorded here when it is made; with 0 approvals the code owner review is not required
either (nobody could satisfy it alone), and all other rules stay unchanged.

`--list-checks` prints which pull request checks are required and why the others are not yet. The
four `e2e` shards and `sbom-scan` become required (`--include-e2e`, `--include-sbom-scan`) after
their first green runs on GitHub.

### Labels and triage

- New issues are triaged with one `kind/*` label (`kind/bug`, `kind/feature`,
  `kind/compat-report`, `kind/security`) and one or more `area/*` labels (`area/agent`,
  `area/server`, `area/ui`, `area/deploy`, `area/docs`). Pull requests get `area/*` and
  `needs-matrix` labels automatically from [.github/labeler.yml](.github/labeler.yml).
- `needs-info` marks an issue that waits for the reporter; it is closed after 30 days without an
  answer and can be reopened at any time.
- `good first issue` and `help wanted` mark issues that are ready for outside contributors.
- `breaking-change` and `no-changelog` are set by a maintainer on pull requests (see
  [CONTRIBUTING.md](CONTRIBUTING.md#changelog)); `backport/X.Y` marks pull requests that go to a
  release branch.
- Questions are moved to Discussions; vulnerability reports in public issues are hidden and the
  reporter is asked to use [SECURITY.md](SECURITY.md).
- Issues are linked to the public GitHub milestones of [ROADMAP.md](ROADMAP.md).
- The first-response target for issues and pull requests is 7 days, best effort.

### Discussions

GitHub Discussions is the place for questions and ideas. The categories, created by hand, are:

| Category | Slug | Format | Form |
|---|---|---|---|
| Announcements | `announcements` | Announcement (maintainers post, everyone comments) | none |
| Q&A | `q-a` | Question and answer | [.github/DISCUSSION_TEMPLATE/q-a.yml](.github/DISCUSSION_TEMPLATE/q-a.yml) |
| Ideas | `ideas` | Open discussion | [.github/DISCUSSION_TEMPLATE/ideas.yml](.github/DISCUSSION_TEMPLATE/ideas.yml) |
| Show and tell | `show-and-tell` | Open discussion | [.github/DISCUSSION_TEMPLATE/show-and-tell.yml](.github/DISCUSSION_TEMPLATE/show-and-tell.yml) |
| Korean (한국어) | `korean` | Open discussion | [.github/DISCUSSION_TEMPLATE/korean.yml](.github/DISCUSSION_TEMPLATE/korean.yml) |

The slug must match the form file name, otherwise GitHub does not use the form. Help in Discussions
is given by the community on a best-effort basis.

## CI runners and secrets

- Pull request checks, including those from forks, run only on GitHub-hosted runners. Workflows do
  not use the `pull_request_target` trigger.
- Self-hosted runners (compatibility matrix, benchmarks, release gates) run only for pushes to
  trusted branches (`main`, `release/*`) and release tags (`v*`), scheduled runs and manual triggers
  by maintainers, and every self-hosted job carries a guard that limits it to those refs, so a manual
  run on a feature branch never reaches them. They are registered in the organization runner group
  `argos-trusted` restricted to trusted workflows, and the repository requires approval before
  workflows from outside collaborators run. `.github/scripts/check-workflows.sh` enforces these
  rules on every pull request, and `check-workflows.sh --simulate pull_request --fork` shows which
  jobs a fork pull request would start (details in
  [docs/security/supply-chain.md](docs/security/supply-chain.md#runner-isolation)).
- Secrets live only in GitHub Environments with required reviewers (`trusted` for self-hosted jobs,
  `release` for releases), never in runner environment variables or in the repository. Jobs on
  self-hosted runners have no write permission; writes happen in GitHub-hosted follow-up jobs.

## Code of conduct

Everyone participating in the project follows the [Code of Conduct](CODE_OF_CONDUCT.md). The
maintainers enforce it; reports go privately to the maintainers as described in its "Enforcement"
section. Security vulnerabilities are handled under [SECURITY.md](SECURITY.md).
