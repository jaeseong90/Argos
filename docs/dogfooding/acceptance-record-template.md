# Acceptance record template: T-ACPT-07, T-ACPT-08 and the Gate 1 dogfooding verdict

Copy this file into the private repository: one copy per system as `system-<x>/acceptance.md`
(T-ACPT-07), and the T-ACPT-08 and verdict sections once as `server/outage-drill-<yyyymmdd>.md`
and `gate1-verdict.md`. Write System A, System B, `app-a`, instance labels and roles, not real
names. Times in `yyyy-mm-dd hh:mm` with the time zone. "Evidence" names a file of the private
repository (layout in [README.md](README.md)).

The checks and their thresholds come from [docs/18-test-design.md](../18-test-design.md) section
14 and scenarios S-07, S-08 of [docs/01-product-plan.md](../01-product-plan.md). Parts that are
tested automatically elsewhere (T-E2E-admin-20 installation guide and token, T-E2E-instance-25
instance page, T-ACC-18 collection gaps, T-CH-001 collector outage) do not replace this record: it
is the person's check on a real system.

## Header

| Field | Value |
|---|---|
| System | System A (`app-a`, environment `prod`) |
| Instances in total, instances with the agent at the end | |
| Argos server version (About), agent version (`--version`) | |
| Performer (role) | |
| Witness or reviewer (role) | |
| Date | |

## T-ACPT-07: agent rollout (S-07)

Procedure: [procedure.md](procedure.md) steps 1 to 4. The time budget covers the operator's work
from the start of step 1 to the confirmed connection in step 3; the application's own restart
time is recorded apart because it depends on the application (assumption of this template).

| # | Check | Expected | Start | End | Result (as seen) | Pass | Evidence |
|---|---|---|---|---|---|---|---|
| 1 | Pre-flight check pass 1 (before the token): table and exit code | only `token` FAIL, `hello` not PASS; `dns`, `tcp`, `tls`, `jdk`, `config` PASS; no `classpath` FAIL | | | | | `check-<instance>-<yyyymmdd>.txt` |
| 2 | Token issued: command or UI, prefix, expiry | `token create` exit code `0`, token shown once, prefix `argos_xxxxxxxx` noted, token file `0600` | | | | | (prefix only) |
| 3 | Approval needed | none for the exact name and environment; otherwise approved under Pending registrations | | | | | |
| 4 | Pre-flight check pass 2: table, `--json`, exit code | `Result: PASS` or `WARN`, `0 failed`, exit code `0`; `hello` check-only; no `-check` instance in the UI | | | | | `check-<instance>-<yyyymmdd>.json` |
| 5 | Agent on one instance: JVM option, restart | one `-javaagent` option; application ready | | | | | |
| 6 | Start banner | exactly one `started in <ms> ms` line, `enabled=true` | | | | | agent log extract |
| 7 | Agent `ERROR` lines, agent output on the application's stdout | `0` and `0` | | | | | |
| 8 | Instance state | `UP` within 1 minute of the application being ready | | | | | |
| 9 | Self-diagnostics | **Drops (24 h)** `0`, **Modules disabled** `None`, no module disabled automatically | | | | | self-diagnosis extract |
| 10 | Comparison for 24 hours | corrected p99 delta ≤ 1 ms, process CPU delta ≤ 3 percentage points, verdict PASS | | | | | `comparison-<yyyymmdd>.md` |
| 11 | Time budget of rows 1 to 8 without the application's restart | ≤ 10 minutes | | | | | |

| Field | Value |
|---|---|
| Application restart time (recorded apart) | |
| `classpath` WARN rows and the application owner's decision | |
| Problems, unclear steps in the procedure | |
| **T-ACPT-07 result (pass / fail)**, by (role), date | |

## T-ACPT-08: collector outage (S-08)

Procedure: [procedure.md](procedure.md) step 7. Once per installation, before the 14 days start.

| # | Check | Expected | Time | Result (as seen) | Pass | Evidence |
|---|---|---|---|---|---|---|
| 1 | Server stopped (`./argos-compose.sh compose stop server`) | stopped; planned duration 10 minutes | | | | |
| 2 | Applications during the outage | still serving; no agent `ERROR`, no agent output on stdout | | | | agent log extracts |
| 3 | Application p99 outside Argos, outage against the same length before | change within the noise of the comparison | | before: / during: | | `raw/` extracts |
| 4 | Server started (`./argos-compose.sh compose start server`), `status` healthy | healthy | | | | |
| 5 | Every agent reconnected (`collector session established`) | within 1 minute of row 4 | | last agent at: | | agent log extracts |
| 6 | Loss counts shown | **Collection quality** shows the agent drops of the outage | | | | screenshot (private) |
| 7 | Outage shaded on the charts | shaded collection gap, not TPS `0` | | | | screenshot (private) |

| Field | Value |
|---|---|
| Systems with the agent during the drill | |
| **T-ACPT-08 result (pass / fail)**, by (role), date | |

## Human checks of the run (procedure step 9)

| Item | Answer | Evidence |
|---|---|---|
| U-18 multi web application `contextPath` and the service dictionary limit | | |
| U-19 Spring Batch summary mode with thousands of chunks | | |
| U-19 log correlation with the real Logback or Log4j configuration | | |
| U-20 derived `instrumentation.app-packages` correct | | |
| N-19 stored data per day against the sizing estimate (within ±30 %) | | |
| SEC-74, SEC-75 during the run (`smoke.sh` weekly) | | |
| C-240 (12) `--check` registers no instance; a normal stop causes no down alert | | |

## Gate 1 dogfooding verdict

Fill in once at the end. The 14 days are consecutive days with the agent on every planned instance
and no restart of the Argos server; any server restart, an upgrade included, starts them again.
The daily values come from the operating log ([operating-log-template.md](operating-log-template.md)).

| System | Window start | Window end | Days recorded (of 14) | Agent-caused incidents | Rollbacks (kill switch / complete) | Issues opened (ids in the private issue log) |
|---|---|---|---|---|---|---|
| System A | | | | | | |
| System B | | | | | | |

| Requirement (docs/03 Gate 1) | Expected | Result | Evidence |
|---|---|---|---|
| In-house systems | ≥ 2 | | |
| Consecutive days of operation | 14 | | `ops/operating-log.md` |
| Agent-caused incidents | 0 | | `issues/issue-log.md` |
| Argos server restarts in the window | 0 | | operating log (server uptime column) |
| Days with nightly consistency mismatch 0 | 14 of 14 | | operating log (consistency column) |
| `smoke.sh` passed (SEC-74, SEC-75) | every weekly check | | |
| T-ACPT-07 passed | every system | | `system-<x>/acceptance.md` |
| T-ACPT-08 passed | once | | `server/outage-drill-<yyyymmdd>.md` |
| Anonymized field report published | yes | | `docs/field-reports/YYYY-MM-<n>.md` |

| Field | Value |
|---|---|
| **Gate 1 dogfooding (pass / fail)** | |
| Decided by (role), date | |
