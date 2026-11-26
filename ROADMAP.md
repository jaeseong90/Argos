# Roadmap

Argos APM is managed by quality gates rather than calendar dates: a phase ends when its gate is
passed, and this roadmap gives no dates. The detailed plan (Korean) is in
[docs/01-product-plan.md](docs/01-product-plan.md), section 11, and the full gate criteria are in
[docs/03-definition-of-done.md](docs/03-definition-of-done.md), section 4.

**Current position: Phase 1 (MVP, internal beta).** The design and the proof of concept (Gate 0) are
done; the MVP features are being implemented and run in internal deployments. Releases in this
phase are `0.x` pre-releases: they exercise the release pipeline, carry no compatibility promise and
are not supported (see [SECURITY.md](SECURITY.md#supported-versions) and
[docs/compatibility.md](docs/compatibility.md)).

## Phases

| Phase | Goal | Exit gate |
|---|---|---|
| D: design (done) | Product plan, architecture and the detailed design documents for agent, protocol, server, storage, API, UI, security, deployment, tests and the build | Design review |
| 0: proof of concept (done) | Close the technical risks with numbers: single-jar agent with isolated class loading, overhead, transport stack, storage at target scale, XLog rendering; put the open-source skeleton in place | **Gate 0**: agent overhead ≤ 5%, queries ≤ 2 s, zero data consistency mismatches, rendering ≤ 16 ms per frame, open-source skeleton (license, notices, SPDX, license allow list, DCO checks) present |
| 1: MVP, internal beta → `0.x` (current) | All "must" features and the security requirements of the MVP stage, in-house deployments, `0.x` pre-releases through the release pipeline | **Gate 1**: all "must" features done, correctness rules fixed by tests, two weeks without agent-caused incidents in real deployments, measured non-functional requirements, 72-hour and chaos tests, Compose installation by a non-participant, generated license notices, threat model draft, a `0.x` tag published by the pipeline |
| 2a: public beta preparation → `1.0.0-beta.1` | English UI and documentation, dynamic configuration, audit log, demo profile and first five minutes, project files, release pipeline with signatures and SBOMs, no-phone-home verification | **Gate 1.5**: demo shows the first XLog point within 60 seconds, English documentation, project files, working security reporting channel, signed releases on GitHub and GHCR, zero egress in the air-gap test, no known critical defects |
| 2b: public beta → `1.0.0-rc.1` → `1.0.0` | Remaining "should" features, tar/systemd and air-gap packages, upgrades, backup and restore, Maven Central, documentation site, compatibility promise, security release drill | **Gate 2** (1.0 GA): at least 6 weeks of public beta with 2 or more beta releases, full compatibility matrix, all non-functional requirements measured, ASVS Level 2 checklist, authenticated security scans, reproducible signed releases, upgrade beta → rc → GA |
| 3: 1.x | Quarterly minor releases and patches as needed, driven by feedback and compatibility reports | — |

## Milestones

Public GitHub milestones track the issues of each release. A milestone is created when work on it
starts; the links below work from then on.

| Milestone | Phase | Contents |
|---|---|---|
| [0.x](https://github.com/argos-apm/argos/milestones?state=open&q=0.) | 1 | MVP pre-releases; each `0.(n-1)` → `0.n` upgrade has a migration path, nothing else is promised |
| [1.0.0-beta.1](https://github.com/argos-apm/argos/milestones?q=1.0.0-beta.1) | 2a | First public beta: everything required by Gate 1.5 |
| [1.0.0-rc.1](https://github.com/argos-apm/argos/milestones?q=1.0.0-rc.1) | 2b | Feature complete for 1.0, first Maven Central publication, upgrade from the betas |
| [1.0.0](https://github.com/argos-apm/argos/milestones?q=1.0.0) | 2b | General availability: Gate 2, compatibility promise in force |
| [1.x](https://github.com/argos-apm/argos/milestones?q=1.) | 3 | Quarterly minor releases (see "Not planned for 1.0") |

## Not planned for 1.0

These are planned for later releases or are out of scope; feedback in
[Discussions (Ideas)](https://github.com/argos-apm/argos/discussions/categories/ideas) helps order
them.

- OTLP ingestion (2.0).
- Log storage and search (1.x). 1.0 injects trace and transaction IDs into the logging MDC only.
- Application servers JEUS, WebLogic and WebSphere (1.x).
- Kubernetes Helm chart and Pod metadata (1.x).
- Message consumers as transactions (`@KafkaListener`, JMS), Redis and Kafka client
  instrumentation (1.x).
- Heap histogram, GC trigger, loaded-class listing and dynamic method profiling by runtime
  retransformation (1.x).
- Custom dashboards with widget layout and variables (1.x).
- Collector high availability and scale-out (1.x); 1.0 runs as a single node.
- mTLS between agent and server (1.x).
- WebFlux/Netty servers and Kotlin coroutines: JVM metrics only.
- Java 7 and older, and GraalVM native images: not supported.

Help is community-based through GitHub Discussions on a best-effort basis. Releases are not
announced before Gate 1.
