# Operations runbook

This page tells the operator of an Argos installation what a problem looks like, how to confirm it and what to do. Section 4 has one entry per symptom; section 5 has one entry per failure that the chaos tests inject (`chaosTest`, T-CH-001 to T-CH-009), with what the application owner notices, what Argos shows and what to do.

The commands are those of the Docker Compose installation ([compose.md](../install/compose.md)). Run them in `deploy/compose`, as the user that runs `./argos-compose.sh` (with `sudo` on a Linux Docker Engine). `argos-admin <command>` stands for the server's operator command line: in Compose, run it with the `compose exec` line at the top of [admin-cli.md](admin-cli.md); outside Compose, `java -jar argos-server-<version>.jar admin <command>`.

Related pages:

- [admin-cli.md](admin-cli.md): every `argos-admin` command, its exit codes and audit events.
- [backup-restore.md](backup-restore.md) and [recovery-rehearsal.md](recovery-rehearsal.md): backup, restore, restore on a new host.
- [ops-checklist.md](../dogfooding/ops-checklist.md): the daily and weekly checks, and `ops-report.sh`, which collects their values without changing anything.
- [agent-config.md](../agent-config.md): agent keys, the pre-flight check `--check`, known limitations.
- [compose.md section 11](../install/compose.md#11-troubleshooting): installation and start-up problems.

## 1. Where to look

| Source | What it shows | How to open it |
|---|---|---|
| **Overview**, panel **Collection server** | Ingest lag (p99 of the recent window), Disk usage (largest used ratio of the WAL file system and the ClickHouse disks), Channel failures (24 h), Agent sessions, Certificate expiry | UI, first screen |
| Application **Dashboard**, section **Collection quality** | Agent drops, Server drops (with the part dropped by quota), Ingest lag, Clock skew warnings, Excluded requests, Late arrivals; charts shade collection gaps, the XLog hatches collection outages | UI, the application's dashboard |
| **Administration > Instances** | the state of each instance (UP, STALE, DOWN, WARN with its reasons), agent version, last heartbeat, Drops (24 h), clock offset; tab **Collection health** ranks the instances by agent drops, ingest lag, clock offset and excluded requests | UI, operator or administrator |
| Instance detail | header state, clock offset, agent drops over the range; **Commands > Show latest self-diagnosis** (queues, drops, disabled modules, effective configuration) | UI, from an instance tile or list |
| **Administration > Pending registrations**, **Agent tokens**, **Data retention**, **Audit log** | approval queue, token state and expiry, retention, audit trail | UI, administrator |
| Storage health | ClickHouse disks, the partitions with the most active parts, merges, insert counters, mutations, the WAL, unresolved dictionary ids, the last TTL run, whether the emergency retention is on, the dead-letter area | `GET /api/v1/admin/storage/health` (operator role): open `<public URL>/api/v1/admin/storage/health` in the browser in which you are signed in. There is no screen for it in this version. |
| Self metrics | the `argos_*` meters of the server on `/actuator/prometheus` (names below) | management port `8081`, bearer token and address rules in [compose.md section 7.2](../install/compose.md#72-management-port); `ops-report.sh` reads the main ones for you |
| Server log | start sequence, WAL, ClickHouse writer, emergency retention, alert delivery | `./argos-compose.sh logs server` |
| Agent log | session, refusals, drops, TLS and configuration warnings | `<log.dir>/argos-agent.log` on the application host (default `../logs` next to the agent jar, [agent-config.md](../agent-config.md#known-limitations)) |
| Diagnostic bundle | configuration, logs, ClickHouse and PostgreSQL state, inventory, all secrets masked | `./argos-compose.sh diag --since 24h` ([compose.md section 15.3](../install/compose.md#153-diagnostic-bundle)) |

Server self metrics used on this page (all on `/actuator/prometheus`):

| Metric | Meaning |
|---|---|
| `argos_ingest_lag_seconds` | time from the server's receipt of a record to the ClickHouse insert acknowledgement. A histogram: compute quantiles from its buckets, for example `histogram_quantile(0.99, sum by (le) (rate(argos_ingest_lag_seconds_bucket[5m])))`. It does not include the network between agent and server. |
| `argos_collector_received_total{type}` | items received, before any drop |
| `argos_collector_dropped_total{reason}` | items the server accepted and dropped: `quota`, `wal_full`, `dead_letter` |
| `argos_collector_rejected_total{reason}` | calls and hellos the server refused, by reason (for example `wal_slow` when the WAL fsync is slow, `token_*` for token verdicts) |
| `argos_collector_auth_blocked_total` | calls refused because their source address is blocked after authentication failures |
| `argos_collector_sessions_closed_total{reason}` | sessions the server closed itself: `idle_timeout` (no agent message for 3 heartbeat intervals), `instance_merged` |
| `argos_sessions_active`, `argos_instances{state}` | live agent sessions, instances per state |
| `argos_wal_bytes`, `argos_wal_segments`, `argos_wal_usage_ratio`, `argos_wal_warnings_total`, `argos_wal_fsync_seconds` | write-ahead log size, segments, share of `argos.wal.max-bytes`, warnings at `argos.wal.warn-ratio`, fsync time |
| `argos_writer_queue_rows{table}`, `argos_writer_inserts_total{table,result}`, `argos_writer_dead_letter_blocks`, `argos_writer_dead_letter_bytes` | ClickHouse writer queue, inserts, blocks ClickHouse refused for good |
| `argos_server_disk_usage_ratio{mount}`, `argos_ch_disk_free_bytes` | used ratio of the server's disks, free ClickHouse disk |
| `argos_ch_parts_active{table}`, `argos_ch_delayed_inserts_total`, `argos_ch_query_p99_seconds`, `argos_ch_memory_tracking_bytes` | ClickHouse parts, delayed inserts, query p99, memory |
| `argos_retention_emergency_drops_total{table}` | partitions dropped by the emergency retention |
| `argos_late_arrivals_total`, `argos_ts_clamped_total`, `argos_ts_rejected_total`, `argos_clock_offset_ms{instance}` | late rows, corrected and refused timestamps, clock offset of live instances |
| `argos_reconcile_mismatch_minutes{app}` | mismatched minutes of the last nightly consistency check |
| `argos_notifications_total`, `argos_notification_failures_total{channel}` | alert deliveries and failed deliveries |
| `argos_server_tls_cert_expiry_seconds`, `argos_server_tls_cert_expiry_days`, `argos_agent_token_expiry_seconds` | time left on the gRPC certificate and on agent tokens |
| `argos_ratelimit_rejections_total` | requests refused by a rate limit |

The agent reports its own counters with its metrics (instance detail, and the agent drops of the Collection quality section): `argos_agent_dropped_total{queue}` (items dropped by the agent, including batches it gave up on) and `argos_agent_batches_dropped_total{kind}`.

The built-in alert templates that watch Argos itself: **Instance down**, **Collection** (rules Agent drops and Server drops), **Server disk** (warning at 0.80, critical at 0.90, for 10 minutes), **Ingest lag** (30 s for 5 minutes, recovers below 5 s), **Channel failure**, **Certificate and token expiry** (warning 30 days, critical 7 days). A channel failure alert goes to the other channels.

## 2. Instance states

The server evaluates every instance every 10 seconds (`argos.collector.stale-after`, `down-after`, `bye-grace`):

| State | When | Notes |
|---|---|---|
| UP | a live session with a heartbeat younger than 60 s | the agent sends a heartbeat every 30 s |
| WARN | UP, with at least one warning: clock offset of 1 s or more, agent or server drops in the last 5 minutes, the lowest accepted agent version, a disabled module, kill switch on, configuration drift, context leak | the tooltip lists the reasons |
| STALE | 60 s or more without a heartbeat, and no `AgentBye` | starts a collection outage (`session_lost`) |
| DOWN | 180 s or more without a heartbeat, or 5 minutes after an `AgentBye` without a reconnect | a DOWN after a planned stop (Bye) starts no outage and no shading; a reconnect inside the 5 minutes never makes the instance DOWN |

Collection outages are shaded on the charts and hatched on the XLog, so that missing data is not read as zero:

- `session_lost`: an instance went STALE or DOWN without a Bye, until it is UP or WARN again;
- `server_down`: from a graceful server stop to the next server start, for every application. A server that crashed writes no stop event, so its downtime shows only as `session_lost` of the instances that did not come back within 60 s.

At its start the server takes the instances it had left up as last seen at that moment. An agent that reconnects within 60 s keeps its instance UP.

## 3. How the agent behaves when it cannot deliver

The agent never blocks an application thread and never changes what the application does: when data cannot be delivered it is dropped and counted (N-05). The details are in [agent-config.md](../agent-config.md#known-limitations) (Reconnects and connections, A refused session) and in the agent's `Sender`:

| Situation | Agent behaviour |
|---|---|
| collector unreachable (connection refused, timeout, name not resolved, TLS failure) | tries again after 1, 2, 4, 8, 16 and then every 30 s (±20 % jitter); no connection held in between; one WARN per minute: `collector connection failed (<status>, <exception class>); the agent keeps retrying with back-off` |
| session ended after it was established | INFO `collector session ended (<status>); reconnecting in <n> ms`, same back-off |
| queues while nothing can be sent | at most `buffer.transaction.capacity` (20,000) transactions, `buffer.max-bytes` (32 MB) for transactions and profiles, items older than `buffer.max-age-seconds` (120 s) dropped; the oldest go first; all drops counted |
| batches in flight | at most `sender.max-in-flight` (4) over all kinds; nothing more is drained while they wait |
| `UNAVAILABLE`, `DEADLINE_EXCEEDED`, other transient codes | the same batch with the same sequence number again after 200 ms, 1 s, 3 s, then dropped and counted. The server recognises a resent batch and stores it once (D-13). |
| `RESOURCE_EXHAUSTED` | with `argos-retry-after-ms`: retried after that time; without it (batch too large): split once into two halves, then dropped |
| `ACK_PARTIAL_DROP` (server quota) or a full server WAL | the dropped items are server drops; they are never resent |
| `INVALID_ARGUMENT` | dropped and counted, at most one WARN per minute |
| `PERMISSION_DENIED` (token of another application) | dropped, one ERROR, new session |
| `UNAUTHENTICATED` (token invalid, expired or revoked) | data dropped; a new attempt every 5 minutes; one WARN per hour: `collector rejected the agent token (UNAUTHENTICATED); ...`. The token file is read once at start. |
| refusal without a retry time (plaintext not allowed, unsupported protocol or agent version) | one ERROR; the transport stops until the application restarts; everything collected is dropped and counted |
| session replaced by another JVM with the same `app.name` and instance name | one WARN; this agent stops sending until it restarts |
| `server.tls: false` or `server.tls.insecure-skip-verify: true` | one WARN on every connection, reconnects included |

The heartbeat carries the agent's drop count, so the server shows the drops once the session is back: Agent drops in Collection quality, Drops (24 h) on the instance, WARN with reason drops for 5 minutes, and the Agent drops rule of the Collection alert template.

## 4. Symptoms

### 4.1 Ingest lag rises (`argos_ingest_lag_seconds` p99 above 5 s)

- **Likely causes:** ClickHouse merges fall behind (many parts, delayed inserts), a slow disk, heavy queries, ClickHouse down or paused (section 5.9), inserts paused at 95 % disk (section 4.3).
- **How to check:** Overview > Collection server > Ingest lag; the Ingest lag alert (fires at 30 s). Storage health: active parts per partition, merges, insert counters; `argos_ch_delayed_inserts_total`, `argos_ch_query_p99_seconds`, `argos_writer_queue_rows`, `argos_wal_bytes`. Disk I/O of the ClickHouse host. Server log lines `Insert into <table> failed (attempt <n>, ...)`.
- **What to do:** stop heavy ad-hoc queries; check `max_concurrent_queries` and `max_concurrent_queries_for_user` in `deploy/clickhouse/config.d/argos.xml` and `users.d/argos.xml`. If parts pile up, see 4.2. If the disk is the limit, add disk or shorten the retention (4.3). If a few instances send far more than the rest, lower their load or the per-instance quotas `argos.collector.quota.*` (excess items are then dropped and shown as server drops by quota).

### 4.2 Too many parts (more than 300 active parts in a partition)

- **Likely causes:** late data creating parts in many old partitions, inserts too small or too frequent, too few merge threads.
- **How to check:** Storage health (partitions with the most active parts), `argos_ch_parts_active{table}`; Collection quality > Late arrivals and `argos_late_arrivals_total`; clock offsets of the instances (Administration > Instances).
- **What to do:** fix the clock of instances with a large offset (4.5): the server corrects or refuses timestamps far from its own clock (`argos_ts_clamped_total`, `argos_ts_rejected_total`), but a wrong clock still spreads rows over partitions. Review `background_pool_size` in `deploy/clickhouse/config.d/argos.xml` (docs/13). A ClickHouse `OPTIMIZE TABLE ... PARTITION ...` of the worst partition outside peak hours is possible, with an account allowed to run it; it is ClickHouse's own statement, not an Argos command.

### 4.3 Disk full

- **Likely causes:** retention too long for the volume, WAL growing while ClickHouse cannot take inserts, emergency retention with nothing left to drop.
- **How to check:** Overview > Collection server > Disk usage; `argos_server_disk_usage_ratio{mount}`, `argos_ch_disk_free_bytes`; the Server disk alert; `argos-admin retention show` (retention, size and oldest partition of each table). Storage health shows whether the emergency retention is on.
- **What Argos does by itself** (fullest ClickHouse disk, `argos.ops.disk.*`):

  | Used | Behaviour | Server log |
  |---|---|---|
  | 0.80 | warning (Server disk alert, warning) | `ClickHouse disk at <n>% (warning ratio ...): free disk space or shorten the retention` |
  | 0.90 | emergency retention: the oldest day partition of `profile_step`, then `metric`, `sql_stat_raw`, `ext_stat_raw`, then `xlog` is dropped, one per step, until usage is below 0.85; never today's partition and never a rollup. Each drop writes an event `retention_emergency`, an audit row `RETENTION_EMERGENCY_DROP` and counts `argos_retention_emergency_drops_total{table}` | `ClickHouse disk at <n>% (emergency ratio ...): emergency retention started, ...` and one line per dropped partition |
  | 0.95 | ClickHouse inserts paused, data kept in the WAL only (up to `argos.wal.max-bytes`, 10 GB); resumed below 0.93 | `ClickHouse disk at <n>%: inserts paused at ...`, then `ClickHouse inserts resumed: the WAL is loaded again` |

- **What to do:** add disk, or shorten the retention (Administration > Data retention, or `argos.retention.*` and `argos-admin retention apply`, [admin-cli.md](admin-cli.md#retention)). The WAL is loaded into ClickHouse by itself when inserts resume. If the log says `Emergency retention has nothing left to drop ... add disk space`, only more disk helps. Data dropped by the emergency retention is gone: record it.

### 4.4 Agents are refused

- **Likely causes:** token revoked or expired, application waiting for approval, agent version outside the accepted range, TLS host name mismatch or untrusted CA, plaintext not allowed, too many sessions or instances.
- **How to check:** the agent log (section 3 lists the lines); `argos_collector_rejected_total{reason}` and `argos_collector_auth_blocked_total`; Administration > Pending registrations, Agent tokens, Instances (agent versions); the audit log (`AGENT_AUTH_FAILED`). Reproduce on the application host with the pre-flight check, which names the reason and what to do:

  ```
  java -jar argos-agent.jar --check --config argos-agent.yml
  ```

- **What to do:** issue a new token (Administration > Applications or Agent tokens, or `argos-admin token create --app <name> --env <env>`) and restart the application, because the agent reads the token file once at start; approve the application; replace the agent with a supported version; fix `server.tls.ca-file` or the certificate's names (5.7). An address blocked after repeated authentication failures is released after `argos.collector.auth-fail.block-minutes` (10).

### 4.5 Clock skew warning (WARN)

- **Likely causes:** NTP not running on the application host; if every instance is WARN at once, the server's own clock.
- **How to check:** instance detail > Clock offset (WARN from 1 s); Collection quality > Clock skew warnings; `argos_clock_offset_ms{instance}`; `--check` row `clock`.
- **What to do:** fix time synchronisation (chrony or the host's NTP service) on the application host. When all instances warn, fix the server host first.

### 4.6 WAL recovery after a crash or a long ClickHouse outage

- **What Argos does:** at start, before the agent port opens, the server scans the WAL, cuts torn tails, restores the duplicate window and loads every record that ClickHouse has not acknowledged, with the same insert tokens (no duplicates, D-13). The start waits until the replay is done; it needs ClickHouse.
- **How to check:** server log `WAL recovery scan finished in <n> ms: <n> segments, <n> bytes, <n> data records, ...`; a cut tail logs `WAL segment <n> cut at offset ... the tail was not durable`, and `WAL segment <n> is not the last one: records after offset <n> are lost` names lost records. `argos_wal_bytes` falls back to its usual size once the records are loaded and the segments reclaimed (`WAL reclamation deleted <n> segments`).
- **What to do:** nothing, as a rule. After the replay, check the consistency of the affected days: the nightly check does it, or `argos-admin consistency check --date <yyyy-MM-dd> --rebuild`. A WAL write or fsync error stops the WAL until a restart (`WAL write or fsync failed in ...`): fix the disk, then restart the server.

### 4.7 Alert channel failures

- **Likely causes:** SMTP server down, webhook URL removed, destination refused by the destination check, TLS problem.
- **How to check:** Overview > Collection server > Channel failures (24 h); `argos_notification_failures_total{channel}`; the Channel failure alert (sent through the other channels); the alert history of the channel.
- **What to do:** send a test message from the channel (it runs the same checks), replace the URL or certificate, or allow the destination (`argos.alert.webhook.allow-cidrs`) when it is an internal address on purpose.

### 4.8 Session storms and refusals

- **Likely causes:** an auto-scaler restarting instances in a loop, one token shared by many JVMs, an attack.
- **How to check:** `argos_sessions_active`, `argos_collector_rejected_total{reason}`, `argos_ratelimit_rejections_total`, `argos_collector_auth_blocked_total`; Administration > Instances (many new instances); the agent log line about a replaced session (two JVMs with the same instance name).
- **What to do:** check the session and instance limits (`argos.collector.session.*`: sessions per token, new sessions per minute, instances per application, new instances per hour) and the authentication failure block. Revoke a token that leaked (`argos-admin token revoke --prefix <argos_xxxxxxxx> --reason <text>`); its sessions end within 5 seconds. Give each JVM its own `app.instance`.

### 4.9 Certificate about to expire or expired

- **How to check:** Overview > Collection server > Certificate expiry; `argos_server_tls_cert_expiry_seconds`; the Certificate and token expiry alert (warning 30 days, critical 7 days); agent logs with TLS handshake failures once it has expired.
- **What to do:** replace `tls/server.crt` and `tls/server.key` as in [compose.md, Certificates in production](../install/compose.md#certificates-in-production). The server reloads by itself when the files change; `argos-admin tls reload` asks it at once and reports the result ([admin-cli.md](admin-cli.md#tls-reload)). An expired certificate stops collection from every agent.

### 4.10 Master key lost

- **Symptom:** the server refuses to start (`argos.master-key-file` missing, malformed or readable by others), or channel tests and command results answer `secret_unavailable`.
- **How to check:** `argos-admin secrets verify` reports per column how many stored secrets decrypt with the configured key ([admin-cli.md](admin-cli.md#secrets-verify)).
- **What to do:** restore the key from its separate backup copy (the key id is in the backup manifest). There is no command that recovers the stored secrets without the key, and none that resets it. Without a copy, a new key can only be used with the loss of every stored secret: alert channel settings and signing keys must be entered again, earlier command results cannot be read, and application user id salts are lost. Never generate a new key for an installation whose key you still have ([compose.md section 7.4](../install/compose.md#74-retention-audit-log-and-master-key)).

### 4.11 Setup token lost or expired

- **Symptom:** the setup wizard answers `403`: the token is valid for 30 minutes after the start and for one use.
- **What to do:** while no administrator exists, `argos-admin setup-token regenerate` writes a new token to the token file; read it with `./argos-compose.sh compose exec server cat /data/setup-token`. Restarting the server also issues a new one ([admin-cli.md](admin-cli.md#setup-token-regenerate)).

### 4.12 Server out of memory or restarting

- **Likely causes:** heap too small for the load, ClickHouse above its memory limit.
- **How to check:** `./argos-compose.sh status` (restart count); the server log (`java.lang.OutOfMemoryError`); the host's kernel log (`dmesg`) for a container killed by the kernel; `argos_ch_memory_tracking_bytes`.
- **What to do:** recompute the memory with [compose.md section 7.1](../install/compose.md#71-memory) and raise `ARGOS_SERVER_MEM` with `ARGOS_SERVER_JAVA_OPTS`, or `ARGOS_CH_MEM` with `ARGOS_CH_MAX_SERVER_MEMORY_USAGE`, together. Check the query limits. After a crash the WAL replay (4.6) restores what the server had acknowledged. Keep a diagnostic bundle for a bug report.

### 4.13 Nightly consistency mismatch

- **Likely causes:** duplicate or missing rows, a failed materialized view.
- **How to check:** `argos_reconcile_mismatch_minutes{app}`; the `ops-report.sh` section "Nightly consistency check" ([ops-checklist.md](../dogfooding/ops-checklist.md#run-the-consistency-check-by-hand)).
- **What to do:** the nightly job rebuilds mismatched days by itself; check its result. Otherwise `argos-admin consistency check --date <yyyy-MM-dd> --rebuild`. A mismatch that comes back is a bug: report it with the WAL and writer lines of the server log and a diagnostic bundle.

### 4.14 Classes stay uninstrumented after a failed retransformation

- **Symptom:** some libraries record nothing (no SQL or HTTP steps) on one application, typically next to another Java agent.
- **How to check:** the agent log: `retransformation of <n> already loaded class(es) failed and they stay uninstrumented (this batch: <n>, e.g. <class>): <JVM reason>; at most one such line per minute`. The JVM refused to change classes that were already loaded; the agent does not retry.
- **What to do:** put the Argos `-javaagent` option before other agents ([agent-config.md](../agent-config.md#known-limitations)), restart, and if the line stays, report it with `debug.transform-log` ([agent-config.md, Diagnostic keys](../agent-config.md#diagnostic-keys)).

## 5. Failure scenarios

Each scenario is exercised by the chaos test of the same id (`./gradlew chaosTest`, [test/chaos/README.md](../../test/chaos/README.md); published runs in `docs/bench/chaos-*.md`). The common expectation of every scenario: the application's latency and errors do not change beyond the noise of a run without the fault, the agent logs no ERROR, and within 60 s of the end of the fault the instances are UP and data arrives again.

### 5.1 Argos server down (T-CH-001)

- **Symptom:** the UI is unreachable. The applications keep running normally; their agents log `collector connection failed (UNAVAILABLE, ...)` once per minute.
- **What Argos shows after the server is back:** a `server_down` gap on every chart for a graceful stop (a crash shows `session_lost` gaps of the instances that took longer than 60 s to return). Agents reconnect at their next try, at most about 30 s apart; the instances are UP again within a minute. Each agent kept only the last 120 s of data (`buffer.max-age-seconds`) and at most its queue bounds; everything older is counted: Agent drops in Collection quality, WARN with reason drops for 5 minutes, the Agent drops alert rule. Right after reconnecting, each agent sends its queued backlog at once; the part above the instance's quota is acknowledged as dropped, so Collection quality can also show server drops by quota for the same outage (5.4).
- **What to do:** `./argos-compose.sh status` and `./argos-compose.sh logs server` to find the cause, then `./argos-compose.sh up`. Nothing is needed on the application hosts. If outages are expected to last longer than two minutes, the agent's `buffer.*` keys can keep more (they take effect at the next application start and cost agent heap up to `buffer.max-bytes`); data of a longer outage is lost either way. For planned work, follow [compose.md section 15.2](../install/compose.md#152-upgrade-and-rollback).

### 5.2 Network latency between agents and server (T-CH-002)

- **Symptom:** data shows up in the UI later than usual. The application is not slowed down: sending happens on the agent's own thread.
- **What Argos shows:** `argos_ingest_lag_seconds` does not change, because it starts at the server's receipt. With a high round trip the agent keeps at most 4 batches in flight; if it cannot keep up with the application, its queues reach their bounds and the oldest items are dropped and counted (Agent drops, WARN). Agent memory stays within `buffer.max-bytes`. Instances stay UP as long as a heartbeat arrives within 60 s.
- **What to do:** measure the round trip: the `--check` row `clock` prints it. Fix the network path, or move the collector closer to the applications. Calls that take longer than `server.deadline-ms` (5 s) are retried and then dropped.

### 5.3 Packet loss (T-CH-003)

- **Symptom:** gaps or a lower transaction count for some instances; the application itself is unaffected.
- **What Argos shows:** calls that time out are sent again with the same sequence number, and the server stores each batch once, so totals never double (D-13). A batch that fails 3 retries is dropped and counted (Agent drops). Sessions that break log `collector session ended (...); reconnecting in <n> ms`; when one stays down for 60 s the instance goes STALE and a `session_lost` gap appears. The server counts the sessions it closed for silence in `argos_collector_sessions_closed_total{reason="idle_timeout"}`.
- **What to do:** fix the network. Check the agent drops per instance under Administration > Instances > Collection health to see which hosts are affected.

### 5.4 Server errors, `RESOURCE_EXHAUSTED` and quota (T-CH-004)

- **Symptom:** some data is missing while everything looks connected.
- **What Argos shows:** two kinds of drops, shown apart:
  - **Server drops** (Collection quality, "(n by quota)"; `argos_collector_dropped_total{reason="quota"}`): the instance sent more than `argos.collector.quota.*` allows (by default 2,000 transactions and 20,000 profile steps per second, 1,000 metric points per 5 s, 6,000 statistics rows per 10 s). The server keeps the batch up to the quota and acknowledges the rest as dropped (`ACK_PARTIAL_DROP`); the agent does not resend it. `reason="wal_full"` means the WAL was at its limit (5.9).
  - **Agent drops**: batches the server refused with a transient error (`UNAVAILABLE`, an internal error) three times in a row, or batches above the limits that the server refused with `RESOURCE_EXHAUSTED`. A slow WAL fsync answers `UNAVAILABLE` with a retry time (`argos_collector_rejected_total{reason="wal_slow"}`), and the agent waits that long.
  - The instance is WARN with reason drops for 5 minutes; the Collection alert's Server drops or Agent drops rule fires.
- **What to do:** for quota drops, find the instance (Collection health ranking) and decide whether its volume is expected: lower its sampling (remote configuration, `sampling.*`) or raise the quota. For server errors, read the server log at that time and `argos_ingest_errors_total`; keep a diagnostic bundle and report a bug.

### 5.5 Agent log disk full (T-CH-005)

- **Symptom:** Argos lines appear on the application's standard error (its console or container log). The application keeps running.
- **What Argos shows:** nothing changes in the UI: collection goes on. When the agent cannot write its log file, it switches to standard error for good, with at most 10 lines per minute, WARN and ERROR only, the same message at most once a minute, one line per event. The agent's own files are bounded (`log.max-file-mb` × `log.max-files`, 50 MB by default), so something else filled the disk.
- **What to do:** free the disk or give the agent its own `log.dir`. The agent returns to its log file only after the application restarts. `--check` row `log-dir` tells whether the directory can be written.

### 5.6 DNS failure (T-CH-006)

- **Symptom:** an application that starts while the collector's name cannot be resolved starts normally (the agent looks the name up only when its network thread connects, not during the application's start-up), but its instance does not appear in Argos, or stays DOWN.
- **What Argos shows:** nothing for that instance until it connects. The agent log has `collector connection failed (UNAVAILABLE, ...)` once per minute, naming the exception class of the lookup failure; each try performs one name lookup.
- **What to do:** fix the DNS record or `server.address`; `--check` row `dns` confirms. The agent connects at its next try (at most about 30 s later, plus the JVM's cache of failed lookups, 10 s by default) without an application restart. Data older than 120 s at that moment is dropped and counted.

### 5.7 TLS host name mismatch and skipped verification (T-CH-007)

- **Symptom:** after a certificate change or an address change, instances do not connect.
- **What Argos shows:** the instances are missing or DOWN. The agent log has `collector connection failed (UNAVAILABLE, ...)` once per minute, naming a TLS handshake or certificate exception; the agent keeps trying with back-off. `--check` row `tls` fails with "does not match the host name" or "not trusted".
- **What to do:** use a certificate whose subject alternative names contain the address of `server.address` exactly, or change `server.address` to one of its names, and set `server.tls.ca-file` to the CA that signed it ([compose.md section 4](../install/compose.md#4-prepare-the-grpc-certificate)). `server.tls.insecure-skip-verify: true` connects anyway, but accepts any certificate and logs `TLS verification disabled (insecure-skip-verify=true): ...` on every connection: use it only to confirm the diagnosis, never in production.

### 5.8 PostgreSQL reset and instance ids reassigned (T-CH-008)

- **Symptom:** after PostgreSQL was recreated, restored from an older backup or pointed at another database, agents are refused, or applications and instances appear under new ids and older data seems to be missing.
- **What Argos shows:** PostgreSQL holds the applications, instances, tokens, users, rules and channels; ClickHouse holds the collected data under application and instance ids. Tokens issued after the backup (all tokens, for an empty database) no longer exist: agents log `collector rejected the agent token (UNAUTHENTICATED)` and try again every 5 minutes. Agents that connect again register under new ids. Data collected before keeps the old ids: where PostgreSQL no longer knows the instance, the transaction detail shows the instance name as a dash and the instance is not in the lists. A database recreated empty would number applications and instances from 1 again; the server prevents that at every start: before it accepts agents it reads the largest application and instance ids of ClickHouse and moves the id sequences past them, with one INFO line per moved sequence (`The instance id sequence now continues after <id>: ...`), so a new application or instance never takes the id of an old one. If ClickHouse cannot be read at that moment, the server logs `Could not read the largest app and instance ids of ClickHouse, the id sequences were not checked: ...` (WARN), starts anyway and checks again at its next start. Dictionary texts (SQL, URLs) come back as the agents reconnect.
- **What to do:** never recreate the PostgreSQL database while keeping ClickHouse. Restore with `restore.sh` instead: it moves the application and instance id sequences past the ids that ClickHouse still holds before the server starts (the server checks the same at its start), so a new instance never takes the id of an old one and never shows its data, and it checks consistency afterwards ([backup-restore.md, Point-in-time mismatch](backup-restore.md#point-in-time-mismatch)). Issue new tokens for agents whose tokens are gone and restart those applications. The old data expires with the retention.

### 5.9 ClickHouse down (T-CH-009)

- **Symptom:** the UI's queries fail ("Storage is temporarily unavailable"); the applications are unaffected. `./argos-compose.sh status` can show the server as unhealthy, because its health check includes the ClickHouse connection.
- **What Argos shows:** agents stay connected and their data is written to the WAL; the server logs failed inserts (`Insert into <table> failed (attempt <n>, token <insert token>), retried with the same token: ...`). `argos_wal_bytes` and `argos_wal_usage_ratio` grow; at `argos.wal.warn-ratio` (0.8 of `argos.wal.max-bytes`, 10 GB) the log says `WAL usage <n>% of argos.wal.max-bytes ...: ClickHouse inserts are behind; at 100% batches are dropped (wal_full)`, and from 100 % new batches are server drops (`reason="wal_full"`). When ClickHouse is back, the writer loads the WAL in order (`Insert into <table> succeeded after <n> failed attempts (<n> s)`), without duplicates; the ingest lag of that backlog shows the outage length once, and the WAL shrinks back.
- **What to do:** bring ClickHouse back (`./argos-compose.sh logs clickhouse`, memory and disk first). **Do not restart the Argos server while ClickHouse is down:** a starting server replays its WAL into ClickHouse before it opens the agent port, so it would wait for ClickHouse and the agents' data would be dropped meanwhile. Watch `argos_wal_bytes` against the free space of the WAL disk for a long outage. Afterwards, the nightly consistency check covers the days of the outage; run `argos-admin consistency check --date <yyyy-MM-dd>` at once if you need the result sooner.

## 6. Not covered here

- PostgreSQL outage and a full server disk as chaos scenarios (18 §11.1 T-CH-010, T-CH-011) are not part of this version's chaos tests; the disk thresholds are described in 4.3.
- Installation and start-up problems: [compose.md section 11](../install/compose.md#11-troubleshooting). Upgrades and rollback: [compose.md section 15.2](../install/compose.md#152-upgrade-and-rollback).
- Turning the agent off on an application that may be affected: the kill switch (Administration > Kill switch, [agent-config.md](../agent-config.md#kill-switch)).
