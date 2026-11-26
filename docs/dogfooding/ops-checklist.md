# Operating checklist: daily and weekly

[한국어](ops-checklist.ko.md)

During the two weeks of dogfooding ([README.md](README.md)) the operator checks the Argos server
and the agents every day and once a week, and records the result. This page lists each check: the
command or the screen, the normal value and what to do when the value is not normal. It assumes
the Docker Compose installation of [docs/install/compose.md](../install/compose.md); run the
commands in its `deploy/compose` directory, with `sudo` on a Linux Docker Engine.

Where to record:

| What | Where (private repository `<PRIVATE-RECORDS-REPO>`) |
|---|---|
| The output of `ops-report.sh` of the day | `ops/daily/<yyyy-mm-dd>.md` |
| One row per day, one summary per week, the verdict after 14 days | `ops/operating-log.md`, from [operating-log-template.md](operating-log-template.md) |
| Every problem or suspicion | `issues/issue-log.md`, from [issue-log-template.md](issue-log-template.md) |

Never paste a token, a password, the master key or anything from `secrets/` into a record.
`ops-report.sh` prints none of these, and no application, instance or host name either.

The "On deviation" column points to the symptoms and failure scenarios of the server
[runbook](../ops/runbook.md) and to the existing guides.

## Daily

### Collect the values: `ops-report.sh`

Run it once a day, **after the nightly consistency check**. The check runs at 03:00 server time by
default (`argos.ops.reconcile.cron`) and checks the previous UTC day, so a morning run of the
report reads a finished check.

```sh
cd <installation directory>/deploy/compose
sudo ./ops-report.sh --since <start of the 14 days, UTC, for example 2026-12-01T00:00:00Z> > daily.md
```

`ops-report.sh` only reads. It starts, stops and re-runs nothing and writes no row: PostgreSQL is
read as `argos_backup` in a read-only transaction inside the postgres container,
`/actuator/prometheus` on the management port inside the server container (the metrics token
stays in the container), `df` in the clickhouse container, and the `manifest.json` files of the
backup sets. It sends nothing anywhere; the result goes to standard output only.

| Option | Meaning |
|---|---|
| `--since <UTC time>` | Start of the 14-day window. A server start after this time is shown as a restart: the 14 days start again. |
| `--date <yyyy-mm-dd>` | The UTC day whose nightly consistency check is shown (default: yesterday in UTC). Use it to fill in a day you missed. |
| `--backup-dir <dir>` | The backup directory, when you pass `--dir` to `backup.sh` (default: `ARGOS_BACKUP_DIR` of `.env`, else `./backups`). |

Exit codes: `0` every section was collected; `1` a section could not be collected (it is marked
**FAILED** in the "Sections" table, the other sections are still printed); `2` usage error or no
Docker.

The output starts with the table row of the operating log (date, consistency mismatch, server
restarts, ingest lag, disk, FIRING alerts, backup) and a list **Checks to act on**. Each entry of
that list matches a row below. Copy the row into the operating log, fill in the last two columns
(agent-caused incident, note) and store the whole output as the daily file. A FAILED section is a
finding of its own: note it and look at the item by hand.

### Daily items

| # | Item | Command or screen | Normal | On deviation |
|---|---|---|---|---|
| D1 | Containers and health | `sudo ./argos-compose.sh status`; "Services" of `ops-report.sh` | `server`, `clickhouse`, `postgres` running and healthy; restart count 0 | `sudo ./argos-compose.sh logs server` (and `clickhouse`, `postgres`), [compose.md section 11](../install/compose.md#11-troubleshooting). A restart count above 0 is a crash restart: [runbook section 4.12](../ops/runbook.md#412-server-out-of-memory-or-restarting). Record it in the issue log. |
| D2 | No server restart in the window | "Server restarts" column (with `--since`) | `0 since <window start>`; the start time is the same every day | Gate 1 needs 14 days without a server restart, upgrades included. Record the restart and its cause; the 14 days start again on the day after. |
| D3 | Installation state | `sudo ./smoke.sh` (no options: it changes nothing and signs in as nobody) | exit 0; the same `NOT VERIFIABLE` lines as after the installation | A failure names the violation (file modes, published ports, database port, default password). Fix it before you go on: no default credentials and no published database port are part of Gate 1 (SEC-74, SEC-75). |
| D4 | Ingest lag | "Ingest lag" column: p99 of `argos_ingest_lag_seconds` (the server exports histogram buckets, so the report estimates p99 since the server start as the upper bound of its bucket; the section names the source), and the share of inserts above about 4.3 s since the server start | p99 at most 5 s | [runbook section 4.1](../ops/runbook.md#41-ingest-lag-rises-argos_ingest_lag_seconds-p99-above-5-s): storage panel (parts, merges), heavy queries, disk speed. The default self alert fires at 30 s. |
| D5 | Nightly consistency check | "Consistency mismatch" column and "Nightly consistency check" section: the `summary` row of `reconcile_results` for yesterday (UTC), gauge `argos_reconcile_mismatch_minutes` | one run, `0` mismatched minutes, result `clean`; gauge 0 | Gate 1 needs 0 on every day. Record the day in the issue log with the mismatch table of the report. If the result is not `repaired` (`mismatch`, `failed`, `not_rebuilt`), run the check with a rebuild by hand (below) and record its output. "No run recorded": the server was down at the job time or the job is off (`argos.ops.reconcile.enabled`); run the check by hand. Repeated mismatches: [runbook section 4.13](../ops/runbook.md#413-nightly-consistency-mismatch), report a bug. |
| D6 | Disk | "Disk" column: `argos_server_disk_usage_ratio` (largest mount of the server) and the ClickHouse data volume (`df -P /var/lib/clickhouse`) | server ratio below 0.80; ClickHouse volume below 80% | The server acts by itself (docs/17 section 9.2): at 0.80 a warning alert (critical at 0.90), at 0.90 emergency retention drops the oldest `profile_step` partitions, then `xlog`, one day at a time until usage is below 0.85, at 0.95 inserts into ClickHouse stop and only the WAL is written (they resume at 0.93). Add disk or shorten the retention (`argos-admin retention show`, then the retention settings, [compose.md section 7.4](../install/compose.md#74-retention-audit-log-and-master-key)). Record an emergency drop: its data is gone. |
| D7 | Server drops and refused data | "Ingest and storage" section: `argos_collector_dropped_total`, `argos_writer_dead_letter_blocks`, `argos_retention_emergency_drops_total` | 0, or the same value as yesterday (they count since the server start) | A rise in drops means the per-instance quota or a full WAL: [runbook sections 5.4](../ops/runbook.md#54-server-errors-resource_exhausted-and-quota-t-ch-004) and [5.9](../ops/runbook.md#59-clickhouse-down-t-ch-009). Dead-letter blocks are data ClickHouse refused: record them in the issue log and keep the diagnostic bundle. |
| D8 | Alerts and channels | "FIRING alerts" column and "Alerts and channels" section; the Alerts screen | FIRING 0 (or each one explained in the note); failed deliveries in 24 h 0; no channel `failing` | Open each FIRING alert. If the agent may be involved (an application alert that started with the rollout, agent self series), open an issue. Channel failures: send a test message from the channel page; [runbook section 4.7](../ops/runbook.md#47-alert-channel-failures). |
| D9 | Instances | "Instances" section; Instances screen | UP + WARN = the number of instances that should run (WARN = up with a warning condition, see the warning counts and D10); STALE 0; DOWN only for instances stopped on purpose; pending registrations 0 | STALE: the application hangs, the network or the agent stopped sending: look at the instance and its agent log. DOWN that was not planned: ask the application owner. Pending registrations: approve or reject them under Admin > Pending registrations. |
| D10 | Agent drops and disabled modules | "Instances" section: instances with the warning `drops`, `module disabled`, `clock skew`; the instance's "Show latest self-diagnosis" | 0 of each | Drops and an automatically disabled module are agent behaviour: open an issue with the self-diagnosis (queue, quota, the module and its reason). A clock skew is the application host's NTP: [runbook section 4.5](../ops/runbook.md#45-clock-skew-warning-warn). |
| D11 | Agent tokens and certificate | "Certificate and tokens" section | certificate 30 days or more; no agent token expires within 30 days | See W3. |
| D12 | The applications (application owner) | the application's own monitoring and logs; the agent log `argos-agent/logs/` | no new error, latency, CPU or memory change since the agent; no agent `ERROR` line; nothing from the agent on standard output | Any suspicion goes into the issue log. If the application is affected, roll back first (kill switch, [procedure.md](procedure.md)), analyse after. Fill in the "Agent-caused incident" column: `y` when an issue of the day was classified as agent-caused. |

### Run the consistency check by hand

Run it when D5 shows a result other than `clean` or `repaired`, or no run. It uses the rollup lock,
so it waits for a running nightly job. It reads ClickHouse heavily: avoid peak hours.

```sh
sudo ./argos-compose.sh compose exec -T -e JAVA_TOOL_OPTIONS= server java -Xmx256m -XX:+UseSerialGC \
  -cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication admin \
  consistency check --date <yyyy-mm-dd> --rebuild
```

`-e JAVA_TOOL_OPTIONS=` keeps the server's heap options away from this second JVM, as
`argos-compose.sh token create` does. Exit code 0: no rollup mismatch is left; 1: a mismatch is
left or the check failed; 2: usage error ([docs/ops/admin-cli.md](../ops/admin-cli.md)). The run is
recorded in `reconcile_results` with the trigger `command` and the audit event
`CONSISTENCY_CHECKED`. Run `sudo ./ops-report.sh --date <yyyy-mm-dd>` afterwards: it shows the
latest run of that day. The first, failed check still counts as a mismatch day in the log.

## Weekly

Do these on the same weekday, and write the weekly summary of the operating log.

| # | Item | Command or screen | Normal | On deviation |
|---|---|---|---|---|
| W1 | Backups | `ops-report.sh` "Backup" column and "Backups" section; the log of the daily backup job ([backup-restore.md, Daily backup](../ops/backup-restore.md#daily-backup)) | a set every day, the newest at most 26 hours old, exit code 0 in the job log; with `--clickhouse`, the newest set has a ClickHouse backup | Run `sudo ./argos-compose.sh backup` by hand and read its message ([backup-restore.md](../ops/backup-restore.md), exit codes). A day without a set is a finding: record it. |
| W2 | Master key and restore readiness | `sudo ./argos-compose.sh compose exec -T -e JAVA_TOOL_OPTIONS= server java -Xmx256m -XX:+UseSerialGC -cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication admin secrets verify --purpose backup`; the master key copy in its separate safe place | exit 0; the key copy exists where the backup record says | Without the master key a restore loses the channel secrets and command results. Restore the copy now. The full restore rehearsal is [docs/ops/recovery-rehearsal.md](../ops/recovery-rehearsal.md) (before Gate 2, then every quarter); not required inside the 14 days. |
| W3 | Certificate and token expiry | "Certificate and tokens" section; Admin > Agent tokens | gRPC certificate 30 days or more; no agent token expiring within 30 days | Replace `tls/server.crt` and `tls/server.key` and reload without a restart (`argos-admin tls reload`, [admin-cli.md](../ops/admin-cli.md)); a server restart would restart the 14 days. Rotate an expiring token (`argos-admin token rotate --prefix <argos_xxxxxxxx>`, grace 7 days) and replace the token file on the application host. |
| W4 | Retention and capacity (N-19) | `sudo ./argos-compose.sh compose exec -T -e JAVA_TOOL_OPTIONS= server java -Xmx256m -XX:+UseSerialGC -cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication admin retention show` | table sizes grow by a steady amount per day; the projection to the full retention fits the disk | Record the size per table every week. Growth per day = (this week's size - last week's size) / 7. Compare it with the estimate of docs/17 section 7.4 for your TPS and instances: a difference above 30% is the human check of N-19, record it in the field report. If the projection does not fit, add disk or shorten retention before day 14. |
| W5 | Audit log | Admin > Audit log, the last 7 days; export CSV into the private repository | only expected events: your own logins, `TOKEN_*` and `CONFIG_*` and `KILL_SWITCH` you did, `CONSISTENCY_CHECKED` every night, one `SERVER_STARTED` at the start of the window | Unknown actors, failed logins in series (`LOGIN_FAILED`, `ACCOUNT_LOCKED`), `AGENT_AUTH_FAILED` from unknown sources, or a `SERVER_STARTED` inside the window: open an issue. |
| W6 | Versions | the server version (Admin > About), agent versions (Admin > Instance management) | unchanged during the 14 days, unless a planned upgrade restarted the window | An upgrade of the server restarts the 14 days. Read [docs/upgrade/0.1.md](../upgrade/0.1.md) before you upgrade (error groups split once). |
| W7 | Diagnostic bundle (only when there is a problem) | `sudo ./argos-compose.sh diag --since 24h` ([compose.md section 15.3](../install/compose.md#153-diagnostic-bundle)) | not run in a normal week | Secrets are masked, but the bundle still holds host names, addresses and logs: read it, store it in the private repository next to the issue, never attach it to a public issue. |

## When a check cannot be done

If `ops-report.sh` fails (exit 1) for a section, do that item by hand and note why it failed. If
the server is down, record the outage time (from `sudo ./argos-compose.sh status` and the logs):
the agents keep running and buffer, and the 14 days start again only if the server restarted.

## What this checklist does not cover

- The rollout itself, the comparison with an instance without the agent, the rollback and the
  collector outage drill: [procedure.md](procedure.md).
- Prometheus and Grafana examples for the server's own metrics are not part of this version; the
  management port is described in [compose.md section 7.2](../install/compose.md#72-management-port).
