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

## CI runners and secrets

- Pull request checks, including those from forks, run only on GitHub-hosted runners. Workflows do
  not use the `pull_request_target` trigger.
- Self-hosted runners (compatibility matrix, benchmarks, release gates) run only for pushes to
  trusted branches (`main`, `release/*`) and manual triggers by maintainers. They are registered in
  an organization runner group restricted to trusted workflows, and the repository requires approval
  before workflows from outside collaborators run.
- Secrets live only in GitHub Environments with required reviewers, never in runner environment
  variables or in the repository.

## Code of conduct

Everyone participating in the project follows the [Code of Conduct](CODE_OF_CONDUCT.md). The
maintainers enforce it.
