# proto

The `argos.v1` protocol between `argos-agent.jar` and `argos-server`: protobuf 3 messages, the gRPC
service `AgentService`, the integer protocol version and the shared protocol constants. This project
is the single source of the protocol (ADR-0010 C-104); nothing else defines or generates it.

- Gradle project: `:proto` (Java 8 bytecode, `argos.java-agent` + `argos.protobuf`)
- Java packages: `io.argos.proto.v1` (generated messages and stubs), `io.argos.proto` (hand-written
  constants and the generated `ProtocolVersion`)
- Design: [11 protocol design](../docs/11-protocol-design.md) §1-§12, [19 §2, §3.4](../docs/19-repo-build-release-design.md),
  alignment decisions in [02 §13](../docs/02-architecture.md), [ADR-0007](../docs/adr/0007-storage-poc-decisions.md),
  [ADR-0010](../docs/adr/0010-phase1-slice-pm-decisions.md)

## Files

| File | Content |
|---|---|
| `src/main/proto/argos/v1/common.proto` | `TransactionKind`, `StepType`, `FailureKind`, `DictionaryKind`, `MetricKind`, capture modes (`IpMode`, `UserIdMode`, `QueryStringMode`, `SqlParamMode`), `CommandType`, `KeyValue` (11 §4.1) |
| `src/main/proto/argos/v1/session.proto` | `AgentMessage`/`ServerMessage` of the session stream: `AgentHello`, `ServerHello`, `Limits`, `Heartbeat`/`HeartbeatAck`, dictionary exchange, `ConfigPush`/`ConfigApplyResult`, `Command`/`CommandResult`, `InstanceUpdate`, `AgentBye`, `AgentEvent`, `SessionClose`, `Throttle` (11 §4.2) |
| `src/main/proto/argos/v1/data.proto` | `BatchHeader`, `Ack`, `TransactionBatch`/`TransactionRecord`, `ProfileBatch`/`TransactionProfile`/`ProfileStep`, `MetricBatch`, `StatBatch`/`SqlStat`/`ExtStat`, `ActiveTransactionSnapshot` (11 §4.3) |
| `src/main/proto/argos/v1/service.proto` | `AgentService`: `Session` (bidirectional stream) and the five unary batch RPCs (11 §3) |
| `PROTOCOL_VERSION` | The protocol integer (DSN-19-10), generated into `io.argos.proto.ProtocolVersion.CURRENT` |
| `buf.yaml` | buf lint (STANDARD) and breaking (FILE) configuration |
| `src/main/java/io/argos/proto/` | `ProtocolLimits` (11 §4.2 `Limits` defaults, §4.4 repeat caps, §4 field caps, §2 batch target and gzip threshold, `defaults()`), `LatencyBuckets` (13 §2 bounds of `buckets`), `TransactionFlags` (C-69 bits) |
| `src/test/resources/argos-v1-fields.txt` | Committed field snapshot (contract test) |

Every `.proto` field comment states its cap (11 §4, §4.4). Generated code is a build output and is
never committed.

## Differences from 11 §4

The `.proto` files follow 11 §4 except for the decisions below. 11 §4 is corrected by the maintainer
later (ADR-0010 "결과"); until then this table and the decisions win.

| Item | 11 §4 | `.proto` | Decision |
|---|---|---|---|
| `StepType` numbers | `SQL=1 HTTP_CALL=2 METHOD=3 ASYNC=4 ERROR=5 MESSAGE_STEP=6 STACK=7 DBC=8 COMMIT=9 ROLLBACK=10` | `SQL=1 DBC=2 COMMIT=3 ROLLBACK=4 HTTP_CALL=5 METHOD=6 ASYNC=7 ERROR=8 STACK=9 MESSAGE_STEP=10 SUMMARY=11` (agent `StepType`, ClickHouse `Enum8`) | 02 §7, C-1 |
| `DictionaryKind` numbers | `... FRAME=7 DB_TARGET=8 APP_VERSION=9` | `SERVICE=1 SQL_TEXT=2 CLASS=3 METHOD_NAME=4 THREAD=5 HOST=6 FRAME=7 METRIC=8 ERROR_GROUP=9 DB_TARGET=10 APP_VERSION=11` (agent `DictKind`) | C-16 |
| `DictionaryKind.POOL = 12` | - | Added: DBC step pool names (10 §6.3, 13 §12.3 `pool`); agent `DictKind.POOL = 12` | ADR-0010 C-101 |
| `SqlStat.dbc_wait_sum_us = 9` | present | Removed; `reserved 9; reserved "dbc_wait_sum_us";` | ADR-0007 C-68 |
| `TransactionRecord.flags = 37` | - | Added `uint32`: 1 virtual thread, 2 leaked, 4 summarized, other bits reserved; `virtual_thread = 28` stays and equals bit 1 | ADR-0007 C-69 |
| `MetricSample.delta = 4` | - | Added `double`: counter = reset-corrected increase, gauge = 0 (the agent sends `kind` in `MetricSeries` and `delta`) | C-15, DSN-13-04 |
| `SqlStat.buckets = 10`, `ExtStat.buckets = 9` | - | Added `repeated uint32`, exactly 15 counts, bounds of 13 §2 (`LatencyBuckets`) | DSN-13-03, 13 §18, ADR-0010 C-101 |
| `SqlStat.sum_exec_us = 11`, `sum_fetch_us = 12` | - | Added `optional uint64` (`sum_us = sum_exec_us + sum_fetch_us` when both are set; absent = not reported, a set 0 is the value 0, C-4), sources of `sql_stat_raw.sum_exec_ms`/`sum_fetch_ms` | 13 §18, ADR-0010 C-101, 11 §12 |
| `AgentHello.plaintext = 25` | - | Added `optional bool`: the agent connects without TLS (16 §3.4). This agent always sets it (`false` over TLS); absent = an agent that does not report it | ADR-0010 C-101, 11 §12 |
| `ServerHello.user_id_salt = 18` | - | Added `bytes`: the app's 32-byte salt for `capture.user-id.mode: hashed`, over TLS only, kept in agent memory only (16 §6.3) | ADR-0010 C-101 |
| `ServerHello.default_ip_mode` | (16 §16 asks for it) | Not added | ADR-0010 C-101 |
| `TransactionProfile.kind = 6` | - | Added `TransactionKind`: the transaction's kind (= `TransactionRecord.kind`), filled by the agent (`BatchAssembler`); the source of V001 `profile_step.kind` | ADR-0010 C-138 |
| `HeartbeatAck.clock_offset_ms` sign | §4.2 says "agent - server"; the §9 formula `((t2 - t1) + (t3 - t4)) / 2` yields server - agent | Server - agent (the §9 formula; a server clock 3 s ahead gives +3000), also for `argos_agent_clock_offset_ms`; no wire change. The agent records the received value unchanged (`ClockSync`); the FakeCollector sends the §9 value | ADR-0010 C-139 |
| `ServerHello.retry_after_s` 0 | "0 = do not retry (version mismatch, revoked token, ...)" | 0 = do not retry applies to rejections other than `TOKEN_*` only; `TOKEN_*` rejections and `UNAUTHENTICATED` retry every 5 minutes (C-102) whatever `retry_after_s` says, and the server sends 300 for them; no wire change | ADR-0010 C-140 |
| `DictionaryKind.HOST` text format | `"host:port"` (outgoing call) | `scheme://host:port` (13 §3.5, V001 `ext_stat_raw.host_id`): scheme and host lower case, the port always written, an IPv6 host in brackets, no userinfo, path or query; a non-HTTP call uses its protocol as the scheme. No wire effect (the text is free-form); the producer is WO-13 | ADR-0010 C-142 |
| `ProfileStep` of type `SUMMARY` | (`SUMMARY` not in 11) | Reuses `status`, `rows`, `fetch_us`, `repeat_count` with summary meanings; the [profile_step](#profile_step-one-row-per-profilestep-of-a-transactionprofile) table below is normative; no wire change | ADR-0010 C-141 |
| `CommandType` | 5 types + `reserved 6 to 9` | Same (configuration changes, kill switch and custom retransform travel as `ConfigPush`) | ADR-0010 C-103 |
| `data.proto` imports | `common.proto` only | Also `session.proto` (`Ack.throttle` is a `Throttle`) | WO-02 D-11 |
| Files | `common`, `session`, `data` | Plus `service.proto` for `AgentService` (11 §3), so the message files stay free of gRPC | (layout only) |
| Java options | `java_package`, `java_multiple_files` | Plus `java_outer_classname` `CommonProto`, `SessionProto`, `DataProto`, `ServiceProto` (no wire effect) | (codegen only) |
| Code generation | "agent and server each generate" (javalite or full) | Generated once in `:proto`, full protobuf-java + grpc-java; no javalite switch | ADR-0005 A-14, ADR-0009 C-72, ADR-0010 C-104 |

Unchanged on purpose: `FailureKind`'s zero value is `FAILURE_NONE` (the meaningful "no failure",
agent `FailureKind.NONE = 0`), not `*_UNSPECIFIED`. It is the only such zero value: the buf
exception is a comment ignore on that value alone, and `EnumZeroValueTest` lists it as the only
exception.

## Evolution rules (11 §12)

- Field and enum numbers are permanent. After WO-11, `argos.v1` takes **additive changes only**,
  each approved by the PM (ADR-0010 C-101). An incompatible protocol is a new package (`argos.v2`).
- New fields are `optional`, or their default means "none". Only `optional` fields express a missing
  value (C-4, D-23): `cpu_time_us`, `alloc_bytes`, `InstanceUpdate.*`, `Throttle.*`, and the C-101
  additions `AgentHello.plaintext`, `SqlStat.sum_exec_us`, `SqlStat.sum_fetch_us`.
- Enums keep `*_UNSPECIFIED = 0`; new values go at the end. Receivers map unknown values to
  `*_UNSPECIFIED` and count them; unknown fields are kept and ignored.
- A new RPC is compatible; removing one needs a major version. `Limits` values are not protocol changes.
- `PROTOCOL_VERSION` changes only when the meaning of a field changes or a mandatory behaviour is
  added (11 §5); adding messages, fields or values keeps it.

Checks that enforce the rules:

| Check | What fails | Run |
|---|---|---|
| `FieldSnapshotTest` (`./gradlew :proto:test`) | Any difference between the descriptors and `src/test/resources/argos-v1-fields.txt` (numbers, types, labels, enum values, reservations, RPCs). On failure the current schema is written to `build/proto-contract/argos-v1-fields.txt`: review it and copy it over the snapshot. | every build (`check`) |
| `buf lint` | STANDARD rules, with scoped exceptions for the 11 names (`ENUM_VALUE_PREFIX`; RPC request/response naming and the shared `Ack` in `service.proto`), each with its reason in `buf.yaml`, plus one comment ignore (`ENUM_ZERO_VALUE_SUFFIX` on `FailureKind.FAILURE_NONE` only; a new enum in any file, `common.proto` included, is still checked) | `bash .github/scripts/check-proto.sh` |
| `buf breaking` (FILE rules) | Renumbered, retyped, renamed or removed fields, enum values and RPCs (a removed field fails even when reserved: generated accessors disappear) against the pull request base | `bash .github/scripts/check-proto.sh --against-git <base>` (CI `lint` job) or `--against <dir>` |
| `BootstrapConstantsContractTest` | `StepType`, `DictionaryKind`, `TransactionKind`, `FailureKind` and the C-69 flag bits differ from the agent bootstrap constants | every build |
| `EnumZeroValueTest` | An enum (top-level or nested, any of the four files) whose zero value is not `<UPPER_SNAKE(EnumName)>_UNSPECIFIED`; the only listed exception is `FailureKind.FAILURE_NONE` | every build |
| `OptionalPresenceTest` | An `optional` field of the D-23 and C-101 lists above that loses presence (absent and a set 0/`false` must stay distinct after a round trip); a plain numeric field (`sql_count`) that gains it | every build |

buf (Apache-2.0) runs as the pinned container `bufbuild/buf:1.74.0@sha256:621f1082...` (full digest in
`check-proto.sh`) with `--network none`; only the proto sources and `buf.yaml` are mounted
read-only. `BUF_BIN=<buf>` uses a local binary instead.

## Consumers (19 §2, ADR-0010 C-104)

`:proto` exports (`api`) `protobuf-java`, `grpc-protobuf` and `grpc-stub` and **no gRPC transport**:
each consumer picks its own.

| Consumer | Dependency | Notes |
|---|---|---|
| Server (WO-21) | `implementation(project(":proto"))` | Picks its own transport (grpc-netty with the server's Netty 4.2 catalog entry, or another); it must not reuse the agent's Netty 4.1 entry. Uses `ProtocolLimits.defaults()` for `ServerHello.limits` and the caps for its second check (SEC-11). The server never edits `proto/**` (needed changes are reported to the PM). |
| Agent (`agent-core`, `agent-dist`) | `agent-core`: `compileOnly(project(":proto"))` (tests: `testImplementation`); `agent-dist`: `implementation(project(":proto"))`, which ships it | Projects that put `agent-core` on their class path for other reasons (instrumentation tests, archTest) do not inherit the protocol. `agent-dist` relocates `io.argos.proto` to `io.argos.agent.shaded.io.argos.proto` like every other dependency (the agent jar contains only `io.argos.agent.*` classes, T-BLD-002; `test/agent/check-shading.sh`). Bytecode is Java 8 (major 52, C-22). Protocol enums carry the bootstrap constant numbers, so no mapping table is needed. |
| Test harness (`agent-testing`) | `project(":proto")` directly (no relocation) | FakeCollector and the 11 §16 contract vectors, reused by WO-21. |
| `test/agent/transport-poc` | none | Development-only P-3 comparison with its own snapshot of 11 §4 (PoC `java_package`); never shipped, never a reference for this project. |

Applying `argos.protobuf` to any other project fails its configuration: depend on `:proto` instead.

## Mapping to the ClickHouse V001 columns

`deploy/clickhouse/schema/V001__init.sql` (column names C-67) is fixed; the server writer (WO-21)
fills each column as below. "Session" means the values bound to the authenticated session of the
data RPC (`argos-session-id`, `ServerHello.app_id`/`instance_id`, the app's `env`, the capture
modes of `AgentHello`). Durations are stored as milliseconds rounded half up (11 §4.4).

### xlog (one row per `TransactionRecord`)

| Column | Source | Conversion |
|---|---|---|
| `app_id`, `env` | session (server-filled) | `env` denormalized from the app (DSN-02-14) |
| `instance_id` | session; `BatchHeader.instance_id` must match it | |
| `ts` | `start_ts_ms` | ms -> `DateTime64(3)`; equals the `tx_id` time prefix (C-121) |
| `tx_id`, `span_id`, `parent_span_id`, `caller_app_id`, `service_id`, `error_class_id`, `thread_name_id`, `request_id`, `user_id` | same-named field | |
| `trace_id` | `trace_id` | 16 bytes -> `FixedString(16)` |
| `kind` | `kind` | enum number = `Enum8` value |
| `app_version_id` | `app_version_id` | 0: writer rule ADR-0010 C-143 (6) (WO-21 implements) |
| `incoming_trace`, `error`, `profile_captured`, `streaming`, `timed_out` | same-named `bool` | -> `UInt8` |
| `http_method` | `http_method` | |
| `http_status` | `http_status` | `uint32` -> `UInt16`; BATCH (the agent sends 0): writer rule ADR-0010 C-143 (3) (WO-21 implements) |
| `elapsed_ms` | `elapsed_us` | us -> ms |
| `error_group_id` | server-computed fingerprint of `error_class_id`, `error_message` (masked again), `error_top_frame_id` (F-S19) | |
| `apdex_t_ms` | server-computed (service Apdex T at insert, D-17) | |
| `remote_ip` | `remote_ip` | 4 bytes -> `::ffff:a.b.c.d`, 16 bytes as is, empty -> `::` |
| `ip_mode`, `user_id_mode` | session (`AgentHello.ip_mode`, `user_id_mode`) | map **by name** (`IP_FULL`->`full`, `IP_MASKED`->`masked`, `IP_OFF`->`off`; `USER_ID_*` likewise): the proto and `Enum8` numbers differ |
| `sql_count`, `ext_count`, `dispatch_count` | same-named `uint32` | -> `UInt16` (saturate at 65,535) |
| `sql_time_ms`, `ext_time_ms`, `dbc_wait_ms` | same-named field | |
| `cpu_time_ms` | `optional cpu_time_us` | us -> ms; absent -> `-1` (D-23) |
| `alloc_bytes` | `optional alloc_bytes` | absent -> `-1` (D-23) |
| `late` | server-computed (receive time − `ts` > 60 s, D-15) | |
| `flags` | `flags` | `uint32` -> `UInt16`, C-69 bits (`TransactionFlags`) |
| `sql_ids` | `sql_ids` | |

Not stored in xlog (no V001 column): `url_path`, `headers` (WO-21 adds the columns by an additive
migration, ADR-0010 C-144), `error_message` and `error_top_frame_id` (fingerprint inputs only),
`virtual_thread` (= `flags` bit 1).

### profile_step (one row per `ProfileStep` of a `TransactionProfile`)

| Column | Source | Conversion |
|---|---|---|
| `app_id`, `env`, `instance_id` | session (server-filled) | |
| `tx_id`, `ts` | `TransactionProfile.tx_id`, `start_ts_ms` | ms -> `DateTime64(3)` |
| `kind` | `TransactionProfile.kind` (ADR-0010 C-138) | enum number = `Enum8` value |
| `seq`, `parent_seq` | same-named field | `uint32` -> `UInt16` |
| `type` | `type` | C-1 number = `Enum8` value |
| `start_offset_ms`, `elapsed_ms`, `fetch_ms` | `start_offset_us`, `elapsed_us`, `fetch_us` | us -> ms |
| `exec_ms` | `elapsed_us − fetch_us` (SQL) | us -> ms |
| `name_id`, `target_id`, `rows`, `error`, `batch_size` | same-named field | `name_id` of a DBC step is a `POOL` key (10 §6.3); DBC `target_id`: ADR-0010 C-143 (4) (WO-21 implements) |
| `status` | `status` (HTTP status) or, when 0, `failure` (all step types except `SUMMARY`, see the next row) | `FailureKind` number: writer rule ADR-0010 C-143 (5) (WO-21 implements) |
| all columns of a `SUMMARY` step (`type = SUMMARY` (11), summary mode 10 §9.1, `TransactionRecord.flags` bit 4) | One step per summarized (step type, name) after the recorded steps: `parent_seq` 0, `name_id` = the summarized name, `status` = the summarized step type (C-1 number, not an HTTP status or `FailureKind`), `repeat_count` = the step count, `elapsed_us` = their total time, `fetch_us` = the longest one, `rows` = the number of failed ones, `error` = any failed; `start_offset_us`, `target_id`, `failure` stay 0 | Same-named columns as above (`fetch_ms` then holds the maximum, `rows` the failure count, `status` the step type); `exec_ms` has no source (the `elapsed_us − fetch_us` rule is SQL only). Encoding of the agent (`BatchAssembler`); this row is normative (ADR-0010 C-141): the API and UI read `type = SUMMARY` rows with these meanings |
| `repeat_count` | `repeat_count` | 0 -> 1 (single step) |
| `detail` | `detail` | mode applied by the agent (`masked`/`partial`) |
| `detail_enc` | server-computed: in `full` mode the server envelope-encrypts `detail` into this column (16 §6.7, DSN-16-09); what `detail` keeps then is the writer's rule (WO-21) | |
| `stack` | `frame_ids` | |

Not stored in V001: `TransactionProfile.truncated`, `total_steps` (WO-21 adds the columns by an
additive migration, ADR-0010 C-144).

### metric (one row per `MetricSample`)

| Column | Source | Conversion |
|---|---|---|
| `app_id`, `env`, `instance_id` | session (server-filled; `app_id = 0` for the server itself) | |
| `name`, `kind` | `MetricSeries.name`, `kind` of `series_index` | `GAUGE=1`/`COUNTER=2` = `Enum8` values |
| `ts` | `ts_ms` | ms -> `DateTime` seconds (5 s grid) |
| `value`, `delta` | `value`, `delta` (C-15) | |

### sql_stat_raw (one row per `SqlStat`) and ext_stat_raw (one row per `ExtStat`)

| Column | Source | Conversion |
|---|---|---|
| `app_id`, `env`, `instance_id` | session (server-filled) | |
| `ts` | `StatBatch.window_start_ms` | ms -> `DateTime` seconds |
| `kind`, `sql_id`, `db_id`, `host_id`, `count`, `error_count`, `fetch_rows`, `timeout_count`, `connect_fail_count` | same-named field | |
| `sum_ms`, `max_ms` | `sum_us`, `max_us` | us -> ms |
| `sum_exec_ms`, `sum_fetch_ms` (SQL) | `optional sum_exec_us`, `optional sum_fetch_us` | us -> ms. Unset values (the V001 columns are non-nullable `UInt64`): writer rule ADR-0010 C-143 (2) (WO-21 implements). No agent code builds `SqlStat` yet (statistics batches are WO-16) |
| `buckets` | `buckets` | exactly 15 counts (`LatencyBuckets`) |

### Enum zero values and unknown enum values (all tables above)

Writer rule: ADR-0010 C-143 (1) (WO-21 implements). The facts it settles:

- The V001 `Enum8` columns `xlog.kind`, `profile_step.kind`, `profile_step.type`, `metric.kind`,
  `sql_stat_raw.kind` and `ext_stat_raw.kind` have no value 0, while every protocol enum has
  `*_UNSPECIFIED = 0` (proto3 default, also what a receiver maps unknown values to).
- `xlog.ip_mode` and `xlog.user_id_mode` have `'off' = 0` and are mapped **by name**; `IP_MODE_UNSPECIFIED`
  and `USER_ID_MODE_UNSPECIFIED` have no `Enum8` name (mapping them by number would store `off`).
- Two 11 clauses could both apply to such a value: 11 §4.4 "unknown enum: the receiver treats it as
  `*_UNSPECIFIED`, counts it in `argos_collector_unknown_enum_total{field}` and does not reject it",
  and 11 §10 "`INVALID_ARGUMENT`: required value outside the enum range"; C-143 (1) keeps 11 §4.4.

Not stored in V001: `StatBatch.window_len_s` (always 10), `StatBatch.counters` (window counters,
D-19/D-20, server self-metrics), `BatchHeader` (deduplication only), `ActiveTransactionSnapshot`
(server memory, 14), session messages (PostgreSQL: instances, dictionary, configuration changes,
command results).
