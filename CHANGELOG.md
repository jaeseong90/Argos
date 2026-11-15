# Changelog

All notable changes to Argos APM are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Versions before 1.0.0 make no compatibility promise; migrations from the previous 0.x minor are provided.

Every pull request adds a line under `## [Unreleased]` (in the matching `Added`, `Changed`,
`Deprecated`, `Removed`, `Fixed` or `Security` subsection) or carries the `no-changelog` label.

## [Unreleased]

### Added

- Repository skeleton: Gradle (Kotlin DSL) monorepo with convention plugins, JDK toolchains
  (agent modules target Java 8, server Java 21), dependency verification, and the React + TypeScript UI
  workspace built with pnpm.
- Quality gates: Spotless with SPDX headers, Error Prone, ArchUnit rules for agent advice code,
  ESLint and strict TypeScript, license allow-list checks, and agent jar shading checks.
- Open-source project files: Apache-2.0 `LICENSE`, `NOTICE`, README (English and Korean),
  contributing guide with DCO sign-off, code of conduct, security policy, governance, roadmap,
  release checklist, issue and pull request templates, and architecture decision records.
- Continuous integration on GitHub-hosted runners: pull request checks (build, lint, license,
  agent packaging, integration, security, OpenAPI compatibility, changelog, DCO), CodeQL, secret
  scanning with gitleaks, Dependabot updates and path-based pull request labels. All actions are
  pinned to commit SHAs.
- XLog canvas proof of concept at `/xlog-poc`: a Canvas 2D response-time scatter plot for up to
  150,000 generated points with linear and log axes, latency threshold, colour by status, instance
  or service, realtime mode (500 points/s, 5-minute window), drag selection with a summary panel and
  the slowest 1,000 selected points. Playwright end-to-end test and Chrome-tracing frame-time spec
  (`pnpm -C ui e2e`, `pnpm -C ui perf`).
- Sample applications for agent, compatibility, benchmark and security tests (`sample-apps/`,
  development only; `sample-apps/load` becomes the public `argos-demo-load` image): a shared
  endpoint catalog with environment switches (`SAMPLE_DB`, `SAMPLE_POOL`, `SAMPLE_ROUTING_DS`,
  `SAMPLE_VT`), `shop-boot2` (Spring Boot 2.7, Java 8, javax), the reference app `shop-boot3`
  (Spring Boot 3.5), `shop-boot4` (Spring Boot 4.1, Framework 7, Tomcat 11), `shop-heavy` (more than
  200 jars, 30,000 classes loaded at startup), `shop-batch` (`@Scheduled`, Quartz, Spring Batch),
  WAR apps on standalone Tomcat 9, 10.1 and 11 (`shop-war-javax`, `shop-war-jakarta` with two web
  apps), the `ext-stub` outgoing-call target, Dockerfiles, and smoke tasks that start each app
  without an agent and check its catalog (`./gradlew :sample-apps:<app>:sampleSmoke`).
- Load and seed traffic: the k6 benchmark scenario `sample-apps/load/bench.js` with
  `run-bench.sh` (k6 image pinned by digest, machine load recorded with the results), a demo load
  generator without third-party dependencies (fixed arrival rate, image definition for
  `argos-demo-load`), and `test/seed/seed-traffic.sh` for the PII and XSS seeds carrying the
  `ARGOS_SEED` sentinel.
- ClickHouse storage proof of concept: the bundled ClickHouse 26.8 LTS pinned by digest
  (`deploy/clickhouse/image.properties`), the initial schema `deploy/clickhouse/schema/V001__init.sql`
  (five raw tables, twelve rollups and their materialized views sharing `deploy/clickhouse/rollups/*.sql`),
  server and user configuration with three least-privilege accounts and no default user, and
  development-only tools in `test/storage/`: a synthetic telemetry generator (bulk and replay through
  deduplication tokens), the Q01-Q20 query runner and report, a standalone benchmark server script, and
  integration tests against a real ClickHouse. Results: `docs/poc/clickhouse-benchmark.md`.
- Java agent proof of concept: a single `argos-agent.jar` (Java 8 target, ByteBuddy 1.18.15 shaded
  under `io.argos.agent.shaded` with its `sun.misc.Unsafe` classes removed, isolated agent class
  loader, no temporary files as long as `-Dargos.log.dir` or the `logs` directory next to the agent
  directory is writable; otherwise the agent log falls back to `java.io.tmpdir/argos-agent-<pid>`)
  that attaches with `-javaagent` on JDK 8 to 25. It instruments the
  servlet API (javax and jakarta), Spring MVC service names, JDBC prepare/execute, `HttpURLConnection`
  and `ThreadPoolExecutor`, writes through the `noop` and `file` (JSONL) exporters (no collector
  transport yet), and answers `java -jar argos-agent.jar --version` and `--check` (JDK version only).
  The proof-of-concept configuration keys (`exporter`, `exporter.file.*`, `debug.transform-list`,
  `test.faults`) may change before the first release.
- Development-only agent tools: `test/agent/transport-poc` (P-3 transport comparison) and the P-2
  overhead harness (now `test/bench`). Results: `docs/poc/agent-poc.md` and
  `docs/poc/agent-overhead.md`.
- Agent test harness in `agent/agent-testing` (development only): `AppLauncher` (sample app x JDK x
  agent settings; Spring Boot jars and WAR apps in a standalone Tomcat bound to 127.0.0.1; local
  process or container with JDK images pinned by digest; JVM presets with
  `-XX:+BytecodeVerificationLocal` and `--sun-misc-unsafe-memory-access=deny` on JDK 25), an
  in-process gRPC `FakeCollector` with fault injection (latency, failures, hang, rejected
  authentication, stream close, restart, TLS modes) and no inbound size limit, an assertion DSL that
  tells missing values from zero, recorded transform baselines with diff, fail-safe, re-entrance and
  leak probes that never pass without data, agent log allow-list and Unsafe scan. The WO-02 attach
  and sample-app tests run on it.
- Agent test hooks for development builds: `argos.test.exporter` (`file:<dir>`, `noop`, `grpc`),
  the fault specs `transform-error` and `helper-nocdef` of `argos.test.faults`, and the diagnostic
  transform log `argos.debug.transform-log`. A fault spec that names no installed or enabled module,
  or a single advice point, logs one warning and injects nothing. Without these settings the agent
  behaves as before.
- Test layers: `check` always runs `test`, `archTest`, `contractTest` and `verifyAgentJar` and never
  the integration, security, matrix or performance tasks (a task-graph guard fails the build
  otherwise); unit-test layer rules (`checkUnitTestSources`); `matrixTest` is skipped with a warning
  outside the matrix runner; `benchTest`, `serverLoadTest`, `storageBench`, `soakTest` and
  `chaosTest` require `-Pbench.machine-profile=<id>` (`serverLoadTest`, `soakTest` and `chaosTest`
  are placeholders without a runner yet). Test ID and requirement tag conventions in
  `test/README.md`, and `./gradlew traceabilityReport` (requirement to test to last result, Markdown
  and JSON).
- Overhead bench harness `test/bench` (development only, formerly `test/agent/overhead`): environment
  record that stops on unfit machines, A-B-A-B-A-B runner, CPU from `/proc` and cgroup v2, latency
  re-aggregated from raw k6 output with bootstrap confidence intervals, memory, thread and startup
  measurements, container and virtual-thread variants, the `docs/bench/<date>-<commit>.md` report
  with verdicts for N-01..N-04 and N-26..N-30 (INVALID when the off-run CPU variation exceeds 2%)
  and the regression warning against an approved report.
- Collector server skeleton (`server/`, Java 21, Spring Boot 4.1): a fixed start sequence
  (configuration, keys, PostgreSQL migration, ClickHouse migration, WAL recovery, listeners) that
  stops the start with one error line when a step fails and opens no port before the migrations
  have finished. Configuration under `argos.*` with validation, `*-file` secrets, short environment
  names such as `ARGOS_PG_URL`, a warning for unknown keys and an effective-configuration summary
  with masked secrets; the key list and the short environment names are checked contracts
  (`server/src/contractTest/resources/config-keys.txt`, `config-env-aliases.txt`). PostgreSQL
  schema V001 applied by Flyway with the schema owner and the role script
  `deploy/postgres/roles.sql` (runtime, audit-retention and backup accounts); the ClickHouse schema
  in `deploy/clickhouse` applied by the server's own
  migration runner with checksums recorded in PostgreSQL, idempotency checks and multi-step
  procedures for materialized-view replacement. Management port 8081 on 127.0.0.1 with `health`,
  `info` and `prometheus` (bearer token and address allow-list for the last two); readiness is UP
  only after the migrations and while PostgreSQL and ClickHouse answer. JSON Lines logs, request
  ids and the `X-Argos-Server-Version` header; the HTTP port refuses request headers over 16 KB,
  URLs over 8 KB (400 `limit_exceeded`) and bodies over 1 MB (a declared length over the limit gets
  400 `payload_too_large`, a longer chunked body is cut off). Layered boot jar with the UI bundled,
  and a container image built with Jib on a digest-pinned base image running as user 10001 that
  writes its log to `/logs/server` and its WAL to `/data/wal` (volumes owned by uid 10001).
- Agent configuration: `argos-agent.yml` (read by the agent's own bounded YAML subset parser),
  `-Dargos.<key>` and `ARGOS_<KEY>` with the precedence defaults < file < system property <
  environment, every key of the agent configuration reference registered with its type, default,
  bounds and remote column, one warning per unknown key or invalid value, and the effective
  configuration with the source of each value. The token comes from `server.token`,
  `server.token-file` or `ARGOS_TOKEN`, is shown only as `argos_****` and is left out of the
  configuration hash. Remote configuration changes are evaluated against a hard cap
  (`remote-config.allow`, by default `sampling.*`, `instrumentation.disabled`,
  `instrumentation.ignore-paths`, `log.level` and `enabled`; `server.*`, `remote-config.*`,
  `app.*` and `log.dir` can never change remotely). Reference: `docs/agent-config.md`, checked
  against the key registry by a test.
- Agent queues: five bounded queues (transactions, profiles, statistics, metrics, active snapshot)
  on JCTools MPSC queues (Apache-2.0, shaded, atomic variant without `sun.misc.Unsafe`) with a count
  limit, a 120-second age limit and a shared byte budget. Application threads only offer; a full
  queue drops its oldest entry once and retries once, and every drop is counted in
  `argos_agent_dropped_total{queue}`.
- Agent transaction model: sampling decided when a transaction ends, summary mode, folding of
  repeated steps (`repeat_count`), `truncated` and `total_steps`, an open-step stack per activation
  scope, forced end of a leaked context, transaction flags (virtual thread, leaked, summarized),
  thread names normalized into a dictionary capped at 1,000 names, unmeasured CPU time and
  allocation left absent instead of zero, and exception messages masked with the default set
  (email addresses, Korean mobile and resident registration numbers, card numbers with a Luhn
  check, IPv4 addresses, JWTs) followed by `capture.mask-patterns`.
- Agent self-protection and self-diagnostics: advice and transformation error thresholds that
  disable a module, `instrumentation.exclude-classes`, a retransformation limiter, agent counters,
  and a self-diagnostics snapshot (live ThreadLocal contexts, active registry, weak map, agent
  thread CPU, direct memory, premain time, module states and errors, drops, orphan steps, SQL
  outside transactions) that development builds write to the file named by the test-only key
  `argos.test.selfdiag-file`. `argos.debug.transform-timing` logs the time and the agent classes of
  each start step, and the agent tests keep a premain class budget per JDK.

### Changed

- Agent transaction ids: the node part starts from a provisional value at premain and is replaced by
  12 bits of `SecureRandom` on an agent thread (later by the server-assigned instance id); above
  2,048 ids in one millisecond the generator waits for the next millisecond, so `tx_id >> 23` is
  always the creation time. Dictionary ids and `sql_id` are XXH3-64 (own implementation without
  `sun.misc.Unsafe`) instead of FNV-1a.
- Agent start: a start that fails before a log file exists prints the single line
  `argos: agent disabled: <reason>` on stderr and nothing else; the ignore matcher skips agent class
  loaders and ignored class names independently (it required both before), and fewer classes are
  loaded on the premain thread.
- ClickHouse storage aligned with the storage decisions of ADR-0007 (the unreleased V001 is changed in
  place): transaction rollups store `quantilesDD(0.01, 0.5, 0.95, 0.99)` percentile states, read with
  `quantilesDDMerge` and rebuilt byte for byte; the server default compression is ZSTD(1) and the column
  codecs end in ZSTD(1); the `xlog` bloom filter parameters are literals (fpp 0.01, granularity 4,
  user id index granularity 1); the column names follow the protocol (`dbc_wait_ms`, `timed_out`,
  `app_version_id`, `sum_dbc_wait_ms`); the batch writer account may insert into the five raw tables
  only and the migration account reads `system.mutations`; `background_schedule_pool_size` keeps the
  ClickHouse default. Schema templates accept only `{db}`, `{p}`, `{where}`, `{retention.*}` and
  `{rollup:<table>}`, and the schema checksum is taken after the rollup expansion. The storage test
  tools rebuild rollups through a staging table and `REPLACE PARTITION`, use deduplication tokens of the
  form `<wal_instance_id>:<epoch>:<first>-<last>`, and pin the Testcontainers resource reaper image by
  digest.

### Removed

- Agent proof-of-concept configuration keys `exporter` and `exporter.file.*`: they are now unknown
  keys. Test exporters are chosen with the development-only key `argos.test.exporter` (`file:<dir>`,
  `noop`, `drop`), which release builds ignore.

### Security

- Release builds of the agent ignore `argos.test.*` and `argos.testing.*` settings (system properties
  and `ARGOS_TEST_*` environment variables) and log one warning naming them. The check fails closed:
  only an agent jar whose `Implementation-Version` ends with `-SNAPSHOT` honours them, and a jar
  without that attribute or with an unreadable manifest counts as a release build. The agent jar
  checks (`checkManifestVersion`, `test/agent/check-shading.sh`) require the attribute and its match
  with the project version.
- Agent configuration warnings mask every `argos_...` run as `argos_****` before they cut a quoted
  value to 64 characters, so no part of a token is logged. A `capture.mask-patterns` expression that
  overflows the stack while matching masks the whole message as `<masked>`, and the transaction is
  kept.
- The server refuses to start when a database password is missing, shorter than 12 characters or on
  the list of well-known default passwords, masks passwords, tokens, credentials in URLs and every
  value of an `Authorization` header in every log line (masking an already masked line changes
  nothing), and ignores `argos.testing.*` settings in release builds.

[Unreleased]: https://github.com/argos-apm/argos/commits/main
