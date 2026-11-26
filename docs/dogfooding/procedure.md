# Dogfooding procedure: rolling the agent out to an in-house system

This is the order in which the agent goes onto a production application, from the pre-flight check
to the full rollout, with both ways back. It follows the agent rollout flow of
[docs/01-product-plan.md](../01-product-plan.md) section 9.5 and scenarios S-07 and S-08. Every step
says what to run, what you should see, what to record and when to stop.

[한국어](procedure.ko.md)

Overview: [README.md](README.md). Records go to the private repository `<PRIVATE-RECORDS-REPO>`;
the file names below are those of the layout in the README.

| Step | What | Who | Recorded in |
|---|---|---|---|
| [0](#step-0-prerequisites) | Prerequisites on the Argos server | operator | acceptance record, operating log |
| [1](#step-1-pre-flight-check) | Pre-flight check on the application host | application owner | `check-*.txt`, acceptance record |
| [2](#step-2-registration-and-token) | Register the application, issue its token, check again | operator, application owner | `check-*.json`, acceptance record |
| [3](#step-3-one-canary-instance) | Agent on one instance | application owner | acceptance record |
| [4](#step-4-compare-for-24-hours) | Compare the canary with an instance without the agent for 24 hours | application owner | comparison table |
| [5](#step-5-expand-one-instance-at-a-time) | Expand one instance at a time | application owner, operator | operating log |
| [6](#step-6-rollback) | Rollback: kill switch (immediate) or removal (complete) | operator, application owner | issue log |
| [7](#step-7-collector-outage-drill-t-acpt-08) | Collector outage drill (T-ACPT-08) | operator | `server/outage-drill-*.md` |
| [8](#step-8-upgrades-and-token-rotation) | Upgrades and token rotation during and after the run | operator | operating log |
| [9](#step-9-human-checks-during-the-run) | Checks only a person on a real system can make | all | acceptance record |

Placeholders used below: `<collector-host>` is the address of the Argos server that agents reach
(a name in its gRPC certificate), `app-a` and `prod` are the application name and environment,
`APP_USER` and `APP_GROUP` are the user and group that run the application, `<app libs>` is the
application's jar, WAR or library directory, `<public URL>` is the address of the Argos UI. Server
commands run in `deploy/compose` on the Argos host, with `sudo` on a Linux Docker Engine.

## Step 0: Prerequisites

Do these once, before the first system.

1. **Install the server** with [docs/install/compose.md](../install/compose.md), sections 2 to 5,
   on a host sized for the expected load
   ([CPU, memory and disk](../install/compose.md#cpu-memory-and-disk)). Use a certificate from your
   organization or your own CA for gRPC, not the self-signed option.
2. **Smoke test** green:

   ```sh
   sudo ./smoke.sh
   ```

   Expected: exit code `0` and the last line `smoke: passed (<n> checks ok, <m> not verifiable on
   this host)`. Any `smoke: FAIL` line: stop and fix the installation first. A line `NOT
   VERIFIABLE` is not a failure; record what it names.
3. **Self alerts reach a person.** In the UI, **Alert rules** lists the rules of the Argos server
   itself (target "Argos server": disk, ingest lag, channel failures, certificate and token
   expiry, parts, consistency), created at the first start. Create a notification channel under
   **Notification channels** and send its test message. Without a channel, a self alert shows only
   in the UI.
4. **One backup**, and the master key stored apart from it
   ([section 15.1](../install/compose.md#151-backup-and-restore)):

   ```sh
   sudo ./argos-compose.sh backup
   ```

   Expected: exit code `0` and a new backup set in `ARGOS_BACKUP_DIR`. Add the daily `crontab` line
   of section 15.1. Record the set name (not its content) in `server/backups.md`.
5. **Clocks synchronized** on the Argos host and on every application host (`timedatectl` shows
   `System clock synchronized: yes`, or `chronyc tracking` a small offset). The pre-flight check
   warns from 1 second of offset.
6. **Freeze the server version for the run.** Record the version shown under **About**. During the
   14 days, do not upgrade or restart the Argos server: any server restart, an upgrade included,
   starts the 14 days again (Gate 1: "no server restart for 2 weeks"). Do the collector outage
   drill of [step 7](#step-7-collector-outage-drill-t-acpt-08) **before** the 14 days start.
7. **Daily check works.** Run the daily check of [ops-checklist.md](ops-checklist.md) once and
   paste its result into the operating log, so the first day of the run is not the first try.

Record in the acceptance record: server version, installation date, smoke result, backup set,
channel test result, clock state.

## Step 1: Pre-flight check

On the application host, as the application's user, with the `java` that runs the application,
before `-javaagent` is added. Prepare the agent directory, the jar, the CA certificate and
`argos-agent.yml` as in [docs/install/compose.md section 9](../install/compose.md#9-connect-the-first-agent)
(sections "Get the agent jar" to "Configure the agent"), with `server.address:
<collector-host>:9100`, `app.name: app-a`, `app.env: prod` and `server.token-file:
/opt/argos-agent/token`. Then:

```sh
sudo -u "$APP_USER" /path/to/the/application/java -jar /opt/argos-agent/argos-agent.jar --version
sudo -u "$APP_USER" /path/to/the/application/java -jar /opt/argos-agent/argos-agent.jar --check \
  --config /opt/argos-agent/argos-agent.yml --classpath <app libs> | tee check-<instance>-<yyyymmdd>.txt
```

The last table line `Result: ...; exit code <n>` carries the check's exit code (`$?` after the pipe
would be that of `tee`). Exit codes: `0` nothing failed, `1` a check failed, `2` usage error. The
check never attaches to the application, writes no file and creates
no directory ([docs/agent-config.md, Pre-flight check](../agent-config.md#pre-flight-check---check)).

This first pass runs **before the token exists** (01 section 9.5: the check result goes into the
change request; the token comes in step 2). Expected now:

| Check | Expected in pass 1 |
|---|---|
| `config` | PASS (`app.env` must be set; a WARN for it means it is missing) |
| `token` | FAIL: no token yet. This is the only expected failure |
| `dns`, `tcp`, `tls` | PASS. The connection and the certificate chain to `<collector-host>` work |
| `hello` | not PASS (the server refuses a session without a token, or the row is SKIP) |
| `clock` | SKIP or PASS |
| `jdk` | PASS. Java 8 needs update 252 or later |
| `classpath` | PASS for every library found. WARN means a version that is not verified |
| `log-dir`, `token-file` | PASS, or `token-file` absent while there is no token |

**Stop** when anything else fails: DNS, connection or TLS (wrong address, firewall, certificate
names or CA), an unsupported JDK, a FAIL in `classpath` (unsupported library version), or a
`log-dir` that cannot be written. Fix it and run pass 1 again. A `classpath` WARN does not stop the
rollout, but record each one in the acceptance record with the application owner's decision.

Record: the file `check-<instance>-<yyyymmdd>.txt` and the start time of this step.

## Step 2: Registration and token

1. **Issue the token** on the Argos host. Either the command line:

   ```sh
   sudo ./argos-compose.sh token create --app app-a --env prod --expires-days 90
   ```

   Expected: exit code `0`, the token printed **once** with an agent configuration example. Exit
   code `4` means the application has 5 active tokens already. `--expires-days` is optional
   (default 365, 1 to 3650): choose an expiry that ends after the run and your next planned
   rotation. Or the UI: **Applications** → **New application** (name, environment, registration
   policy, recommended alert rules) → **Agent tokens** → **Issue token**; the token is shown once
   as well.

   `token create` registers the application `(app-a, prod)` itself, so agents that report exactly
   this name and environment register without approval.

2. **Store the token** on each application host as the application's user, with mode `0600`,
   without putting it on a command line
   ([Store the token](../install/compose.md#store-the-token)):

   ```sh
   sudo -u "$APP_USER" sh -c 'umask 077 && cat > /opt/argos-agent/token'
   ```

   Paste the token, press Enter, then Ctrl-D. The yml refers to it with `server.token-file`. Do not
   write the token into `argos-agent.yml`, a ticket, a chat or the private repository.

3. **Approval queue: avoid it for the rollout.** Issue the token of step 2.1 for exactly the name
   and environment the agents report; then nothing waits for approval. The queue is for agents
   that arrive with an environment nobody registered and whose registration policy is
   **Approval** (column **Registration** under **Applications**, or the server default
   `argos.agent.auto-register.<env>`): their session is refused, the request appears under
   **Pending registrations**, and **Approve** creates the application in that environment and
   issues one token, shown once; the agents connect on their next retry. The same as REST, with an
   administrator session: `GET /api/v1/admin/pending-registrations`, then
   `POST /api/v1/admin/pending-registrations/{id}/approve` (or `.../reject`). The pre-flight check
   never queues a request (its hello is check-only) and its `hello` row fails with the approval
   reason as long as the environment is not registered. If a request does appear (an agent
   started with an unplanned `app.env`), approve or reject it there and record it in the issue
   log; until the approval that agent logs one `ERROR` line `collector rejected the agent session:
   APP_NOT_REGISTERED ...`.

4. **Pre-flight check, pass 2**, the same command as in step 1, now also with `--json`:

   ```sh
   sudo -u "$APP_USER" /path/to/the/application/java -jar /opt/argos-agent/argos-agent.jar --check \
     --config /opt/argos-agent/argos-agent.yml --classpath <app libs> | tee -a check-<instance>-<yyyymmdd>.txt
   sudo -u "$APP_USER" /path/to/the/application/java -jar /opt/argos-agent/argos-agent.jar --check \
     --config /opt/argos-agent/argos-agent.yml --classpath <app libs> --json > check-<instance>-<yyyymmdd>.json
   ```

   Expected: `Result: PASS` or `Result: WARN` with `0 failed`, exit code `0`; the `token` row shows
   `argos_****` and `token-file` shows `rw-------`; the `hello` row says `check-only (no instance
   registered)`, and a `Note:` line says the server registered no instance for it. The JSON has
   `"exit_code":0`. In the UI no instance named `<instance>-check` appears (a server older than the
   agent would register one and show it as stopped: the `Note:` line says which).

   **Stop** on exit code `1`. The `hello` row names the cause (token invalid, expired or revoked,
   token of another application, application waiting for approval, agent version outside the range
   the server accepts). Fix it and run pass 2 again. Fix a `token-file` WARN (`chmod 600`) and a
   `clock` WARN (NTP) before step 3.

Record: the JSON file, the token **prefix** (`argos_xxxxxxxx`, from **Agent tokens**), the
expiry, who issued it (role), and the time.

## Step 3: One canary instance

Choose one instance that has a peer: an instance of the same application with the same role, the
same hardware and the same share of traffic, which stays without the agent (two peers are better,
see [comparison-template.md](comparison-template.md)). Start the 24-hour "before" window of the
comparison **before** this step: all of them without the agent.

1. Add one JVM option to the canary's start configuration (for example `JAVA_OPTS` or the service
   unit), exactly:

   ```sh
   -javaagent:/opt/argos-agent/argos-agent.jar=config=/opt/argos-agent/argos-agent.yml
   ```

2. Restart the application on that instance in your usual change window. Record the time the
   restart started and the time the application reported it was ready.
3. **Banner.** The agent log has exactly one start banner:

   ```sh
   sudo grep "started in" /opt/argos-agent/logs/argos-agent.log
   sudo grep "collector session established" /opt/argos-agent/logs/argos-agent.log
   ```

   Expected: one line `Argos agent <version> (commit <c>, protocol <p>) started in <ms> ms:
   app=app-a env=prod ... token=argos_**** ... enabled=true ...` per start, and one `collector
   session established` line.
4. **Nothing from the agent in the application's own output.** In the application's standard output
   and its log of this start there is no line from the agent (the agent writes only to its own log
   file; it falls back to standard error only when it cannot write that file):

   ```sh
   grep -ci "argos" <application stdout or console log of this start>
   sudo grep -c " ERROR " /opt/argos-agent/logs/argos-agent.log*
   ```

   Expected: `0` and `0` (one count per agent log file). A line of your own that names the agent,
   such as a start script echoing its JVM options, does not count; note it. Read every `WARN` of
   the agent log and record it.
5. **Instance UP.** In the UI, the application's **Instances** screen lists the canary as `UP`;
   or open `<public URL>/api/v1/apps?q=app-a` in the signed-in browser: `instances_up` is `1`.
6. **Self-diagnostics clean.** On the instance page (**Instances** → the canary):
   - the header shows **Drops (24 h)** `0` and **Modules disabled** `None`;
   - the **Environment** tab lists no module under **Instrumentation modules** with state
     `Disabled` or with errors, and **Self diagnosis** shows `0` drops;
   - for the full report (operator role): **Commands** tab → **Run a self diagnosis**, then
     **Show latest self-diagnosis**. Check: no dropped items, no module disabled automatically,
     `instrumentation.app-packages` with source `default` names the application's own packages
     (U-20, [step 9](#step-9-human-checks-during-the-run)).
7. Send normal traffic and check that transactions of the canary appear (XLog, dashboard).

**Stop and roll back** ([step 6](#step-6-rollback)) when: the application does not start or
behaves differently, the agent writes to the application's output, the agent log has an `ERROR`,
the instance does not become `UP` within 1 minute of the application being ready, drops are not
`0`, or a module was disabled automatically. Record it in the issue log.

Record in the acceptance record (T-ACPT-07): clock times of steps 1 to 3, the banner line's
`started in` value, the error and output counts, the instance state, the self-diagnosis result.

## Step 4: Compare for 24 hours

Leave the canary running for 24 hours of normal traffic and fill in
[comparison-template.md](comparison-template.md): latency p50, p95, p99, error rate, CPU, heap,
GC, start time and throughput of the canary against its peer, each measured with the **same tool
outside Argos** on both instances. An instance without the agent has no data in Argos, and the
instance comparison of Argos shows p95, not p99, so Argos numbers serve only as a cross-check of
the canary.

Thresholds (S-07): **CPU difference at most 3 percentage points** and **p99 difference at most
1 ms**, after the correction for the difference the two instances had before the agent (the
template shows the calculation).

- Both within the thresholds and no incident: continue with step 5.
- Above a threshold and above the noise between two instances without the agent: **stop, roll
  back** (step 6) and open an issue.
- Above a threshold but within that noise: inconclusive. Do not expand. Extend the comparison by
  another 24 hours, or swap the roles (agent on the peer, removed from the canary), and decide
  with the application owner. Record the decision.

## Step 5: Expand one instance at a time

1. Add the agent to the next instance as in step 3 (files, token file, JVM option, restart) and
   check items 3 to 6 of step 3 for it. Keep at least one instance without the agent until the
   last instances, so a comparison stays possible.
2. After each instance, look at the application's **Instance comparison** (dashboard section and
   **Instances** screen): with 3 or more instances that have a value, the server marks outliers in
   p95, error rate and CPU. An outlier among the instances with the agent is a reason to stop and
   look before the next instance.
3. Check **Collection quality** on the dashboard: agent drops `0`, no unexpected excluded requests,
   no clock skew warning.
4. When every instance runs with the agent, the 14 days of the run can start (if the server has
   not restarted since; see [step 0](#step-0-prerequisites)). Record the start time in UTC. Fill
   in the operating log every day from then on ([ops-checklist.md](ops-checklist.md)); the daily
   `sudo ./ops-report.sh --since <start, YYYY-MM-DDTHH:MM:SSZ>` reports a server start after that
   time as a restart.

## Step 6: Rollback

Two ways back, both without a change on the Argos server
([docs/agent-config.md, Kill switch](../agent-config.md#kill-switch)).

### Immediate: kill switch, no restart

Every instrumentation point of the agent becomes a no-op at once; the application keeps running
unchanged; metrics and the session stay, so the switch can be turned back.

- UI: **Configuration** → the application → section **Kill switch** → **Instances** (every
  instance, or one) → **Stop tracing (kill switch on)**, enter a reason, confirm. **Results by
  instance** shows each instance as applied; the instance shows `WARN` until the switch is turned
  off.
- REST, with an administrator session: `POST /api/v1/admin/apps/{appId}/kill-switch` with the body
  `{"enabled": false, "reason": "<why>", "instance_ids": [<id>]}` (no `instance_ids`, or an empty
  list, for every instance). The answer `202` carries the configuration change; its per-instance
  results are at `GET /api/v1/admin/config/changes/{changeId}`.

Expected: every connected instance applied within **10 seconds** (N-25), the agent log has one line
for the switch and the server records `KILL_SWITCH` in the audit log. The answer `400` means no
targeted agent allows the key `enabled` remotely (`remote-config.allow` was narrowed): use the
complete rollback. Turn it back with **Resume tracing (kill switch off)** (`"enabled": true`).

### Complete: remove the agent, restart

1. Remove the `-javaagent:...` option from the start configuration and restart the application.
   When the option cannot be removed (a shared start script, a platform setting), add
   `-Dargos.agent.disabled=true` instead: the agent then installs nothing at all at the next start.
2. Expected: the application starts as before the agent; the instance shows as stopped in Argos
   (the agent says goodbye on shutdown, so no instance-down alert fires); no new
   `started in` banner in the agent log.
3. Leave the instance to the automatic archiving (7 days), or archive it under **Instance
   management**.

Record every rollback in the issue log: which way, why, the time, how long until applied.

## Step 7: Collector outage drill (T-ACPT-08)

Scenario S-08: the Argos server is down and the applications are not affected. Run the drill once,
**before the 14 days start** (it restarts the server). Pick a time with normal traffic, tell the
alert recipients, and have the application owner measure latency outside Argos (as in the
comparison) during the drill and for the same length of time before it.

1. Stop the server for 10 minutes (longer than the about 2 minutes of data the agents keep, so
   that losses are counted):

   ```sh
   sudo ./argos-compose.sh compose stop server
   ```

   Record the clock time. Expected: the applications keep serving; their latency outside Argos does
   not change; the agent logs warn about the lost connection and retry with backoff, without
   `ERROR`; nothing from the agent on the application's output.
2. Start it again:

   ```sh
   sudo ./argos-compose.sh compose start server
   sudo ./argos-compose.sh status
   ```

   Record the clock time when `status` shows the server `healthy`.
3. Expected: within **1 minute** of that time every agent logs `collector session established`
   again (read the time of that line in each agent log), and the instances show `UP`.
4. In the UI, for the drill's time range: the dashboard's **Collection quality** shows the agent
   drops of the drill, and the charts show the outage as a shaded collection gap, not as TPS `0`.

**Stop** the drill and start the server at once if an application is affected. That is an
agent-caused incident: record it.

Record in `server/outage-drill-<yyyymmdd>.md` and in the T-ACPT-08 rows of the acceptance record:
stop and start times, the time until the last agent reconnected, the application p99 before and
during the outage (outside Argos), the drop counts shown, a screenshot of the shaded gap (in the
private repository only).

## Step 8: Upgrades and token rotation

- **Server upgrade:** after the 14 days, or accept that the 14 days start again. Server first,
  agents afterwards: [docs/install/compose.md section 15.2](../install/compose.md#152-upgrade-and-rollback)
  (`sudo ./argos-compose.sh upgrade --image argos-server:<new version>`, rollback with
  `upgrade --rollback`). The upgrade runs a consistency check of the previous day; record its
  result.
- **Agent upgrade:** replace the jar and restart one instance at a time, as in step 5. Upgrading
  from a development or dogfooding build splits error groups once, and changing
  `instrumentation.app-packages` splits them again: read [docs/upgrade/0.1.md](../upgrade/0.1.md)
  and record the time of the change in the operating log.
- **Token rotation:** **Agent tokens** → **Rotate**, or `argos-admin token rotate` in the server
  container ([docs/ops/admin-cli.md](../ops/admin-cli.md#token-commands-and-a-running-server)):

  ```sh
  sudo ./argos-compose.sh compose exec -T -e JAVA_TOOL_OPTIONS= server java -Xmx256m -XX:+UseSerialGC \
    -cp @/opt/argos/app/jib-classpath-file io.argos.server.ServerApplication \
    admin token rotate --prefix argos_xxxxxxxx --grace-days 7
  ```

  The new token is printed once. The old token keeps working during the grace period (1 to 30
  days, default 7). The agent reads the token file at start: replace the file on every host and
  restart each instance within the grace period. **Revoke** ends the sessions of a token within 5
  seconds. Other `argos-admin` commands below (`retention show`, `version`) run with the same
  command line.

## Step 9: Human checks during the run

These open points can only be answered on real systems. Record the answer and the evidence in the
acceptance record.

| Item | Question | How to check |
|---|---|---|
| U-18 | Tomcat with several web applications: does the `contextPath` prefix stay within the service dictionary limit (web applications × services ≤ 10,000)? | service statistics of the application; **Dictionary overflows** in the instance's **Self diagnosis** stays `0` |
| U-19 | Spring Batch steps with thousands of chunks: is the summary mode enough? | transactions of kind `BATCH`: the detail shows summary rows, the batch is not split into an unusable number of steps, the HTTP statistics are not affected |
| U-19 | Log correlation with the application's real Logback or Log4j configuration | the application's log lines carry the correlation keys while a transaction runs ([Log correlation](../agent-config.md#log-correlation)) |
| U-20 | Is the derived `instrumentation.app-packages` right for the application? | self-diagnosis: the value with source `default`; compare with the application's own packages; the top application frame of error stacks is a frame of the application |
| N-19 | Does the capacity estimate hold within ±30 %? | the daily growth of the stored data under the real load against the [sizing table](../install/compose.md#cpu-memory-and-disk): `argos-admin retention show` in the server container ([docs/ops/admin-cli.md](../ops/admin-cli.md)) |
| SEC-74, SEC-75 | Still no default credentials and only the two ports published, during the run | `sudo ./smoke.sh` in the weekly check ([ops-checklist.md](ops-checklist.md)) |
| C-240 (12) | `--check` against the real server registers no instance; an application stopped normally causes no instance-down alert | step 2.4 and step 6 (complete rollback) |
