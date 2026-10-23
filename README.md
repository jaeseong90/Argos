# Argos APM

[![Status: pre-release](https://img.shields.io/badge/status-pre--release%2C%20unsupported-orange)](#project-status)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

[한국어](README.ko.md)

Argos APM is an open-source, self-hosted application performance monitoring (APM) system for Java
back ends (Spring Boot and Tomcat). A bytecode agent (Java 8+) records transactions, SQL (including
connection-pool wait), outbound calls, batch jobs and JVM, pool and host metrics, and sends them to a
Spring Boot collector that stores data in ClickHouse and PostgreSQL. A React UI shows a service
overview, XLog (response-time scatter plot), distributed traces, live active transactions, complete
SQL statistics and alerting tuned to avoid night-time false positives. It is designed to run anywhere,
including air-gapped networks, at commercial-product quality.

## Project status

**Pre-release. Not supported. Do not use in production.**

Argos APM is in the design and repository-scaffolding phase. There is no release, no container image
and no published agent yet. The repository currently contains the design documents, the build
skeleton and the CI checks that every later change must pass. Interfaces described below are planned
and may change without notice until 1.0.

## Planned quick start

The following is the intended first-five-minutes experience; it does not work yet.

```sh
# Server, ClickHouse, PostgreSQL, two sample apps with the agent attached, and a load generator.
docker compose --profile demo up
# Open http://localhost:8080 and watch XLog points arrive within 60 seconds.
```

Attaching the agent to an application (planned):

```sh
export ARGOS_SERVER_TOKEN_FILE=/etc/argos/agent.token
java -javaagent:/opt/argos-agent/argos-agent.jar \
     -Dargos.server.address=argos.example.internal:9100 -Dargos.app.name=shop -Dargos.app.env=prod \
     -jar app.jar
```

Every setting can also be given in `argos-agent.yml`, as a `-Dargos.<key>` system property or as an
`ARGOS_<KEY>` environment variable.

## Building from source

Requirements: JDK 21 (Gradle provisions the JDK 8 toolchain for agent tests), Node.js 24 (24.14.0 or
later) with Corepack, Docker 24+ and git. See [docs/dev-setup.md](docs/dev-setup.md) for details,
Windows notes and the full list of checks.

```sh
corepack enable                      # once per machine
corepack install -g "$(node -p "require('./ui/package.json').packageManager")"   # pnpm pinned by ui/
./gradlew build                      # all modules: compile, unit tests, static analysis, checks
pnpm -C ui install --frozen-lockfile
pnpm -C ui build && pnpm -C ui test
```

On Windows, if `corepack enable` fails with EPERM, run pnpm as `corepack pnpm …` (see
[docs/dev-setup.md](docs/dev-setup.md) §1).

## Compatibility targets (1.0)

| Area | Supported (verified) |
|---|---|
| Application JDK | 8 (8u252+), 11, 17, 21, 25 |
| Runtime model | Platform threads; virtual threads (JDK 21+, Spring Boot 3.2+) |
| Servlet container | Tomcat 9.0, 10.1, 11 (standalone and embedded) |
| Spring | Framework 5.3 / 6.x / 7.x, Spring Boot 2.7 / 3.x / 4.x |
| JDBC | MySQL, MariaDB, PostgreSQL, Microsoft SQL Server, Oracle |
| HTTP clients | `HttpURLConnection`, Apache HttpClient 4.5 / 5.x, OkHttp 3 / 4, Java 11 `HttpClient`, `RestTemplate` |
| Server | JDK 21, ClickHouse (bundled LTS), PostgreSQL 15+, Docker 24+, linux/amd64 and linux/arm64 |

WebFlux (Netty) servers, Kotlin coroutines and GraalVM native images are out of scope for 1.0.
The full matrix, including "not yet verified" combinations, is in
[docs/01-product-plan.md](docs/01-product-plan.md) (section 8).

## Telemetry: none

Argos APM does not phone home. The agent, server and UI make no network calls other than to
destinations an operator explicitly configures (the collector address, alert channels, SMTP).
There are no usage statistics, crash reports or update checks, and the UI loads no external fonts,
scripts or analytics.

## Documentation

| Document | Contents |
|---|---|
| [docs/README.md](docs/README.md) | Map of the design documents (Korean) and reading order |
| [docs/dev-setup.md](docs/dev-setup.md) | Development environment, build and test commands |
| [CHANGELOG.md](CHANGELOG.md) | Notable changes ([Keep a Changelog](https://keepachangelog.com/en/1.1.0/)) |
| [ROADMAP.md](ROADMAP.md) | Phases and quality gates |
| [docs/adr/](docs/adr/) | Architecture decision records |

The planning and design documents (`docs/01`–`docs/06`, `docs/10`–`docs/19`) are written in Korean.
User-facing documents are written in English with Korean translations.

## Community and contributing

- Questions and ideas: GitHub Discussions (English or Korean). Issues are for bugs, feature proposals
  and compatibility reports.
- Contributions are welcome under the [Developer Certificate of Origin](https://developercertificate.org/):
  sign off every commit with `git commit -s`. See [CONTRIBUTING.md](CONTRIBUTING.md).
- This project follows the [Code of Conduct](CODE_OF_CONDUCT.md).
- How decisions are made: [GOVERNANCE.md](GOVERNANCE.md). Development is planned and reviewed by
  people and implemented with the help of an AI coding agent; every change passes the same tests and
  human review.

## Security

Please do not report vulnerabilities in public issues. See [SECURITY.md](SECURITY.md) for private
reporting and response times.

## License

Argos APM is licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for
attribution. The license will not be changed (see [GOVERNANCE.md](GOVERNANCE.md)).
