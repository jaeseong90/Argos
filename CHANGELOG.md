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
- Server authentication and access control for the UI and the REST API:
  - Login with cookie sessions: a 15-minute access token signed with Ed25519 (JDK, no JOSE library;
    key in `conf/jwt.key`, created at the first start, or `ARGOS_JWT_KEY`; a previous key is
    accepted during a rotation), a session record in PostgreSQL and three cookies (`argos_at`,
    `argos_rt` on `/api/v1/auth/refresh`, `argos_csrf`).
  - Refresh rotation with reuse detection: a refresh token presented twice revokes its whole
    session family.
  - Sessions end at once on logout, password change or reset, role change, account disablement and
    administrator revocation, with an event other components can listen to. At most five sessions
    per user (the oldest is revoked). Optional long kiosk sessions for approved viewers on allowed
    networks, off by default.
  - CSRF protection: double-submit token in `X-Argos-CSRF` plus an `Origin` (or `Referer`) check
    against `argos.web.public-url`, and `Sec-Fetch-Site: cross-site` is refused.
  - Setup token bootstrap for the first administrator: one use, 30 minutes, shown in one log line
    and written to the owner-only file `conf/setup-token`. Unattended setup reads
    `ARGOS_ADMIN_PASSWORD_FILE` and requires `ARGOS_IP_MODE`; that administrator must change the
    password at the first login.
  - Password policy (12 to 128 characters, 10,000 common passwords refused, no user name or
    `argos` inside) and lockout after 10 failures for 15, then 30, then 60 minutes.
  - User administration API under `/api/v1/admin/users`: list, create, role, enabled, `pii.view`,
    kiosk approval, session revocation, password reset, unlock.
  - Role-based access control with the roles `viewer`, `operator` and `admin`: every REST handler
    declares its role with `@RequiresRole`, and a handler without one stops the server start, as
    does any other kind of HTTP handler (bean-name URL handlers, functional routes, custom handler
    mappings) apart from static resources. The run-time OpenAPI document is served to viewers at
    `/api/v1/openapi` (and `.yaml`) with `x-argos-role`, `x-argos-pii` and `x-argos-audit` on every
    operation.
  - API rate limits answered with 429 and `Retry-After`: login, setup and refresh 20 per minute per
    address; the API 50 per second with a burst of 100 per session; heavy queries 10 per minute;
    CSP reports 60 per minute. Rejections are counted in `argos_ratelimit_rejections_total{scope}`.
  - Security headers on every response and a Content-Security-Policy with a new nonce per request
    for the UI page, with the report endpoint `/api/v1/csp-report`.
  - One JSON error body for every API error (`{"error":{"code","message","details","request_id"}}`),
    and audit records of the authentication, user and setup events in PostgreSQL.
  - PostgreSQL migration V002 (session families and rotation, lockout level, user permissions,
    failure counts of unknown login names kept as SHA-256 of the name) and
    the configuration keys `argos.jwt-key`, `argos.jwt-key-file`, `argos.jwt-previous-key`,
    `argos.jwt-previous-key-file`, `argos.auth.setup-token-file`, `argos.admin-username`,
    `argos.admin-password-file`, `argos.security.cookie.secure`, `argos.security.kiosk.*`,
    `argos.ratelimit.api-burst-per-user`, `argos.ratelimit.csp-report-per-ip-per-minute` and
    `argos.web.csp.style-unsafe-inline`.
- Agent-collector protocol `argos.v1` in `proto/` (the single source; the agent and the server use
  the same generated code): the session stream and five batch RPCs, the protocol version, shared
  limits and latency bucket bounds, a committed field snapshot test and `buf` lint and breaking
  checks (`bash .github/scripts/check-proto.sh`, pinned container, also in the pull request lint
  job). The differences from the protocol design and the mapping to the ClickHouse columns are in
  `proto/README.md`.
- Agent gRPC transport (the default exporter): batches per kind with sequence numbers, at most four
  calls in flight, retries with the same sequence number, splitting at 1 MiB and gzip above 1 KiB;
  a session stream with hello, heartbeats and clock samples, dictionary pre-send and requests,
  instance updates, `AgentBye` with a one-second flush at shutdown, throttling and reconnects with
  jittered backoff (5 minutes after a token rejection). TLS uses the JDK provider with chain and
  host name verification (`server.tls.ca-file`); plaintext and `insecure-skip-verify` log a warning
  on every connect. One `argos-io` thread, Netty 4.1 and gRPC shaded into the agent jar with their
  `sun.misc.Unsafe` code paths off. Java 8 before 8u252 logs one error and turns only the transport
  off. Commands and configuration pushes are received and answered as rejected or not allowed;
  carrying them out comes later. Between reconnect attempts the agent holds no connection (one TCP
  connect and one name lookup per attempt); it never uses the application JVM's proxy settings; a
  session replaced by another JVM with the same instance name, or refused for good, stops the
  transport until restart with one log line; repeated gRPC and Netty warnings are logged once per
  minute (`argos_agent_library_log_suppressed_total`), and tokens are masked in exception text too.
- Development-only collector tools: a standalone fake collector
  (`./gradlew :agent:agent-testing:fakeCollectorJar`), protocol contract vectors for the server
  tests, and `--exporter grpc` with `--collector` and `--agent-config` in the overhead bench.
- The agent jar's `META-INF/THIRD-PARTY-NOTICES` carries the copyright line and BSD-3-Clause text
  of protobuf-java, the NOTICE files of Netty, gRPC and PerfMark and the MIT text of Netty's
  SLF4J-derived logging classes (verbatim upstream copies in `agent/agent-dist/src/notices/`). The
  new `checkThirdPartyNotices` step of `verifyAgentJar` fails when a bundled module under a license
  other than Apache-2.0 lacks its copyright line and license text, or a Netty, gRPC or PerfMark
  module lacks its NOTICE.
- Agent request boundaries (servlet, javax and jakarta): one HTTP request is one transaction. It
  starts on the REQUEST dispatch; forwards, includes, error pages and async re-dispatches join it and
  are counted in the new `dispatch_count`; an error page dispatch after the request has ended (Spring
  Boot's `/error`) is not recorded and is counted in `argos_agent_transactions_unanchored_total`.
  Ignore rules are checked in the order method, path, extension, user agent. Verified on Tomcat 9,
  10.1 and 11 (Spring Boot 2.7, 3.5, 4.1 and standalone WAR apps); Jetty and Undertow are not
  verified yet.
- Agent async and streaming requests (new module `servlet-async`): an async request ends exactly once
  at its last dispatch, `onComplete`, `onError`, after `onTimeout`, at the first response flush of
  server-sent events and streaming bodies (Tomcat 9, 10.1 and 11, matched by class name), or at
  `transaction.max-duration-ms`, which the agent's watch thread now enforces (`timed_out` and `leaked`
  flags). `101 Switching Protocols` responses are flagged `streaming`.
- Agent request data modes: query string `off`/`whitelist`/`full`, client address
  `masked`/`full`/`off` with `X-Forwarded-For` read only behind `capture.trusted-proxies`, user id
  `off`/`plain`/`hashed` (HMAC-SHA256 with the per-application salt from the collector; without a
  salt no user id is sent and `argos_agent_user_id_skipped_total{reason}` counts it), header allow
  list, and the new key `capture.request-id-header` (not set by default, value cut at 128 bytes).
  Every mode keeps its privacy default; the agent never reads request parameters, bodies or new
  sessions.
- Agent test harness: a servlet suite for the Spring Boot sample apps and the WAR apps, a servlet
  fixture web application without Spring (`/servlet-fixture`, Tomcat 9 and 10.1), and a Tomcat log
  check after a graceful shutdown of standalone Tomcat (no agent mention in a warning or in its
  stack trace; SEVERE records and ThreadLocal leak warnings beyond a run without the agent fail).
- Agent JDBC instrumentation: one SQL step per statement execution of the application whatever the
  pools, routing data sources and driver wrappers in between, with the execution time apart from
  the fetch time and rows, batch sizes, DBC steps named after the pool (their time counted as the
  transaction's connection wait; a lazy connection proxy is not one), COMMIT and ROLLBACK steps,
  and statements outside a transaction counted in `argos_agent_sql_outside_tx_total` instead of
  recorded. The database target is read once per connection (`getMetaData().getURL()`) and kept as
  `<vendor>://<host>:<port>/<db>` without account, password or parameters (Oracle thin and TNS,
  MySQL, MariaDB, PostgreSQL, SQL Server, Tibero, Altibase, CUBRID, DB2, H2). Bind parameters
  follow `capture.sql-parameters`: `masked` (default) sends nothing, `partial` the masked values of
  whitelisted `<column> = ?` positions and the type and length of the others, `full` every masked
  value; only JDK value types are rendered, never a stream, a large object or `toString()`, and
  each value is masked before it is cut at 256 characters. An Oracle or Tibero account or password
  in the URL, quoted or not, is removed too, and a URL that cannot be read safely (for example a
  `user:password@host` password holding `?` or `;` before `=`) keeps only the vendor.
- Agent SQL normalizer: literals become `?`, comments and whitespace collapse, `IN` lists and
  multi-row `VALUES` fold, with the string, comment and identifier rules of the database taken from
  its URL (MySQL and MariaDB `"..."` strings and `#` comments, SQL Server `[...]` identifiers and
  `$` money constants), assuming its default session settings; `sql.max-length`,
  `sql.backslash-escape` and `sql.keep-hints` apply. A statement that fails, is ambiguous or
  exceeds its work budget (about 1 ms for 64 KiB) is named `<unparsed>` and counted in
  `argos_agent_sql_unparsed_total`; the raw text is never sent or logged, and the statement cache
  keeps only lengths and hashes, never the text.
- Agent SQL normalizer fail-safe rules for non-default session settings: a PostgreSQL statement with a quote after an odd number of backslashes in a string is `<unparsed>`, SQL Server and CUBRID mask a `"..."` that is not shaped like an identifier, and a quote inside an unclosed `[name` is `<unparsed>`; an Oracle URL without a leading `@` whose `?` parameters could hold the `@` keeps only the vendor.
- Agent connection pool and Tomcat registries (new modules `pool` and `tomcat`): HikariCP, Tomcat
  JDBC and Commons DBCP 2 pools and Tomcat connectors register when they are created or started,
  read only through their own getters (never JMX), held weakly and bounded without pinning the
  application's class loader across a redeploy; the connector names and
  the first port reach the collector as an instance update. Sending the 5-second pool and connector
  metrics comes later.
- Sample application switches for the JDBC tests: `SAMPLE_DB` `mariadb`, `mssql` and `oracle` in
  `shop-boot3` (schema scripts per database), `SAMPLE_DRIVER_WRAPPER` (a delegating JDBC driver
  wrapper as the fifth connection level) and `SAMPLE_SECOND_POOL` (two pools, one restarted). The
  agent test harness runs the JDBC suite on H2 by default and on MySQL 8.4, MariaDB 11.4,
  PostgreSQL 16, SQL Server 2022 (`-Pagent.jdbc.containers=true`) and Oracle Database Free
  (`-Pagent.jdbc.oracle=true`) in containers pinned by digest, one at a time.
- Agent data ingestion in the collector server over gRPC (port 9100): agent sessions (hello checks,
  automatic registration of new instances, clock offset from heartbeats) and the five batch calls.
  An agent token writes only to its own application and to at most 8 environment variants
  registered automatically from it; an existing application of another environment is refused.
  Instance names longer than 128 characters are stored shortened with a hash suffix, so that two
  long names never merge.
  Every batch is written to a write-ahead log before it is acknowledged (`argos.wal.*`: group fsync,
  256 MiB segments, 10 GiB cap with a warning at 80 %, recovery at start), deduplicated by its
  sequence number, and loaded into the five ClickHouse raw tables by a batch writer (`argos.writer.*`:
  every 2 s or 50,000 rows, explicit column lists, deduplication tokens, retries with the same token,
  the write-ahead log as the spool while ClickHouse is unavailable). On the way the server corrects
  timestamps of agents whose clock runs more than 5 s ahead, masks error messages again before
  grouping them into error groups, records the application's Apdex threshold, and resolves
  dictionary entries with caps per instance and per application. A shutdown closes the sessions,
  drains running calls, flushes the writer and closes the log in that order; what was acknowledged
  and not yet loaded is loaded at the next start. Until encrypted storage exists, SQL bind values
  from agents in `full` mode (or an unknown mode) are removed before the log write and counted in
  `argos_collector_redacted_total`. A batch whose log write is not on disk within 1 s
  is answered `UNAVAILABLE` (retry after 1 s) and is still stored only once; while a log write is
  stuck for more than 1 s, new batches get the same answer at once without waiting, and at most
  64 MiB of batches wait in memory for the log (`argos_wal_queued_bytes`); readiness reports
  `ingest` DOWN when the loader stops; known instances keep connecting while PostgreSQL is down if
  their token is cached; one server holds at most 5,000 agent sessions.
- Collector limits, quotas and authentication: TLS 1.2 or 1.3 with AEAD cipher suites only
  (plaintext only with `argos.collector.allow-plaintext`), agent tokens checked by their SHA-256
  hash with a cache, addresses blocked for 10 minutes after 20 failed authentications in a minute
  (answered `RESOURCE_EXHAUSTED` with the remaining time, without a token lookup),
  batches above the size (checked while decompressing), item, string or repeat limits refused, and
  per-instance quotas for transactions, profile steps, metrics and statistics rows (new key
  `argos.collector.quota.stats-per-10s`, default 6,000) that remove items from the end of a batch,
  answer `ACK_PARTIAL_DROP`, and are given back when a batch is not written to the log. Instance and session caps per application and token; no gRPC reflection or
  health service. `argos.collector.max-message-bytes` accepts at most 63 MiB, so that every
  acknowledged batch fits one write-ahead log record that recovery can read.
- ClickHouse schema V002 (`deploy/clickhouse/schema/V002__ingest_columns.sql`, added columns only):
  `xlog.url_path`, `xlog.headers`, `profile_step.truncated` and `profile_step.total_steps`.
  PostgreSQL V003 adds the dictionary kind `pool` and `apps.registered_from_app_id`.
- Development-only server test tools: a protocol-level fake agent with the 18 §8 attacks
  (`server/src/testFixtures`, package `io.argos.server.testing.fakeagent`), end-to-end tests from a
  fake agent through the boot jar to ClickHouse (process kills, ClickHouse outage, broken
  materialized view, duplicates), and a 1,000 TPS ingest lag run (`-Pserver.load=true`).
- Query REST API under `/api/v1` (read-only; viewer role unless noted): overview, applications and
  instances, the application dashboard (percentiles, 1-day and 7-day baselines, events, collection
  counters, outages), instance comparison, service, SQL, outgoing-call and error statistics with
  their details, instance metrics, events, collection health, server health, storage health
  (operator), XLog (columnar JSON, gzip, deterministic sampling that keeps every error and slow
  point), the drag summary, transaction search, transaction detail with baseline and export, traces,
  active transactions and global search. Automatic source selection reads raw rows, 1-minute or
  1-hour rollups with the last 2 minutes and partial minutes from raw rows, and flags
  `retention_exceeded`. Every response has the `data`/`meta` envelope (request id, range, source,
  truncation); limits (range, rows, buckets, string and array sizes, timeout) answer 400 or 504
  with the standard error body; lists use tamper-checked keyset cursors. List endpoints export CSV
  (`format=csv`, formula-safe cells, at most 10,000 rows, audited, heavy rate-limit bucket).
  Collected strings are returned unescaped, with control characters replaced and length caps.
  ClickHouse is read only through the read-only account, with request values as query parameters
  and execution, row and memory limits on every statement. A transaction is found by its id within
  2 seconds of its id time, then within two days; beyond that the API answers 404 with
  `clock_skew_possible` and the transaction opens from a list or with `?ts`.
- OpenAPI specification `docs/api/openapi.yaml`, generated from the server by
  `./gradlew :server:generateOpenApi`, with the `x-argos-role`, `x-argos-audit`, `x-argos-pii`,
  `x-argos-csv`, `x-argos-limits`, `x-argos-source` and `x-argos-untrusted` extensions. Contract
  tests fail when the committed file differs from the code, lint it (role, audit, success schemas,
  operation ids, collected strings, JSON and CSV media types only, required properties on every
  response object; nullable properties are declared as such; request body schemas list only the
  fields the server requires and are never shared with responses) and run the role permission matrix
  from it; the pull request check rejects breaking changes (`oasdiff`) unless the pull request has
  the `breaking-change` label.
- Development-only query tools: `:server:loadQueryTestData` loads generator data into a running
  installation (for end-to-end UI tests), and the query API bench runs the N-07 and N-24 calls
  cold and warm on generator data (`-Pserver.apiBench=true`, profiles `ci`, `local` and the
  30-day `full`; the targets are enforced only on `full` data with
  `-Pserver.apiBench.enforce=true`).
- Web UI skeleton: the route tree of every screen (placeholders until each screen is built), the
  shell with application, environment and transaction kind selectors, global search box, alerts
  badge, account menu, side menu and kiosk mode; sign-in with return to the requested link, the
  first-run setup wizard (setup token, administrator, IP display mode, then a pointer to
  `argos-compose.sh token create` for the first agent token) and the account page with password
  change; one global time range (presets, absolute range in the browser time zone with its UTC
  offset, previous, next, zoom, now, live mode and "resume live", keyboard shortcuts with a `?`
  help); the URL as the state of every screen in the `docs/14` grammar (the `docs/15` spelling is
  still read), validated on load with defaults restored and a notice, and refused above 2 KB;
  English and Korean.
- UI client and safety rails: the TypeScript API types are generated from `docs/api/openapi.yaml`
  (`pnpm -C ui gen:api`, checked by `pnpm -C ui check:api`), with collected strings typed as
  untrusted; one API wrapper sends the session cookies and the CSRF header, refreshes the session
  once across tabs and returns to the sign-in page with the current link when it ends; the
  WebSocket client validates the `docs/14` frames, shares subscriptions, reconnects with backoff
  and stops after a session end. ESLint rules keep collected strings out of anything but text
  components, raw link targets out of `href` and tokens out of browser storage. zod runs without
  its JIT so that the server's nonce CSP reports no violation.
- Shared UI components: data table (server sort, column filters, include and exclude a cell value,
  column chooser, CSV through the server export), stat tile, status badge, time range picker, filter
  chips, kind and environment and application selectors, safe text, link and code block, empty,
  error, loading and partial result states, and a time series chart (uPlot) with event markers,
  collection outage shading, comparison baseline, click menu, cursor sync and an accessible table;
  design tokens with checked contrast and an Ant Design 6 theme.
- UI end-to-end suite against the real server: `pnpm -C ui e2e` builds a seeded stack (PostgreSQL,
  ClickHouse and the server boot jar in Docker on random local ports, generator data, users and
  agent traffic from the sample app), runs the specs for sign-in, deep links, time range, realtime
  (WebSocket stub until the server endpoint exists), i18n, layout at 1366x768, CSP, XSS seeds,
  accessibility (axe, no critical finding) and the common required elements, and removes the stack;
  `pnpm -C ui e2e:docker` runs them in the pinned Linux Playwright image, where the
  visual-regression baselines are compared; `pnpm -C ui perf:initial-load` records the initial load.
  The pull request workflow runs it in four shards (`e2e` job).
- UI keyboard and accessibility: a table cell value can be included or excluded from the keyboard
  (arrow keys pick the cell, Shift+F10 or the context menu key opens its menu); `t` then `1`-`9`
  picks a time range preset; every screen sets the page title; status changes of the realtime and
  kiosk badges are announced once instead of every second; placeholder and icon colours meet the
  contrast minimum. The session cache is dropped at sign-in and when the session ends. Live mode
  can be switched on only on screens that support it. Filter values are limited to 1,024 UTF-8
  bytes, as on the server: a longer table cell value cannot be included or excluded (the actions
  are disabled with an explanation) rather than being cut. `pnpm -C ui build` ends with a bundle
  size report against the budgets
  of `docs/15`. `e2e:docker` installs its packages with `npm ci` from a lock derived from
  `pnpm-lock.yaml` (integrity checked).
- XLog screen (`/apps/:appId/xlog`): the transactions of the time range as a scatter of response
  time over time, coloured by status, instance or top services (named in the legend), on a linear
  or log axis (seconds or minutes for batch jobs), with the application's latency threshold,
  "Showing N of M" when the server samples a window (the same points after a reload), collection
  outage shading and event markers (`alert=` emphasises its event). The realtime 5-minute window
  follows the `xlog` topic: duplicates are dropped by transaction id, pause stops only the window,
  resume and reconnect fill the gap through REST, points more than 60 s late are left out. A drag,
  or the keyboard (focus the plot, arrow keys, Enter), selects a region (`sel=` in the URL): the
  selection summary shows the count, the top 10 services (count, average, maximum, errors), the
  instance and status distributions and the server's HTTP status and error class distributions,
  and a row click filters the scatter and the list; the transaction list of the server search has
  the required columns, a column chooser, two server sorts, "top 1,000", include or exclude a cell
  value, the server CSV export, and Up/Down/Enter or a click opens the detail with the time hint
  and the way back to the list. While the search runs, the list shows its first rows from the
  chart's points when they are exactly the server's first rows. A hidden table and a polite
  announcement describe the chart for screen readers. Filter chips name the service, instance or
  error class instead of showing its key. A range over 24 hours is not requested: the screen offers
  the last 24 hours of that range or the last hour. Without a realtime message for 30 seconds the
  screen fills the window through REST every 10 seconds and says so; a drag while live keeps the
  5-minute window the chart showed.
  Filters the APIs cannot apply are listed as not applied, never emulated.
- `GET /apps/{appId}` returns `kinds`, the transaction kinds the application recorded in the last
  7 days (null when ClickHouse cannot answer); the kind filter disables Batch for an application
  without batch jobs.
- Transaction detail screen: a summary of every collected field with the request method and URL
  (CPU time and allocated memory say "not measurable" for virtual threads, missing values show a
  dash, never 0; user id and remote IP with their collection mode), the position among the same
  service's requests (p50, p95, percentile) and a side-by-side comparison with a normal request on
  a shared or own time scale, the reason a profile was not captured, and the tabs timeline
  (uninstrumented time in grey, equal to the summary value and highlighted from 20 %, stack capture
  and error marks, summary-mode rows outside the time axis, side panel), call tree with repeated
  calls folded (count and sum), SQL (normalized text, parameters as the server masked them,
  execution apart from connection wait per pool), external calls (failure kind), error, stack
  captures side by side with common frames, and headers. Previous and next follow the order of the XLog list the detail
  was opened from; links to the SQL, external and error statistics, the trace and the calling
  transaction keep the time range; JSON export through the audited server export; copy link.
- Distributed trace view: tree and timeline of the trace's transactions with application,
  environment, service, instance, version, status and time, the caller's HTTP step next to the
  callee with the network and wait time between them (both durations, without claiming a cause,
  when the caller step is shorter), external calls and missing nodes (not instrumented, not
  collected yet with "query again", profile not captured), the critical path,
  links to each transaction's detail and to the application's dashboard for the same time range,
  copy trace ID, JSON export of the loaded trace, and 7- and 30-day searches. Both screens draw
  their rows as a virtual DOM list (keyboard navigation, 24 px rows) shared in
  `ui/src/components/waterfall`.
- Application dashboard (`/apps/:appId/dashboard`): six summary tiles (TPS, average, p95, p99,
  error rate, active) with sparklines and the change against the previous range or 7 days earlier
  (`tileRef`), nine time series charts with a shared cursor (TPS, response time average/p95/p99,
  error rate, active, heap and GC pause, process CPU, Tomcat threads, connection pool usage per pool,
  external call time and errors), a comparison baseline (1 or 7 days earlier, dashed), event markers
  (`alert=` emphasises its event), collection outage shading on every chart, drag to zoom (switches
  to an absolute range) and a point menu that opens XLog around the bucket. Below the charts:
  instance tiles (state colour and label, active bar, heap, CPU, link to the instance), the
  instance comparison table (TPS, p95, error rate, active, heap, CPU, pool usage, uptime; sorting in
  the URL, the outliers the server flags highlighted, a cell value adds an instance filter, server CSV
  export), the firing alerts panel (says "not available" until the alert engine exists) and the
  collection status panel (agent and server drops, ingest lag, clock skew, excluded requests with
  reasons, late arrivals, running batch jobs; a dash when not measured, never 0). While live, the
  `counter` topic updates the tiles, the last bucket and the instance states every 5 seconds;
  reconnects and 30 seconds without a message are filled through REST.
- Docker Compose installation (`deploy/compose/`): the server, ClickHouse and PostgreSQL with no
  default credentials and only ports 8080 (UI and API) and 9100 (agent gRPC, TLS) published. The
  databases sit on an internal network without a route out, every image reference except the
  locally built server image is pinned by digest, and the server runs as uid 10001 with a read-only
  root file system. `argos-env-init.sh` checks the prerequisites, plans the container memory from
  the host memory and generates every password and key from a cryptographically secure random
  generator into `secrets/` (directory 0700, files 0600; ClickHouse receives SHA-256 hashes only),
  refusing empty, short or default secrets before anything is hashed; `.env` holds settings only.
  `argos-compose.sh` starts, stops and inspects the stack and issues agent tokens (`token create`:
  printed once, at most 5 active tokens per application, recorded in the audit log). `smoke.sh`
  checks the installed state: file modes, published ports, images, networks, the server user,
  health, Actuator and the setup token. Overlays publish the management port on 127.0.0.1:8081,
  switch to the unattended setup, or add an example nginx reverse proxy on 443. Installation
  guide: `docs/install/compose.md` (Korean: `docs/install/compose.ko.md`).
- Server container image: `/data`, `/data/tmp` and `/logs` belong to uid 10001 in the image, so new
  volumes need no preparation; `HOME=/data`; the JVM options of the deployment design with the
  heap from `JAVA_TOOL_OPTIONS`; a reproducible creation time.
- Docker Compose installation checks: `argos-compose.sh up` refuses to start when the containers
  cannot read their configuration files (a checkout under umask 027 or 077), and with the reverse
  proxy unless port 8080 is published on 127.0.0.1 only. The example proxy overwrites
  `X-Forwarded-For`, and the server trusts forwarded headers from the proxy's fixed address only.
  `argos-env-init.sh --force` refuses to replace the secrets while volumes of the installation
  exist. `token create` prints the agent configuration with the server address taken from the gRPC
  certificate. `smoke.sh` signs in only with `--check-admin-login` (unattended setup, right after
  the installation).
- Instance states: every instance is UP, STALE (60 seconds without a heartbeat), DOWN (180 seconds
  without one, or 5 minutes after the agent's shutdown message without a reconnect) or WARN (clock
  offset of 1 second or more, drops in the last 5 minutes, the lowest accepted agent version, a
  disabled module, the kill switch, a configuration different from the application's majority, or
  leaked contexts). Transitions are stored as state events, feed the collection outages of the
  dashboard and XLog, and are exported as `argos_instances{state}` and `argos_clock_offset_ms`. The
  REST APIs report the state and the disabled modules. A session without any message for 90 seconds
  is closed. An instance that restarts under a temporary `host:pid` name is merged back into its
  earlier instance once the agent reports its port. Instances without contact for 7 days are
  archived and deleted 30 days later.
- Approval queue: a Hello for an environment without automatic registration, or beyond the
  8-variant limit, is queued in `pending_registrations` (attempts and last attempt, at most 1,000
  rows, the oldest replaced) instead of being dropped; an approved variant is accepted on the
  agent's next retry with its existing token. PostgreSQL migration V004.
- Realtime WebSocket `/ws`: the `xlog`, `counter`, `active` and `overview` topics (with `alert` and
  `admin` accepted for later use) on an in-memory bus that never blocks ingest. A slow subscriber
  loses its oldest XLog pushes first and is told how many (`notice` backpressure, `dropped`), and
  keeps only the newest counter, active and overview push. Points arrive within a second of their
  receipt; points more than 60 seconds late are stored but not pushed. The handshake requires the
  session cookie and the server's own origin; a session that is signed out, revoked, disabled or
  changes its password is closed within a second with code 4401 after a `reauth` frame. Limits:
  5 connections per user, 20 subscriptions per connection, subscription changes rate limited,
  4 KiB per client message, 60 seconds without a client message.
- Active transactions gauge: every active snapshot is stored as `argos_active_transactions`, and
  the dashboard's active series is the sum over the instances (empty where no snapshot exists).
- Overview screen (`/overview`, the default screen after sign-in): every application of the
  environment and application group filters as a dense table or as cards (the choice is
  remembered), with its state, instances up of total, TPS, p95, error rate, active and running
  batch jobs, firing alerts, last deploy and one-hour TPS, p95 and error rate trends; a missing
  value shows a dash, never 0. Applications that are not UP or have firing alerts stay on top
  whatever the sort (state, error rate, p95, alerts, kept in the URL). A click opens the
  application's dashboard with the same time range, kind, environment and live mode. Side panels
  show the firing alerts of all applications and the collection server's own state (ingest lag,
  agent sessions; disk usage, certificate expiry and channel failures say "not available" until
  they are measured). While live, the `overview` topic updates the rows every 5 seconds without a
  REST query. Kiosk mode (`?kiosk=1`) hides the top bar and the menu. At 1366x768, 30 applications
  fit without scrolling. The application selector is disabled on screens that are not about one
  application.
- Full statistics and rollups: every SQL and outgoing call statistic the agent sends is stored
  without sampling and rolled up per minute and per hour (`sql_stat_*`, `ext_stat_*`, split by
  transaction kind); a resent batch adds nothing. The hourly instance rollup `instance_stat_1h`
  (ClickHouse migration V003, kept 365 days) serves instance-filtered dashboards over long ranges.
- Nightly consistency check (`argos.ops.reconcile.cron`, 03:00 server time): the previous UTC day's
  transaction counts are compared with the application, service and instance rollups, the minute
  rollups with their raw tables and the hourly rollups with their minute rollups; mismatching days
  are rebuilt automatically (at most twice) and checked again. The statement counts of transactions
  against the SQL and outgoing call statistics are recorded only. Results are stored in
  `reconcile_results` (PostgreSQL migration V005) and exported as
  `argos_reconcile_mismatch_minutes{app}`.
- `argos-admin` commands in the server jar (`java -jar argos-server-<version>.jar admin ...`, logs on
  stderr, exit code 0 success, 1 failure, 2 usage error): `rollup rebuild --date <day> [--table <t>]...
  [--json]` rebuilds a day's minute rollups and the month's hourly rollups byte for byte through
  staging tables and `REPLACE PARTITION`; `consistency check --date <day> [--rebuild] [--json]` runs
  the nightly check on demand; `apdex reapply --app <id> [--service <id>] --from <day> --to <day>`
  applies the current Apdex threshold retroactively to raw transactions still within retention and
  rebuilds those days. All three share one lock with the nightly job.
- Service Apdex thresholds: a per-service threshold overrides the application's (at most 1,000 per
  application, PostgreSQL migration V006). The threshold is stamped on each transaction at receipt
  and kept through a write-ahead log replay, so a change applies from then on and earlier minutes
  keep their values; every change adds an `apdex_t_change` event marker to the dashboard and the
  events API.
- Error groups keep one representative stack: the first error profile of a group is resolved
  through the frame dictionary (also when the dictionary arrives later), masked and stored once,
  and returned by the error group API.
- Application dashboard: the CPU chart shows system CPU next to process CPU, the average, p99 and
  active tiles show their change against the previous range or 7 days earlier, an instance can be
  excluded (`f.instance=!<id>`, API parameter `exclude_instance`, at most 100), and instance-filtered
  views follow the same source rule as the whole application, including the hourly rollup for long
  ranges. While live, the `counter` topic fills heap
  used, GC pause and CPU from the agents' JVM metrics (empty, never 0, when no sample arrived).
- Statistics screens (side menu STATISTICS, `/apps/:appId/stats/{services,sql,external,errors}`):
  Top N tables (10, 50, 100 or 500 rows) with sort by total time, count, average, p95 and errors,
  change against the previous range (count, p95, error rate), kind and environment filters, value
  click and column filters (kept in the URL and passed to the related transactions in XLog), and a
  server CSV export of the same question. Bucket-estimated SQL and external-call percentiles carry an
  "estimated" badge. Service detail: trend with deploy markers, per-version p95 table, instance
  distribution and a cross view of the service's SQL, external calls and error groups. SQL detail:
  calling services with their share, instances, databases, normalized and original text shown as
  text, connection wait shown as not measured. External call detail per host: timeouts and connect
  failures, trend, calling services and related transactions. Error analysis: a "new" filter,
  first and last seen, masked message pattern, representative stack, version and instance
  distribution and the last 10 transactions. Each screen loads as its own route chunk.
- Query API: `sort=p95_ms` on `GET /apps/{appId}/sql/stats` and `/external/stats` (also for the
  CSV export), ordered by the same bucket estimate as the returned `p95_ms`, and `compare_prev` on
  `GET /apps/{appId}/errors/groups` adding `delta.count` against the previous range of the same
  length (empty when the group did not occur then; CSV column `delta_count`).
- Agent HTTP client instrumentation for six clients: `HttpURLConnection` (also under
  `RestTemplate`), the JDK 11+ `java.net.http.HttpClient` (`send` and `sendAsync`), Apache
  HttpClient 4.5 and 5 (classic), OkHttp 3 and 4 (`execute` and `enqueue`) and Reactor Netty
  `HttpClient` under Spring `WebClient` (`block`, futures and `subscribe` inside a request). Each
  call inside a transaction is one external-call step named by `scheme://host:port`, with the path
  (no query), the status and a failure class: timeout, connection failure, HTTP 5xx or other (by
  cause and the same for every client: any connect or response timeout is a timeout, including
  Netty's connect timeout and OkHttp's whole-call timeout (`callTimeout`); a refused or unreachable
  host is a connection failure); a
  followed redirect is one step with the final status. Transactions count their external calls and
  external time.
- W3C trace context propagation: the agent sends `traceparent` (and `tracestate`) on outgoing calls,
  keeps a `traceparent` the application set itself, and a servlet request that arrives with a valid
  `traceparent` continues that trace (parent span, validated and bounded `tracestate` passed on).
  The `propagation.*` settings now take effect: `propagation.exclude-hosts` sends neither header to
  the listed hosts, and only hosts in `propagation.argos-state.hosts` receive the `argos` tracestate
  entry, from which the receiving agent records the caller application (`caller_app_id`) that links
  a distributed trace across applications. No `argos` entry is sent by default; without it,
  `GET /traces/{traceId}` links a callee to the transaction of the trace whose span id is the
  callee's parent span id, so distributed traces stay connected with the default settings.
- `GET /apps/{appId}/external/{hostId}` adds per-bucket `timeouts` and `connect_failures` series,
  drawn in the trend of the external call detail screen with their totals.
- Active transactions screen (`/apps/:appId/active`, side menu "Active transactions"): per-instance
  bars of running transactions in four elapsed bands (default 1, 3 and 8 seconds, adjustable per
  viewer in the browser), instance state badges (WARN, STALE, DOWN), a top summary (total, count in
  the slowest band, busiest instance) with a link to the alert rules of `active_transactions`, and a
  list of every running transaction with instance, service, elapsed time, state with its detail
  (SQL summary, called host, database of a connection wait, method, async wait), thread (virtual
  threads marked), user, client IP (shown as "Not collected" when the collection mode is off), kind
  and start time, sorted by elapsed time. The screen follows the `active` realtime topic every 5
  seconds with an "as of N s ago" age computed on the server clock, can be paused, shows "Updates
  stopped" when snapshots stop arriving and recovers by itself after a reconnect; filters for
  instance (also by clicking a bar), service, kind and state are kept in the URL. A selected row
  links to the recent completed transactions of the same service in XLog. Operators can show the
  thread stack of a running transaction (state, frames and whether the thread still runs that
  transaction) and take a thread dump that opens in the instance's dump viewer; viewers see both
  actions disabled with the reason. Instance names link to the instance detail. The screen loads
  as its own route chunk.
- Instance detail screen (`/apps/:appId/instances/:instanceId`) and instance list
  (`/apps/:appId/instances`): a header with state, application, environment, version, agent
  version (old or unsupported warning), JVM, OS, host, PID, cgroup, start time, uptime, restarts in
  the selected range, clock skew, drops, disabled modules, IP capture mode and virtual threads;
  links to the instance's XLog (coloured by instance), active transactions, alert history and
  configuration history. The metrics tab draws heap, memory pools, GC count and pause per
  collector, threads, classes, process and system CPU (with the container limit), file
  descriptors, Tomcat per connector, connection pools per pool, system memory, disk and network
  with a synchronised cursor, deployment and instance event markers and an optional baseline from
  the same range seven days earlier; series the agent does not report show "Not measured". The
  environment tab shows the effective configuration with the source of each key, the remotely
  changeable keys, module status, the public self-diagnosis summary, and for administrators the
  masked environment dump (environment, system properties, JVM arguments). The commands tab
  offers thread dump, thread stack and self diagnosis to operators and reload config and
  environment dump to administrators, with a confirmation, live status, error and expiry reasons
  and the command history (requester, time, duration, size; results open only for the requester
  and administrators). The thread dump viewer groups threads by state with counts, collapses
  identical stacks, shows lock owners and waiters and deadlocks, marks virtual threads, searches
  thread names, classes and methods, links threads to their running transaction, keeps the last
  20 dumps, warns when a dump was cut and downloads the dump as `.txt` generated in the browser.
  The screen loads as its own route chunk with its own `instance` and `commands` locales.
- Command history (`GET /api/v1/commands/{id}`, `GET /api/v1/instances/{id}/commands`) returns
  `requested_by_name`. The development-only active feeder answers remote commands with
  `--commands` for end-to-end tests.
- Alert engine: rules per application, environment and target (application, service, instance,
  external host) over latency percentiles, error rate, throughput, active transactions, JVM and
  pool metrics, batch runs, instance state and the server's own health, evaluated every 10 seconds
  from in-memory windows (up to 15 minutes) or the 1-minute rollups (longer windows). A state machine
  with pending (`for`), firing, acknowledged, resolved and no-data states, warn and critical levels,
  a recovery threshold, re-notification, a minimum sample count (low-traffic applications do not
  fire), a warm-up grace for new instances, active hours, mute schedules and time-limited mutes (at
  most 30 days). An agent that says goodbye and reconnects within 5 minutes raises no instance-down
  alert. Notifications of the same application, environment and severity are grouped: the first
  goes out at once, later ones within 30 seconds are combined (at most 20 listed). Messages use a
  fixed set of template variables and carry a deep link to the dashboard around the alert time.
- Alert REST API under `/api/v1/alerts`: rules (create, update, delete, enable, disable, clone,
  history, preview over up to 7 days), templates and template apply, events with acknowledgement,
  mutes, statistics, and notification channels with a test send and webhook signing key rotation.
  Rule, mute and channel changes are audited; viewers read, operators change rules, administrators
  manage channels.
- Notification channels: email (SMTP with STARTTLS required by default), Slack incoming webhooks
  and generic webhooks with a versioned JSON payload (`schema_version` 1) signed with HMAC-SHA256
  (`X-Argos-Signature`, two signatures during a key rotation grace of 7 days). Each delivery is tried
  up to 4 times (retries after 1, 5 and 30 seconds); a channel that fails 3 deliveries in a row is
  marked failing and raises a self alert on the other channels. Delivery records keep status and
  error codes only and are removed after the alert event retention.
- Default alert templates: 14 templates (10 application, 4 server) following the product plan. The
  recommended application rules are created when an application registers, the server rules once
  on first start, and a rule made from an older template version shows the field differences.
- The "Firing alerts" panels of the overview and the application dashboard list the engine's
  firing and acknowledged alerts (`GET /overview/alerts`, `Dashboard.firing`, per-application
  `firing {critical, warn}`, `alerts_firing_total`), and the server health panel shows the channel
  delivery failures of the last 24 hours.
- Agent metrics every 5 seconds (aligned to the wall clock) on a new agent thread `argos-metrics`:
  JVM memory by area, pool and buffer, garbage collection counts and time with a normalised
  collector name and type (G1, Parallel, Serial, CMS, Shenandoah, ZGC including generational ZGC)
  and `jvm_gc_pause_seconds_total` that counts pauses only, threads, classes, process CPU, open
  file descriptors, uptime, system CPU, memory, local disk sizes and network traffic (Linux),
  container CPU and memory limits and CPU throttling from cgroup v1 or v2 (the agent now reports
  the detected cgroup version), JDBC pool and Tomcat connector gauges, connection waits per pool,
  and the agent's own counters. Metrics are read without JMX remote access and work with Spring's
  default `spring.jmx.enabled=false`. Counters are sent with a reset-corrected delta; at most
  `metrics.max-series` (500) series are kept and further series are dropped and counted
  (`argos_agent_metric_series_dropped_total`). Metric names follow the OpenMetrics conventions and
  the list is kept as a public contract.
- Full SQL and external call statistics from the agent: every execution and every outgoing call
  is counted in 10-second windows aligned to the wall clock, independently of profile sampling,
  with counts, errors, timeouts, connection failures, execute and fetch time, fetched rows, maximum
  and latency buckets per statement, database and transaction kind or per external host. Keys
  beyond `stats.max-keys` (SQL, default 5,000) and 1,000 hosts per window go to an overflow row
  and are counted; the open window is sent once at shutdown.
- Active transaction snapshots from the agent every 5 seconds (or the server's interval): up to
  500 running transactions, longest first, with their state (in a method, running SQL, waiting for
  a connection, calling a host, async wait), the SQL text or host of the state, thread, user and
  client IP under the configured collection modes. Dictionary entries of statistics and snapshots
  are sent before the batch, so the active transactions screen shows the SQL text at once.
- Alert screens (side menu ALERTS, each a lazy route chunk): the alert history (`/alerts`) pins the
  firing and acknowledged alerts of every or the selected application with counts by severity and
  the channel status, lists the state transitions with time, application, target, rule, severity,
  transition and its reason, value and threshold with units, duration, deliveries and who
  acknowledged, filters by range, application, environment, severity, state, rule and failed
  deliveries, and offers per row the deep link, rule edit, acknowledge with a note and mute this
  target (up to 30 days), plus 7- and 30-day alert statistics. Alert rules (`/alerts/rules`): list,
  create from a default template with the changed fields marked, every rule field, message template
  variables with completion and an error for anything not allowed, a debounced 7-day preview with
  firing times, count and longest firing, clone to another application or environment, disable
  apart from delete, and the revision history. Notification channels (`/alerts/channels`): e-mail,
  Slack and webhook with the server's destination check result, the destination shown only masked
  after saving, test send, and webhook signing key rotation shown once. In live mode the `alert`
  topic refreshes the history.
- Dashboard and XLog charts draw the application's alert state transitions of the range as markers;
  a link with `alert=<event id>` emphasises that transition and shows its summary above the charts.
- Alert API: `alert_events.reason` (PostgreSQL migration V009, a closed list: condition, cleared,
  escalated, acked, resolved, target_gone, rule_disabled, rule_deleted, no_data, data_returned;
  older events have none) in the alert events; `rule.metric` and `rule.unit` (the unit of value and
  threshold) on alert events, `GET /overview/alerts` and the dashboard's firing alerts; and
  `changed_by_name` in a rule's revision history.
- Agent token API (administrators): issue (`POST /api/v1/admin/apps/{appId}/tokens`, the token is
  shown once, 365 days by default, 1 to 3650), list, rotate (the previous token stays valid for a
  7-day grace period) and revoke with a reason. At most 5 active tokens per application. Revoking
  a token ends the agent sessions that use it at once with `UNAUTHENTICATED`; a job marks expired
  tokens and ends their sessions too, and `argos_agent_token_expiry_seconds{token_prefix}` shows the
  time left. PostgreSQL migration V010 (`agent_tokens.revoked_by`, `revoke_reason`).
- `argos-admin token create --app --env [--expires-days] [--actor] [--json]` issues an agent token
  through the server's token service (creating the application when missing).
- Remote commands (`POST /api/v1/commands`, `GET /api/v1/commands/{id}`, `/result`): thread dump,
  thread stack and self diagnostics for operators, reload config and environment dump for
  administrators. A command must reach the agent within 60 seconds, one command runs per instance
  at a time, at most 10 per instance per minute, and a command ends as expired when its session
  ends or no result arrives within 120 seconds. Results (up to 1 MiB) are stored compressed and
  encrypted with the master key for 30 days, readable only by the requester and administrators,
  with thread dumps parsed into threads and deadlocks.
  PostgreSQL migration V011 (`commands` columns, `config_changes` columns, `app_config`).
- Agent configuration changes (administrators): propose a change with a required reason and see the
  diff and, per instance, the keys its `remote-config.allow` refuses; apply it to the connected
  instances (each receives only the keys it allows) with per-instance results (applied, partial,
  rejected, offline, pending), hand pending values to instances in their next hello, revert a change,
  and see configuration drift. Server-side validation follows the agent key reference (unknown,
  immutable, out-of-range values and masking reductions are refused). The kill switch turns an
  application's agents off or on with a reason. Operators see an instance's effective configuration
  (`GET /api/v1/instances/{id}/effective-config`).
- Application management: create (`POST /api/v1/apps`), update group, Apdex T, registration policy
  and `app_packages` (`PATCH`), delete with name confirmation (tokens revoked, sessions closed,
  alert rules removed), archive and delete instances, per-service Apdex overrides
  (`PUT`/`DELETE /api/v1/apps/{appId}/services/{serviceId}/apdex`), and the pending registration
  queue (`/api/v1/admin/pending-registrations`, approve or reject). `GET /apps/{appId}` returns
  `app_packages`, and the transaction and error screens show the application's own stack frames in
  bold. PostgreSQL migration V012 (`apps.app_packages`, soft delete of applications and users).
- Deployment and custom events: `POST /api/v1/events` with a session or with an application's agent
  token as `Authorization: Bearer` (for deployment scripts, 60 per application per minute), and
  `DELETE /api/v1/events/{eventId}` for administrators.
- Own sessions: `GET /api/v1/auth/sessions` and `DELETE /api/v1/auth/sessions/{id}`, shown on the
  account page with an end-session button; administrators can delete users
  (`DELETE /api/v1/admin/users/{id}`). The setup wizard has an optional first-application step that
  registers the application, shows its agent token once and the `-javaagent` options.
- The realtime `alert` push carries `rule` as `{id, name, metric, unit}` like the REST API.
- Async work joins its transaction: tasks handed to `ThreadPoolExecutor`,
  `ScheduledThreadPoolExecutor`, `ForkJoinPool` (including parallel streams), the JDK 21+
  `ThreadPerTaskExecutor` (`Executors.newVirtualThreadPerTaskExecutor()`), Tomcat's executor and
  Spring's `ThreadPoolTaskExecutor`, `ThreadPoolTaskScheduler` and `SimpleAsyncTaskExecutor`
  (so `@Async` methods), and the eight `CompletableFuture.*Async` methods carry the request's
  transaction, so their SQL, HTTP calls and steps show in the same transaction (with
  `async.step=true` under one ASYNC step per task). Tasks are never wrapped: `remove()`,
  `cancel()` and the queue stay the application's own, executors outside the allow-list are not
  transformed, and a pooled thread that runs a task without a transaction sees none. Application
  task classes defined after the agent started carry the transaction in an added field, other
  tasks in a weak map.
- Virtual threads (JDK 21+): transactions on virtual threads (Spring Boot
  `spring.threads.virtual.enabled`, Tomcat `tomcat-handler-N`) are flagged, their thread names
  are normalized to one dictionary entry, CPU time and allocated bytes are sent as missing instead
  of a wrong value, and the agent reports virtual thread use to the server once per session.
- The agent answers remote commands: thread dump (JSON with locks and deadlocks on every JDK,
  virtual threads on JDK 21+ through `HotSpotDiagnosticMXBean.dumpThreads`, the running
  transaction of each thread, cut at 1 MiB), thread stack of a running transaction or thread, self
  diagnostics (version, effective configuration with sources, allow list, queues, agent threads),
  environment dump (environment, system properties and JVM arguments masked inside the agent by
  fixed key and value rules, including `-Dkey=value` items of `JAVA_TOOL_OPTIONS` and similar
  variables; `commands.env-dump.mask-patterns` only adds patterns and no setting turns masking off)
  and config reload (re-reads the local configuration and lists the keys that need a restart).
  Commands are a closed list run one at a time on one agent thread, with a time to live counted
  from receipt and per-type cooldowns. The self diagnostics list each instrumentation module with
  its state and, when it does not run, the reason.
- The agent applies remote configuration changes and the `ServerHello` configuration: only keys in
  its local `remote-config.allow` are applied (a hard cap; an allowed remote value wins over the
  local one), other keys are refused with a reason and keep their local value, keys the running
  agent cannot change are refused as `restart_required`, and the result carries the applied keys,
  the refused keys and the new configuration hash. The remote kill switch turns instrumentation off
  and on while the session and metrics continue. Custom method patterns that start with a forbidden
  prefix (`java.`, `io.argos.` and others) are ignored, a pattern matching more than 500 classes is
  disabled, and a change retransforms at most 50 classes once per minute.
- Batch transactions: Spring `@Scheduled` methods (Spring 5.3 to 7.0), Quartz jobs (2.3 to 2.5)
  and Spring Batch steps (4.3 to 5.2) are recorded as transactions of kind `BATCH`
  (`BATCH <class>#<method>` without proxy suffixes, `BATCH <group>.<name>`, `BATCH <job>.<step>`),
  kept apart from the HTTP statistics, Apdex and throughput. A job started inside another
  transaction is a `METHOD` step of it, and a chunk step with more steps than
  `sampling.profile.max-steps` is folded into summary entries instead of one step per item.
- Log correlation: log events created inside a transaction carry `argos.trace_id` and
  `argos.tx_id` in their MDC copy (Logback 1.2 to 1.5, Log4j 1.2 and reload4j, Log4j 2 with the
  garbage-free thread context map), also after the application's `MDC.clear()`, on executor
  threads of the transaction and through Logback's `AsyncAppender`.
- `capture.user-id.source: mdc:<key>` reads the user id from the MDC of the transaction's log
  events; a transaction without such an event is counted as `mdc_absent`.
- Custom method steps: `instrumentation.custom-methods` items record a `METHOD` step per call
  (`Class#method`, once through proxies), and `as-transaction: true` items begin a `BATCH`
  transaction when the thread has none. Items are applied from the first class loaded after the
  agent starts, a pattern over the class limit stops recording at once, and a pushed value with a
  forbidden item is rejected as a whole while a local one ignores only that item.
- Server metric `argos_ch_memory_tracking_bytes`: the memory the ClickHouse server tracks now
  (`system.metrics` `MemoryTracking`), read by the storage health job every minute.
- `instrumentation.app-packages` is derived from the main class when unset (`Start-Class`,
  `Main-Class` or the command line, top two package levels) and shown in the effective
  configuration and self-diagnostics.
- Sample app `shop-batch`: `POST /batch/run/{job}?detached=true` runs a job on its own thread and
  `GET /batch/executions/{id}` reports it; `SAMPLE_BATCH_PER_ITEM_SQL=true` runs one query per
  item. The shop apps' `/api/mdc-log` takes `clear`, `async`, `user` and `tag`.
- Slow transaction stacks: a transaction running longer than its slow threshold gets `STACK`
  steps sampled by the agent's watch thread (at most 10 threads per second, 5 samples per
  transaction 3 seconds apart, 64 frames, `class.method(file:line)`; one batched JMX call for
  platform threads, `Thread.getStackTrace()` for virtual threads). The cost is counted in
  `argos_agent_stack_capture_seconds_total` and the self-diagnostics, and the sampler turns itself
  off after ten cycles in a row over 50 ms.
- Error stacks: an exception that ends a transaction adds an `ERROR` step with up to 30 frames
  (`capture.stack-depth`) and sends its first application frame (`instrumentation.app-packages`,
  without the line number) as `error_top_frame_id`, so error groups get a representative stack and
  top frame from real agent data. `instrumentation.app-packages` now applies while the agent runs.
- `java -jar argos-agent.jar --check [--config <file>] [--json] [--classpath <dir|jar>...]`: a
  pre-flight check of the configuration, token, DNS/TCP/TLS connection, session start, clock
  offset, JDK, library compatibility of the application's class path and log directory, as a table
  or one JSON object, with exit code 0 (no failure), 1 (a failure) or 2 (usage error). Tokens are
  shown as `argos_****`; the check registers an instance named `<instance>-check` and ends it at
  once with `AgentBye`.
- `app.version` defaults to the `Implementation-Version` of the jar or WAR started with
  `java -jar`; it is sent in the session start and in JSONL records.
- Agent self-throttling: while the agent's threads use more than 1 % of the host's CPU in a
  minute, the profile `normal-rate` is halved and the event `SELF_CPU_HIGH` is sent; the rate
  returns after five calm minutes.

- Audit log API: `GET /api/v1/admin/audit` (administrators; time range, actor, action and target
  filters, cursor paging) and `GET /api/v1/admin/audit/export` (CSV, at most 10,000 rows); both
  reads are themselves audited. The event catalogue has 74 names; administration, configuration,
  command, token, login, agent authentication (`AGENT_AUTH_FAILED`, `AGENT_AUTH_BLOCKED`,
  `AGENT_SESSION_REJECTED`, rate-limited per address), migration and server start events are
  recorded. `argos.audit.sinks` adds JSON Lines files (`file:<path>`, rolled at 100 MiB, 10 files)
  and RFC 5424 syslog over UDP, TCP or TLS next to PostgreSQL; records PostgreSQL does not take go
  to `argos.audit.fallback-file`. Audit partitions are created ahead and dropped after
  `argos.retention.audit` (at least 90 days, also enforced in the database).
- Privacy deletion: `POST /api/v1/admin/privacy/deletions` with `user_id` or `ip`, `from`, `to`
  and optional `app_id` in the JSON body queues a job (at most 30 days per request, 5 requests per hour per administrator) that deletes the
  matching raw transactions and profile steps (plain and hashed user ids, masked addresses
  included) and reports its state, tables and deleted rows at
  `GET /api/v1/admin/privacy/jobs/{jobId}`. The target value is stored only as a SHA-256 digest.
- Retention settings: `GET/PUT /api/v1/admin/settings/retention` shows the effective retention per
  table and its source and changes it (shortening needs `confirm`); the server applies TTL changes
  at start, on change and with `argos-admin retention apply`, without rewriting parts on an
  extension. `GET/PUT /api/v1/admin/settings/privacy` changes the default client address mode.
- Emergency retention: from 90 % ClickHouse disk use the server drops one daily raw partition per
  minute (profile steps first, then metrics and statement statistics, then transactions; never
  today's partition or rollups) until use is below 85 %, records each drop as an event and an
  audit row, pauses inserts at 95 % (the write-ahead log keeps the data) and resumes at 93 %.
- Storage status: `GET /api/v1/admin/storage/health` reports parts, delayed inserts, free disk,
  query p99, the last TTL run, emergency retention and the dead-letter area;
  `GET /api/v1/server/health` reports disk use and certificate expiry. The server writes its own
  metrics every 5 seconds as application 0 for the collection status screen, and the
  `argos_ch_*`, `argos_server_disk_usage_ratio`, `argos_retention_emergency_drops_total`,
  `argos_writer_dead_letter_*`, `argos_audit_*` and `argos_privacy_jobs` series are exposed on the
  authenticated `/actuator/prometheus`.
- Dead-letter area: a block ClickHouse rejects three times with a schema or format error is moved
  to `argos.writer.dead-letter.dir` (owner-only files, at most 1 GiB, never deleted
  automatically) in a format that can be inserted again, and its rows are counted as
  `argos_collector_dropped_total{reason="dead_letter"}`.
- Collector TLS: the certificate and key reload without a restart when the files change
  (`argos.collector.tls.reload.watch`), through `POST /api/v1/admin/tls/reload` or
  `argos-admin tls reload`; open connections keep their session. Encrypted PKCS#8 keys are read
  with `argos.collector.tls.key-password-file`. `argos_server_tls_cert_expiry_seconds` feeds the
  certificate expiry alert template.
- `argos-admin` commands: `migrate`, `config effective|check`,
  `cleanup instances|dictionary|commands`, `token issue|revoke|rotate`,
  `setup-token regenerate`, `tls reload`, `retention show|apply`, `secrets rekey` (alias
  `master-key rotate`), `bundle` and `version`, all with `--json` and audited
  (`docs/ops/admin-cli.md`). `argos-admin bundle` writes a diagnostic archive (owner-only) with
  masked logs and configuration, schema history, ClickHouse system tables and instance and token
  summaries.
- Hashed user ids: each application has a random user id salt, sent to agents in the session
  start, so `capture.user-id.mode: hashed` works, and user id search finds hashed rows from the
  plain value.
- `argos.collector.public-address` sets the collector address shown in the first-application
  step of the setup wizard (default: the browser's host and port 9100).
- Rollup rebuild, consistency checks and Apdex reapply write the audit events `ROLLUP_REBUILT`,
  `CONSISTENCY_CHECKED` and `APDEX_REAPPLIED`; `argos.ops.reconcile.enabled=false` turns the
  nightly consistency job off.
- Administration screens under `/admin`: applications (create, edit, delete, install guide with
  the `-javaagent` options, `argos-agent.yml`, the `--check` command and a canary checklist),
  pending agent registrations (approve with a token shown once, or refuse), instances (archive,
  delete archived ones, compatibility and configuration drift markers) with a collection status
  ranking tab (dropped data, ingest lag, clock skew, excluded transactions), agent tokens (issue
  with an expiry shown once, rotate with a 7-day grace, revoke with the number of sessions it
  ends, expiry warnings), configuration changes (current effective configuration, required
  reason, diff preview, targets, per-instance results with refused keys, history, revert) and the
  kill switch behind a confirmation dialog, privacy (per-instance capture modes, default IP mode,
  deletion requests with job status, retention settings), users and roles (create with an initial
  password shown once, roles, sessions, unlock), the audit log with filters and a server CSV
  export, and version and open source license pages. Menus follow the role matrix; every screen
  is a lazy route chunk.
- Global search at `/search`: a transaction id or trace id opens the record directly, `user:`
  and `req:` search the selected range (up to 24 hours), other text lists matches, and a user
  search with no result says when user id capture is off.
- `GET /api/v1/about` (server version, commit, protocol version, agent version counts, uptime)
  and `GET /api/v1/about/licenses` (server, UI and agent third-party lists; a section reports
  `not_generated` when the build carries no list).
- `App.tokens_active` in the applications API, and `default_thresholds_ms` in
  `GET /apps/{appId}/active` from the new `argos.ui.active-thresholds-ms` setting (default
  1000, 3000, 8000 ms): the active transactions screen uses it unless the viewer set their own.
- The dashboard's "N excluded" note links operators and administrators to the collection status
  ranking.

### Changed

- Privacy deletion requests carry the user id or client address in the JSON body of
  `POST /api/v1/admin/privacy/deletions`. The query-string form
  `DELETE /api/v1/admin/privacy/subjects?user_id=|ip=...` is removed (404), so the value no longer
  reaches the access logs of the server or of a proxy. The admin privacy screen uses the new
  request.
- Agent: the default `remote-config.allow` names the keys a collector may change safely while the
  agent runs: `sampling.**`, `instrumentation.disabled`, `instrumentation.ignore-*`, `log.level`,
  `enabled`, `transaction.slow-stack.*`, `capture.error-message-max-length`, `capture.stack-depth`,
  `instrumentation.app-packages`, `async.step`, `active.snapshot-interval-seconds`,
  `metrics.interval-seconds` and `transaction.max-duration-ms`. Keys that widen what is collected
  still need the local list. Write a local list to keep the earlier five patterns.
- Agent: the context stores (task, connection and request state attached to application objects)
  share one bound of 100,000 entries, about 7 MiB, besides the 100,000 entries per store; an entry
  over it is dropped and counted.
- Error groups: the fingerprint now includes the top application frame the agent sends, so the
  groups of an error recorded by an older agent split once after the agent upgrade, and again when
  `instrumentation.app-packages` changes. A redeploy that only moves lines keeps its groups.
- Agent `STACK` steps carry line numbers (`class.method(file:line)`).
- `ARGOS_HTTP_BIND` and `ARGOS_GRPC_BIND` are host-side publish addresses of the Compose
  installation and no longer server settings: the server listens on every interface unless
  `server.address` or `argos.collector.bind` sets another address.
- Docker Compose installation: `argos-env-init.sh` budgets the container limits (server +
  PostgreSQL + ClickHouse at most 0.9 x RAM, ClickHouse `max_server_memory_usage` = 0.9 x its
  container), uses a minimum-spec tier with a warning on 8 GB hosts (7168 to 7964 MiB visible to
  Docker: ClickHouse down to 2.5 GiB, then the heap down to 1.5 GiB), refuses hosts below 7168 MiB,
  and warns when a plain-HTTP public URL on a host other than localhost will make signing in fail;
  the sign-in page and the last setup step then say that the browser did not keep the session
  cookie; the server knows the audit event `TOKEN_ISSUED`.
- Agent service names: the Spring MVC handler pattern is read on the REQUEST dispatch only and keeps
  the request's method; a handler method without a pattern gives `Controller#method`, the Servlet 4
  `HttpServletMapping` pattern names requests without a Spring handler, and otherwise the path is
  normalized (numbers, UUIDs and hex strings of 32 or more characters become `{*}`; query strings and
  fragments are dropped). Web applications with a context path prefix their names with it.
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
- The agent test harness's `FakeCollector` speaks `argos.v1` from `proto/` instead of the
  proof-of-concept protocol and records sessions, heartbeats, clock estimates, `AgentBye` and
  deduplicated batches; new faults cover WAL-full drops, paused session reads and hello rejections in
  the protocol's check order.
- The development-only test exporter `noop` now encodes like the transport (protobuf) before it drops.
- The agent log queue is capped at 1 MiB of pending text; lines beyond it are dropped and counted in
  `argos_agent_log_dropped_total`.
- Known agent limitations in `docs/agent-config.md` now include the transport: Java 8 before 8u252,
  the JDK's own `socket_<n>` temporary file on Windows with JDK 16 or later, the warnings of plaintext
  and skipped verification, the reaction to a refused session and the meaning of
  `direct_memory_bytes`.
- Query API and OpenAPI document: unsigned 64-bit keys (transaction ids, dictionary and error group
  keys) are declared as decimal strings with the pattern `^[0-9]{1,20}$` and linted, request schemas
  mark the fields the server always requires, instance comparison outliers are judged against the
  other instances (from 3 instances on), and the enforced query API bench also requires each cold
  call within twice its target.
- Web UI delivery: the server gzips the UI's HTML, CSS, JavaScript and SVG of 2 KB or more (API JSON
  stays uncompressed) and lets browsers cache the hashed `/assets/` files for a year, while the page
  and the API stay `no-store`; the OpenAPI document lists the values of `ip_mode`, `roles`,
  `permissions` and the CSV `format` parameter and marks user names as untrusted; input and select
  borders now have 3:1 contrast; a table column with a `filterValue` key offers no include/exclude
  for a row without a key; and every end-to-end test fails on any browser CSP violation, also in
  another tab or before a navigation.
- The XLog canvas proof of concept (`/xlog-poc`) and its synthetic data generator are no longer part
  of the production build: `pnpm -C ui perf` and the `preview` end-to-end project build them with
  `vite build --mode perf` into `build/perf-dist`. `XLOG_PERF_TARGET=route` runs the frame-time
  spec against the XLog screen of the end-to-end stack and also measures the selection query and
  the time from the pointer release to the painted summary and first list row (including the GPU
  frame). Its realtime scenario plays the WebSocket protocol stub (100,000 points, then 500 points/s),
  and `XLOG_PERF_ROUTE_UNSAMPLED=1` measures an unsampled window of about 100,000 points. The bundle
  report fails when a production chunk holds a module of the proof of concept.
- UI translations load per screen: the `common`, `shell` and `auth` catalogs stay in the entry
  bundle, the `xlog`, `tx`, `trace` and `dashboard` catalogs load as same-origin chunks together with
  their route (`ui/src/i18n/routeNamespaces.ts`), and switching the language loads the new language
  first. The CSV export helper loads on the first export. The initial bundle path shrinks from
  372.3 to 359.1 KiB gzip.
- `GET /overview`, `GET /apps` and `GET /apps/{appId}` take the application state from the instance
  states: the worst state of its instances that are not archived (DOWN, then STALE, WARN, UP; DOWN
  without instances), and `instances.up` / `instances_up` count the instances that are UP or WARN,
  instead of counting live agent sessions.
- `argos-compose.sh token create` runs `argos-admin token create` inside the healthy server
  container instead of writing PostgreSQL directly: the server's token service creates the token,
  keeps the limit of 5 active tokens and writes the audit row. Without `--expires-days` the token
  now expires after 365 days (previously never).
- `argos.retention.stat-days` and `argos.retention.metric-days` may differ.
- An application's registration policy is optional: when it is not set, agents' automatic
  registration follows `argos.agent.auto-register.<env>`; applications created without a policy
  and existing applications with `approval` inherit the environment default, and the API returns
  `registration_policy_inherited`.
- User id search on applications with hashed user ids no longer answers 422.
- Instance down alerts are resolved as `target_gone` when the instance is archived or deleted.
- `instrumentation.custom-methods` items no longer take a step type: an item is
  `<class glob>#<method glob>[|<as-transaction>]` (yml: `pattern` and `as-transaction`) and always
  records a `METHOD` step. An item in the earlier format with a step type (`|SQL`, `|METHOD|true`,
  `step-type:` in the yml) is ignored with one warning while the other items apply; a pushed value
  with such an item is rejected as a whole (`FORBIDDEN_PATTERN`, `step_type_suffix`).
- Service statistics give batch transactions no Apdex: a service with only `BATCH` transactions
  has `apdex` null in `GET /api/v1/apps/{appId}/services/stats` (shown as "—"), and a service's
  Apdex counts only its non-batch transactions with `kind=ALL`.
- `CUSTOM_METHOD_CHANGED` is audited when a remote change of the custom methods is answered:
  `success` by the applying user when an agent applied it, `rejected` when every agent or the
  server refused it, with the per-instance outcome.
- The agent configuration guide explains that Log4j 2's default thread context maps get no
  correlation keys and how to turn on the garbage-free map
  (`-Dlog4j2.garbagefree.threadContextMap=true`).

### Removed

- Agent proof-of-concept configuration keys `exporter` and `exporter.file.*`: they are now unknown
  keys. Test exporters are chosen with the development-only key `argos.test.exporter` (`file:<dir>`,
  `noop`, `drop`), which release builds ignore.

### Fixed

- Trace view: a callee whose caller started in the same millisecond as an earlier transaction of
  the trace (a caller and its first callee) is placed under its real caller with its caller step.
  The parent span id of the incoming traceparent now decides whenever the trace holds that
  transaction; the time overlap is the fallback.
- The overview's firing alerts panel draws only as many alerts as fit next to the application table
  and links the rest as "+N more" to the alert history, so the overview no longer scrolls at
  1366x768 when many alerts fire.
- The instance environment tab reads the agent's environment dump format (environment, system
  properties and JVM arguments as tables, masked values marked, cut and unavailable sections
  named) and the agent's self-diagnosis format (effective configuration as a map with sources,
  module state with the reason a module does not run, shown in the module status).
- The instance environment tab no longer opens the latest self-diagnosis result by itself: it is
  read (and audited) only when the user selects "Show latest self-diagnosis".
- The agent's environment dump no longer masks long paths that mix `/` with `-` or `_` (such as
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`): a long run counts as a secret only within one
  alphabet (standard base64, URL-safe base64 or hex).
- The overview's firing alerts panel no longer truncates the rule name to its first letter, and the
  dashboard's firing alerts show value and threshold with their units.
- The acknowledged rows of the overview's firing alerts open the alert history on acknowledged
  alerts.
- Visual test baselines mask the whole alert badge of the top bar, including its count bubble.
- Every `alert` push of the realtime WebSocket carries the transition's `reason`, the same value as
  the alert history (previously only `target_gone`).
- The rule parts of `GET /api/v1/alerts/templates` carry their template's `template_key` and
  `template_version` instead of null.
- The agent counts an SQL execution over the statistics key cap once in
  `argos_agent_stats_overflow_total` (its result set fetch no longer counts it again), and counts
  statistics windows let go without a flush in the new `argos_agent_stats_windows_dropped_total`.
- In a container with a memory limit, `system_memory_used_bytes` is the working set (usage less
  inactive page cache), so page cache no longer makes a container look close to its limit.
- The `mount` label of the disk metrics replaces home directories (`/home/<name>`, `/Users/<name>`,
  `C:\Users\<name>`) with `~`, so no user name is sent.
- The alert screens no longer add the application and environment selects as a separate chunk to
  the first screen's download.
- A transaction's SQL count and SQL and connection wait times no longer lose updates when its
  async work runs SQL on several threads at once.
- The agent no longer drops a statistics window while the flush is running but late (a delayed
  flush thread under load could lose a whole window).
- A JDK `HttpClient.sendAsync` step is closed before the application's future completes on every
  JDK, also when the JDK completes the exchange's dependents in first-in order (JDK 21+).
- On Java 8 the agent's shutdown wait no longer depends on the wall clock.
- A pushed custom methods change that is refused (`retransform_limit`) no longer logs the
  "item disabled" WARN; the WARN follows only an applied change.
- The realtime `overview` status now uses the same instance basis as `GET /overview` and
  `GET /apps`: every instance that is not archived counts, an untracked one as down, and archived
  instances are left out.
- After the retention settings change (`PUT /admin/settings/retention`), the query API's raw,
  1-minute and 1-hour retention boundaries (`retention_exceeded`) follow the new values without
  a server restart.
- The `CUSTOM_METHOD_CHANGED` audit row names the administrator who applied the configuration
  change, read from the stored change, also when the agent's result arrives after the server
  forgot the pending change.

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
- Server sessions and secrets: access, refresh and setup tokens are kept only as SHA-256 hashes and
  passwords only as bcrypt (cost 12) hashes. They are compared in constant time, never returned
  after issue and never logged. The single setup-token log line is the one exception. Log masking
  also covers the session cookies, the `X-Argos-CSRF` header, JWT-shaped values, the JWT signing
  key settings (`argos.jwt-key`, `ARGOS_JWT_KEY`, the previous key) and PEM private keys.
- Login failures look the same for unknown users and wrong passwords, a dummy bcrypt check included,
  and unknown names are locked out like real ones; their failure counts are kept in PostgreSQL, so
  a restart or another node does not reset them. Spring Security runs without HTTP sessions,
  form login, HTTP basic or `JSESSIONID`, and a revoked session is refused within 5 seconds at
  most.
- Every HTTP response carries `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`,
  `Permissions-Policy` and the cross-origin opener and resource policies (HSTS on secure requests).
  API responses are always `Cache-Control: no-store`. CORS preflights are refused. Rate limit keys
  cannot be dodged with encoded paths or forged `X-Forwarded-For` headers (forwarded headers count
  only from a trusted proxy).
- API error bodies never carry stack traces, exception classes, SQL or file paths; 5xx bodies hold
  a fixed message and the request id only.
- The agent sends its token only in the gRPC call metadata: never in the hello, a batch, a log line
  (the `log.level: debug` frame logs of the gRPC library are not written, and `Bearer` values and
  `argos_` runs are masked in library messages), the self-diagnostics or the console. The agent sets
  only its relocated Netty properties and opens no network connection other than the configured
  collector.
- Alert channel destinations are validated when saved, updated, tested and before every delivery:
  `https` only by default, loopback, private, link-local, metadata and other reserved ranges
  (including IPv4 inside IPv6 forms) and the server's own addresses are refused unless an operator
  lists them in `argos.alert.webhook.allow-cidrs`; the connection goes to the resolved and checked
  address with certificate and host name verification, redirects are never followed and response
  bodies are never shown or stored. Channel targets, recipients and webhook signing keys are stored
  encrypted with the master key (AES-256-GCM envelopes; the previous key still decrypts) and
  returned masked. Plaintext SMTP is refused unless `argos.alert.smtp.insecure` is set,
  which logs a warning at startup.
- Agent tokens are stored only as SHA-256 hashes with a 14-character prefix, returned once with
  `Cache-Control: no-store` and never logged; the revoke reason is not written to the audit log.
  Remote command results are encrypted at rest (AES-256-GCM envelopes) and their bodies never
  logged; their identifiers are random UUIDs and other users' results answer 403. Remote
  configuration keys outside an instance's `remote-config.allow` are never pushed to it.
- The server refuses to start without a usable master key: missing, malformed, or (on POSIX)
  readable by group or others unless `argos.security.master-key.permissive` is set, which warns
  at every start; on Windows the file permission cannot be checked and the operator restricts it.
  `argos-admin secrets rekey` re-encrypts alert channel settings, webhook signing keys, command
  results and user id salts under the current or a new key and records `MASTER_KEY_ROTATED`.
- The runtime database account cannot update, delete or truncate audit rows, and audit partitions
  younger than 90 days cannot be dropped. Audit records, logs and privacy jobs never contain the
  deletion target, and the diagnostic bundle masks tokens, passwords, the master key, channel URLs
  and the metrics token.

[Unreleased]: https://github.com/argos-apm/argos/commits/main
