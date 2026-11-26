<!-- SPDX-License-Identifier: Apache-2.0 -->

# Compatibility

This page says which versions of Argos APM work together, which interfaces are a public contract,
how a contract is changed or retired, and how the build checks that nothing changes by accident.
It applies in full from 1.0.0. Before that, read [Version policy](#version-policy) first: 0.x
releases make no compatibility promise.

Requirement IDs in parentheses (O-R01 and so on) refer to the product plan
([01-product-plan.md](01-product-plan.md) section 10.4, Korean).

## Version policy

- **One version for everything.** The agent, the server, the UI, the container images and the
  release assets of one release share one [SemVer](https://semver.org/) version `X.Y.Z` (O-R01).
  Pre-releases are `X.Y.Z-beta.N` and `X.Y.Z-rc.N`; tags are `vX.Y.Z[-beta.N|-rc.N]`
  ([RELEASING.md](../RELEASING.md#versioning)).
- **Patch** (`X.Y.Z+1`): fixes only. No contract below changes.
- **Minor** (`X.Y+1.0`): new features. Contracts change only by addition (a new key, a new optional
  field, a new endpoint, a new metric). Every minor comes with upgrade notes in
  `docs/upgrade/X.Y.md` (written for the first minor release, [RELEASING.md](../RELEASING.md#before-the-release)).
- **Major** (`X+1.0.0`): the only place where a contract can be removed or changed incompatibly,
  and only after the deprecation period of [Deprecation](#deprecation).
- **0.x is the exception.** Releases before 1.0.0 are pre-releases for evaluation. They make **no
  compatibility promise**: a 0.x minor may change any contract below without a deprecation
  period. What 0.x does provide is an upgrade path from the previous minor: an installation of
  `0.(n-1)` upgrades to `0.n` with the documented procedure and keeps its data (O-R01). Breaking
  changes are still listed in the CHANGELOG (`breaking-change` label) and the snapshot tests of
  [How compatibility is checked](#how-compatibility-is-checked) still fail on an unannounced change.
- **The protocol has its own version.** The agent to server protocol (gRPC package `argos.v1`) has
  an integer version in [`proto/PROTOCOL_VERSION`](../proto/PROTOCOL_VERSION), currently `1`.
  Adding messages, fields or enum values does not raise it. A changed meaning of an existing field
  or a new required behaviour raises it to `current + 1`, and the server keeps accepting the
  previous protocol version for at least two minors. `argos.v1` itself only takes additive changes;
  an incompatible protocol is a new package (`argos.v2`).

## Agent and server versions

Upgrade the **server first**, then replace the agents on the applications' own restart schedule.

| Server | Accepts agents | Refuses |
|---|---|---|
| `X.Y.Z` (any patch, any pre-release suffix) | `X.(Y-2).*` to `X.Y.*` (any patch, any suffix) | newer agents (`X.(Y+1)` and later, `(X+1).*`): `AGENT_VERSION_TOO_NEW`; older agents and unparsable versions: `AGENT_VERSION_TOO_OLD` |
| `(X+1).0.Z` (major transition) | `(X+1).0.*` and, for this one minor only, the agents of the last minor of `X` | as above; `(X+1).1` no longer accepts `X.*` agents |

Examples: a server 1.5.2 accepts agents 1.3.0 to 1.5.x; it refuses agent 1.6.0 and agent 1.2.9.
A server 0.4.0 accepts agents 0.2.x to 0.4.x.

What happens:

- **Protocol check first.** The agent's `protocol_version` must be in the server's range
  (`[1, 1]` today). Outside it the server answers `FAILED_PRECONDITION` with reason
  `PROTOCOL_UNSUPPORTED`.
- **Refused agent.** The server answers the agent's hello with `FAILED_PRECONDITION`, reason
  `AGENT_VERSION_TOO_NEW` or `AGENT_VERSION_TOO_OLD` and the message
  `Agent version is not supported: server <version> accepts agent versions <X.(Y-2)>.x to <X.Y>.x`.
  The agent logs one ERROR line, stops connecting for good and keeps the application running
  unchanged; it does not retry, so restart the application after you replace the agent. The
  pre-flight check `java -jar argos-agent-<version>.jar --check` reports the same refusal before you
  install ([agent-config.md](agent-config.md#pre-flight-check---check)).
- **Lower bound warning.** An agent of the lowest accepted minor (`X.(Y-2)`) is accepted, but its
  instance gets the WARN reason `old_version` in the UI and the API: the next server minor will
  refuse it.
- **Unknown server version.** A server built without build information (a development build
  without a version) accepts every agent version.
- **Before you upgrade.** `argos-compose.sh upgrade` reads the versions of the connected agents and
  warns about every agent the new server will refuse; it refuses a downgrade and a skipped major
  version ([install/compose.md](install/compose.md#15-backup-upgrade-and-diagnostics)).

Source of truth: `MINORS_BEHIND = 2` in
[`AgentVersionRange`](../server/src/main/java/io/argos/server/session/AgentVersionRange.java)
(tested by `AgentVersionRangeTest`), and `MIN_PROTOCOL_VERSION` in `HelloService` with the
generated `io.argos.proto.ProtocolVersion.CURRENT` from `proto/PROTOCOL_VERSION`.

**Implementation status.** The server in this tree implements the same-major range. The major
transition grace (second table row) is not implemented yet, because no major transition has happened;
it is added before the first release of a new major. 0.x agents are not covered by it.

## Public contracts

Changing anything in this list is a breaking change (O-R03): from 1.0 it needs the deprecation
procedure below and a major release. Internal names that are not listed here (Java packages, class
names, database tables, log message text, UI markup) can change in any release.

| Contract | What is fixed | Reference |
|---|---|---|
| Agent configuration keys | Every public key of `argos-agent.yml`, with its type, default and remote-change flag, and its two other forms: the system property `-Dargos.<key>` and the environment variable `ARGOS_<KEY>` (upper case, `.` and `-` as `_`). The fixed names `argos-agent.yml`, `-Dargos.config`, `ARGOS_CONFIG`, `ARGOS_TOKEN` and the agent argument `config=<path>`. The `debug.*` diagnostic keys are listed too; `argos.test.*` keys exist only in development builds and are not a contract. | [agent-config.md](agent-config.md) |
| Server configuration keys | Every `argos.*` key of the server with its type and default (Spring relaxed binding gives each one the environment form `ARGOS_...`), the short environment variables of the Compose installation (for example `ARGOS_CH_URL` for `argos.clickhouse.url`) and the Spring keys operators rely on (`server.port`, `server.address`, `management.server.port`, `management.server.address`). | `argos-admin config effective` ([ops/admin-cli.md](ops/admin-cli.md)), [install/compose.md](install/compose.md#7-configuration) |
| REST API | Everything under `/api/v1`. Within `v1` only additions: new endpoints, new optional request fields, new response fields, new enum values that clients must tolerate. Removing or renaming a field, changing its type or making an optional request field required is breaking. | [api/openapi.yaml](api/openapi.yaml) (generated, never edited by hand) |
| WebSocket frames | The frames of the real-time endpoint `/ws`, in both directions (frame types, field names and types). | `server/src/test/resources/io/argos/server/realtime/ws-frame-vectors.json` |
| Agent to server protocol | The gRPC package `argos.v1` and the protocol version rules above. | [`proto/`](../proto/README.md) |
| Metric names | The names, kinds and label keys of the metric series the agent reports (OpenMetrics names such as `jvm_memory_used_bytes{area}`) and of the server's own metrics on the management port (`/actuator/prometheus`). | `agent/agent-core/src/test/resources/metrics/metric-names.txt` |
| Alert webhook payload | The JSON body of webhook notifications, versioned by its top-level field `schema_version` (currently `1`), and the signature header `X-Argos-Signature` (`t=<unix seconds>,v1=<HMAC-SHA256>`). Within one `schema_version` fields are only added; any other change is a new `schema_version`. | `server/src/test/resources/io/argos/server/alert/channel/webhook-payload-v1.json` |
| Command lines | Arguments, options, output formats (`--json`) and exit codes of `argos-admin` (0 success, 1 failure, 2 usage error, 4 refused by a limit), of the agent's `--check` (0 no FAIL, 1 FAIL, 2 usage error), of `deploy/verify-release.sh` (0 verified, 1 a check failed, 2 usage error) and of the Compose scripts (`argos-env-init.sh`, `argos-compose.sh`, `smoke.sh`, `backup.sh`, `restore.sh`). | [ops/admin-cli.md](ops/admin-cli.md), [agent-config.md](agent-config.md#pre-flight-check---check), [install/verify-release.md](install/verify-release.md), [install/compose.md](install/compose.md) |
| Directory layout | The server image's volumes `/data` (WAL, setup token, signing key, temporary files) and `/logs`, its user `10001:10001` and ports `8080` (HTTP) and `9100` (gRPC); the Compose installation's files next to `compose.yaml` (`.env`, `secrets/`, `tls/server.crt`, `tls/server.key`, the backup directory); the agent's `argos-agent.yml` next to the agent jar and its default log directory `../logs` relative to the jar. | [install/compose.md](install/compose.md#services-ports-and-volumes), [agent-config.md](agent-config.md) |
| Log correlation keys | The MDC keys the agent adds to application log events: `argos.trace_id` and `argos.tx_id`. | [agent-config.md](agent-config.md#log-correlation) |
| Trace propagation | The W3C `traceparent` header and the `tracestate` entry with the key `argos` (value `<app>:<instance>:<service>` in lower-case hex), sent only to hosts in `propagation.argos-state.hosts`. | [agent-config.md](agent-config.md#context-propagation) |

Release assets and their names (`argos-agent-X.Y.Z.jar`, `SHA256SUMS`, the container image names
`ghcr.io/<owner>/argos-server` and so on) are described in [RELEASING.md](../RELEASING.md); a
renamed asset is announced in the CHANGELOG like any breaking change.

## Deprecation

From 1.0 a public contract is retired in three steps (O-R03, docs/19 section 7.8):

1. **Deprecate** in a minor release: an entry under `Deprecated` in [CHANGELOG.md](../CHANGELOG.md)
   naming the replacement, a WARN line at run time whenever the deprecated item is used (a
   deprecated configuration key, environment variable, endpoint or option), and a note in the
   reference documentation. The deprecated item keeps working unchanged.
2. **Keep it for at least two more minors.** The upgrade notes of each of them repeat the
   deprecation.
3. **Remove** it in the next major release, never in a minor or a patch. The CHANGELOG lists it
   under `Removed`.

Configuration is lenient in every release: an unknown key never stops the agent or the server. The
agent logs one WARN per unknown key, naming the key and where it came from, and ignores it; the
server logs one WARN per unknown `argos.*` key and `argos-admin config check` lists them. An
invalid value gives a WARN and the documented default.

No contract is deprecated at the moment.

## Database schema

- **Forward only** (O-R04). Schema changes run automatically when the server starts: PostgreSQL
  through Flyway (`server/src/main/resources/db/pg/V<NNN>__<name>.sql`), then ClickHouse through the
  server's own migration runner (`deploy/clickhouse/schema`, `rollups`, `migrations`), with the
  history in PostgreSQL. A released migration file never changes: both runners compare checksums
  and refuse to start on a changed applied file, and `./gradlew :server:checkFlywayNames` (part of
  `check`) enforces the file names.
- **Upgrade from any supported minor in one step** to the current release. A skipped major version
  and a downgrade are refused by `argos-compose.sh upgrade`.
- **Rollback to the previous minor only.** `argos-compose.sh upgrade --rollback` restores the
  previous image and the PostgreSQL data of the backup taken right before the upgrade (data written
  since then is lost). The ClickHouse schema is not rolled back: columns and tables that the new
  version added are ignored by the old one. When a later release replaces a rollup table and its
  materialized view, the old one is dropped only in the following minor, so that the rollback window
  stays open (docs/17 section 8.4; no such replacement exists yet).
- **0.x**: the same mechanisms apply; the supported upgrade path is from the previous 0.x minor.

Operator procedure: [install/compose.md](install/compose.md#15-backup-upgrade-and-diagnostics) and
[ops/backup-restore.md](ops/backup-restore.md).

## Supported environments

The 1.0 target matrix (product plan section 8, summarized). "Verified" means tested by the
compatibility matrix before a release; "unverified" means expected to work, reports welcome.

| Area | Verified | Unverified or conditional | Not supported |
|---|---|---|---|
| Application JDK | 8 (8u252 and later), 11, 17, 21, 25 (Temurin; Oracle, Corretto, Zulu checked) | Liberica, Microsoft Build, Semeru/OpenJ9 | Java 7 and earlier, GraalVM native image |
| Threads | platform threads, virtual threads (JDK 21+, Boot 3.2+; no CPU and allocation values) | CRaC restore | WebFlux (Netty) servers, Kotlin coroutines, Spring Cloud Gateway: JVM metrics only |
| Web servers | Tomcat 9.0, 10.1, 11 (standalone and embedded) | Tomcat 8.5, Jetty 10/11/12, Undertow 2.3 embedded | JEUS, WebLogic, WebSphere |
| Spring | Framework 5.3, 6.x, 7.x; Boot 2.7, 3.x, 4.x | Boot 2.7 on JDK 25 | Boot 2.6 and earlier |
| Servlet API | javax 3.1, 4.0; jakarta 5, 6, 6.1 | | |
| JDBC | MySQL, MariaDB, PostgreSQL, SQL Server, Oracle | Tibero, Altibase, CUBRID, DB2 | |
| Connection pools | HikariCP 4, 5, 6; Tomcat JDBC; DBCP2 | c3p0, Druid | |
| HTTP clients | `HttpURLConnection`, Apache HttpClient 4.5 and 5.x (sync), OkHttp 3 and 4, Java 11 `HttpClient`, RestTemplate | WebClient called from Servlet MVC, Apache HC5 async, OkHttp 5 | WebClient inside WebFlux applications |
| Async | executors, `CompletableFuture`, Spring `@Async`, Servlet async, JDK 21 thread-per-task executors | SSE, `StreamingResponseBody` | `Thread.start` directly, Reactor and RxJava schedulers |
| Batch | Spring `@Scheduled`, Quartz, Spring Batch steps | configured root methods | message consumers |
| Logging (MDC) | Logback, Log4j 2, Log4j 1.x / reload4j | JUL (no MDC) | |
| Application OS | Linux (RHEL/Rocky 8+, Ubuntu 20.04+), containers with cgroup v1 or v2 | Windows Server 2019+, Kubernetes pod metadata | |
| Server | JDK 21, the bundled ClickHouse version, PostgreSQL 15+, Docker 24+, images linux/amd64 and linux/arm64 | | |
| Browsers | Chrome and Edge, latest two versions | Firefox, Safari | |

The supported library versions of each instrumentation module, with the versions its tests run on,
are in the module's README (`agent/agent-instrumentation/<module>/README.md`, section "Supported
library versions"). The latest results of the compatibility matrix, combination by combination, are
in [compatibility-matrix.md](compatibility-matrix.md) (generated; ○ verified, △ not verified, ◎ community verified).

## How compatibility is checked

Each contract has a committed snapshot and a test that compares the code with it. A change to a
contract therefore fails the build until the snapshot is updated by hand **in the same pull
request**, where reviewers see it, together with a CHANGELOG entry (and, for a breaking change,
the `breaking-change` label). The tests never rewrite their snapshots.

| Contract | Snapshot | Test (task) |
|---|---|---|
| Agent configuration keys | `agent/agent-core/src/test/resources/config/agent-config-keys.txt` (key, type, default, remote flag, kind, environment name) | `AgentConfigKeySnapshotTest` (`:agent:agent-core:test`); `AgentConfigDocTest` keeps [agent-config.md](agent-config.md) in step with the code |
| Server configuration keys | `server/src/contractTest/resources/config-keys.txt` | `ConfigKeySnapshotTest` (`:server:contractTest`) |
| Short environment variables and Spring keys | `server/src/contractTest/resources/config-env-aliases.txt` | `ConfigEnvAliasSnapshotTest` (`:server:contractTest`) |
| REST API | `docs/api/openapi.yaml` | `OpenApiContractTest` (committed spec = generated spec, lint) and `OpenApiDiffContractTest` (`:server:contractTest`); the pull request job `openapi-diff` runs `oasdiff breaking` against the base branch (`.github/scripts/check-openapi-diff.sh`) and fails without the `breaking-change` label |
| WebSocket frames | `ws-frame-vectors.json` | `WsFrameVectorsTest` (`:server:test`); the UI parser reads the same file (`ui/e2e/fixtures/wsServerFrames.test.ts`, `pnpm -C ui test`) |
| Protocol | `proto/src/main/proto/argos/v1` | `.github/scripts/check-proto.sh` (`buf lint` and `buf breaking` against the base, pull request `lint` job); `ProtocolContractTest` with the vectors `agent/agent-testing/src/main/resources/contract/argos-v1-vectors.json` (`:agent:agent-testing:integrationTest`); `ProtocolVersionTest` (`:proto:test`) |
| Agent and server versions | `AgentVersionRange.MINORS_BEHIND` | `AgentVersionRangeTest` (`:server:test`) |
| Agent metric names | `metric-names.txt` | `MetricNamesSnapshotTest` (`:agent:agent-core:test`) |
| Server self-metric names | the list of docs/12 section 9.4 | `SelfMetricsIT` (`:server:integrationTest`) |
| Webhook payload | `webhook-payload-v1.json` | `ChannelMessageTest` (T-API-008a/b, `:server:test`) |
| Log correlation and trace propagation | MDC keys, `tracestate` key `argos` | `MdcInjectionTest` (logging module and agent integration suite), `TraceStateCodecTest` (`:agent:agent-core:test`) |
| Command lines | exit codes and `--json` output | `AdminCliOpsIT` and the other `io.argos.server.cli` tests (`:server:integrationTest`), `CheckCliTest` (`:agent:agent-testing:integrationTest`), `deploy/test/verify-release.test.sh`, `deploy/compose/test/run-all.sh` |
| Database migrations | file names and checksums | `:server:checkFlywayNames` (`check`), checksum validation at every server start |

The configuration snapshots (agent and server) and the metric name snapshot together are the
"configuration key snapshot" of the test plan (T-API-009). All tests of the table except the
integration tests run in `./gradlew check`, which every pull request runs
([dev-setup.md](dev-setup.md#5-ci-checks-you-can-run-locally)).
