# Roadmap

Argos APM is managed by quality gates rather than calendar dates: a phase ends when its gate is
passed. The detailed plan (Korean) is in [docs/01-product-plan.md](docs/01-product-plan.md),
section 11, and the gate criteria are in [docs/03-definition-of-done.md](docs/03-definition-of-done.md),
section 4. GitHub milestones (0.x, 1.0, 1.x) will track the public issues for each phase.

| Phase | Goal | Exit gate |
|---|---|---|
| Design (done) | Product plan, architecture and detailed design documents | Design review |
| Phase 0: proof of concept (current) | Close the technical risks with numbers: agent packaging and class loading, overhead, storage at target scale, XLog rendering; put the open-source skeleton in place | **Gate 0**: agent overhead ≤ 5%, queries ≤ 2 s, data consistency checks with zero mismatches, rendering ≤ 16 ms per frame, open-source skeleton present |
| Phase 1: MVP, internal beta → `0.x` | All "must" features, security requirements for the MVP, in-house deployments | **Gate 1**: two weeks without incidents in real deployments, measured non-functional requirements, Compose install by a non-participant, generated license notices |
| Phase 2a: public beta preparation → `1.0.0-beta.1` | English UI and documentation, demo profile, project files, release pipeline with signatures and SBOMs, no-phone-home verification | **Gate 1.5** |
| Phase 2b: public beta → `1.0.0-rc.1` → `1.0.0` | Remaining "should" features, tar/systemd and air-gap packages, upgrades, backup and restore, Maven Central, documentation site, compatibility promise | **Gate 2**: 1.0 GA |
| Phase 3: 1.x | Quarterly minor releases driven by feedback and compatibility reports | — |

Not planned for 1.0: OTLP ingestion (2.0), log storage and search (1.x; 1.0 injects trace IDs into the
MDC only), commercial application servers such as JEUS, WebLogic and WebSphere (1.x), Kubernetes Helm
chart (1.x), WebFlux/Netty servers and Kotlin coroutines (JVM metrics only).

Help is community-based through GitHub Discussions on a best-effort basis, without an SLA.
Releases are not announced before Gate 1.
