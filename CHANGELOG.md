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

### Changed

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

[Unreleased]: https://github.com/argos-apm/argos/commits/main
