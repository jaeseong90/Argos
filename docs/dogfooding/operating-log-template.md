# Operating log template

Copy this file to `ops/operating-log.md` of the private repository `<PRIVATE-RECORDS-REPO>` at the
start of the 14 days and fill in one row per day. The checks behind each column are in
[ops-checklist.md](ops-checklist.md). Never write a token, a password, the master key or a value
from `secrets/` here. Use the system labels of `systems.md` (System A, System B), not real names,
in anything that may be quoted in the public field report.

## Window

| Field | Value |
|---|---|
| Installation | `<label, for example "dogfooding server 1">` |
| Server version (frozen for the 14 days) | `<x.y.z>` |
| Agent version | `<x.y.z>` |
| Systems in scope | `<System A (n instances)>`, `<System B (n instances)>` |
| Window start (UTC), the `--since` value | `<yyyy-mm-ddThh:mm:ssZ>` |
| Day 14 (UTC) | `<yyyy-mm-dd>` |
| Operator, recorder (roles; names only in `systems.md`) | `<role>`, `<role>` |
| Restarted windows (date and reason) | `<none>` |

The 14 days start again on the day after any server restart, upgrades included (Gate 1: 2 weeks
without a server restart).

## Daily rows

Run `sudo ./ops-report.sh --since <window start>` after the nightly consistency check, store its
output as `ops/daily/<yyyy-mm-dd>.md`, and copy its table row here. The first seven columns come
from the report; fill in the last two yourself.

- **Consistency mismatch (minutes)**: minutes that did not match on the first nightly check of the
  previous UTC day, with the day and the result (`0 (2026-12-01, clean)`). Gate 1 needs `0` on
  every day. `no run recorded` counts as not done: run the check by hand
  ([ops-checklist.md](ops-checklist.md#run-the-consistency-check-by-hand)) and note it.
- **Server restarts**: `0 since <window start>`, or the restart time.
- **Ingest lag**: p99 (since the server start, estimated from the histogram bucket), and the share above the bucket below 5 s since the
  server start.
- **Disk**: the server's disk usage ratio and the ClickHouse volume use.
- **FIRING alerts**: open alerts at the time of the report (critical in brackets).
- **Backup**: age of the newest complete backup set.
- **Agent-caused incident**: `y` if an issue of the day was classified as agent-caused
  ([issue-log-template.md](issue-log-template.md)), else `n`. Give the issue id in the note.
- **Note**: everything else: checks to act on and what was done, FAILED sections of the report,
  issue ids, planned changes.

| Day | Date (UTC) | Consistency mismatch (minutes) | Server restarts | Ingest lag | Disk | FIRING alerts | Backup | Agent-caused incident | Note |
|---|---|---|---|---|---|---|---|---|---|
| 1 | | | | | | | | | |
| 2 | | | | | | | | | |
| 3 | | | | | | | | | |
| 4 | | | | | | | | | |
| 5 | | | | | | | | | |
| 6 | | | | | | | | | |
| 7 | | | | | | | | | |
| 8 | | | | | | | | | |
| 9 | | | | | | | | | |
| 10 | | | | | | | | | |
| 11 | | | | | | | | | |
| 12 | | | | | | | | | |
| 13 | | | | | | | | | |
| 14 | | | | | | | | | |

Example of a filled-in row (values are illustrative):

| Day | Date (UTC) | Consistency mismatch (minutes) | Server restarts | Ingest lag | Disk | FIRING alerts | Backup | Agent-caused incident | Note |
|---|---|---|---|---|---|---|---|---|---|
| 3 | 2026-12-03 | 0 (2026-12-02, clean) | 0 since 2026-12-01T00:00:00Z | p99 0.800 s (0.010% > 4.29 s since start) | server 0.31, ClickHouse 22% | 0 | 6 h old | n | |

## Weekly summary

Fill in after the weekly checks (W1 to W7 of [ops-checklist.md](ops-checklist.md)).

| Item | Week 1 (days 1-7) | Week 2 (days 8-14) |
|---|---|---|
| Days with consistency mismatch 0 (of 7) | | |
| Server restarts | | |
| Days with ingest lag p99 above 5 s | | |
| Largest disk usage ratio, ClickHouse volume use | | |
| Alerts that fired (count; agent-related count) | | |
| Backups: days with a complete set (of 7); master key check (W2) | | |
| Certificate days left; tokens expiring within 30 days | | |
| ClickHouse size per table and growth per day (W4, N-19) | | |
| Audit log reviewed (W5): findings | | |
| Versions unchanged (W6) | | |
| Issues opened; agent-caused | | |
| Instances per system (UP at the end of the week) | | |
| Kill switch or rollback used (date, system, reason) | | |

## Verdict after 14 days

| Gate 1 dogfooding condition | Result | Evidence |
|---|---|---|
| At least 2 in-house systems ran the agent for the whole window | yes / no | systems in scope above, instance counts of the weekly summaries |
| 14 consecutive days recorded (14 daily rows, 14 daily files) | yes / no | this table, `ops/daily/` |
| Agent-caused incidents: 0 | yes / no | the "Agent-caused incident" column, the issue log |
| No server restart in the 14 days | yes / no | the "Server restarts" column, audit log `SERVER_STARTED` |
| Nightly consistency check: 0 mismatched minutes on each of the 14 days | yes / no | the "Consistency mismatch" column, `reconcile_results` |
| No default credentials and no database port published (`smoke.sh` daily) | yes / no | daily D3 |

Overall: **pass / fail**. Decided by `<role>` on `<yyyy-mm-dd>`. On fail, the reason and the new
window start: `<...>`.

The verdict goes into the Gate 1 table of
[acceptance-record-template.md](acceptance-record-template.md). The public field report
([docs/field-reports/](../field-reports/README.md)) takes the counts only.

## ops-report.sh output of the day

Store each day's full output as `ops/daily/<yyyy-mm-dd>.md`. To keep it in this file instead, paste
it below the date, one section per day:

```
### <yyyy-mm-dd>

<paste the output of: sudo ./ops-report.sh --since <window start>>
```
