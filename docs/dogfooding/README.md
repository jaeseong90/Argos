# Dogfooding Argos APM on in-house systems

Before the first `0.x` pre-release, the maintainers run Argos on their own production systems and
keep a record of it. This directory holds the procedure, the checklists and the record templates
for that run. Anyone who rolls Argos out to a real application can use them the same way.

The gate is the dogfooding row of Gate 1 in
[docs/03-definition-of-done.md](../03-definition-of-done.md). Its accuracy and security rows also
apply here:

| Gate 1 requirement | What has to be true at the end |
|---|---|
| Systems | the agent runs on **at least 2** in-house systems (an application with all its instances counts as one system) |
| Duration | **2 consecutive weeks** (14 days) of operation with the daily record filled in every day |
| Agent-caused incidents | **0** on every system during the 14 days (definition below) |
| Server restarts | **0** in those 14 days. A restart of the Argos server, an upgrade included, starts the 14 days again |
| Nightly consistency check | **0** mismatched minutes on each of the 14 days (`reconcile_results`, gauge `argos_reconcile_mismatch_minutes`) |
| Security in operation | no default credentials and no database port published while it runs (SEC-74, SEC-75): `smoke.sh` passes |
| Records | kept in a **private repository**. This public repository receives an anonymized summary only |
| Acceptance | T-ACPT-07 (agent rollout) and T-ACPT-08 (collector outage) carried out and recorded by a person |

An **agent-caused incident** is any effect on an application whose cause is the agent: an
exception or changed result in the application, a hang or slower start beyond the comparison
thresholds, extra CPU or memory beyond the thresholds of
[comparison-template.md](comparison-template.md), output on the application's standard output or
log that breaks something, or an application restart needed because of the agent. Record every
suspicion in the issue log and classify it there. It counts as agent-caused until the analysis
shows otherwise.

## Documents

| Document | Use it for | Who fills it in |
|---|---|---|
| [procedure.md](procedure.md) ([한국어](procedure.ko.md)) | The rollout, step by step: pre-flight check, registration and token, one canary instance, comparison, expansion, rollback, collector outage drill, upgrades | operator, application owner |
| [comparison-template.md](comparison-template.md) | Canary against an instance without the agent: latency, errors, CPU, heap, GC, start time, throughput, each measured the same way on both | application owner |
| [acceptance-record-template.md](acceptance-record-template.md) | T-ACPT-07 and T-ACPT-08 for each system, and the Gate 1 dogfooding verdict | recorder |
| [ops-checklist.md](ops-checklist.md) ([한국어](ops-checklist.ko.md)) | Daily and weekly checks of the Argos server and the agents during the 2 weeks | operator |
| [operating-log-template.md](operating-log-template.md) | One row per day: the values of the daily check | operator |
| [issue-log-template.md](issue-log-template.md) | One entry per problem or suspicion, with its classification | anyone who finds a problem |
| [`deploy/compose/ops-report.sh`](../../deploy/compose/ops-report.sh) | Read-only: collects the values of the daily check from a running Compose installation as a Markdown snippet | operator |
| [docs/field-reports/](../field-reports/README.md) | The public, anonymized summary at the end, written from [its template](../field-reports/TEMPLATE.md) | recorder, two reviewers |
| [docs/upgrade/0.1.md](../upgrade/0.1.md) | What changes for installations that ran a development or dogfooding build (error groups split once) | operator |

Background references: [docs/install/compose.md](../install/compose.md) (server installation,
backup, upgrade, diagnostic bundle), [docs/agent-config.md](../agent-config.md) (every agent
setting, the pre-flight check, the kill switch), [docs/ops/admin-cli.md](../ops/admin-cli.md)
(`argos-admin`), [docs/ops/backup-restore.md](../ops/backup-restore.md).

## Roles

One person can hold several roles. Record the role, not the name, in anything that may become
public. Names belong in the private repository only.

| Role | Responsibilities |
|---|---|
| Operator | Runs the Argos server: installation, tokens, approvals, kill switch, daily and weekly checks, backups, the collector outage drill |
| Application owner | Owns an in-house system: change approval, agent files and JVM option on the application hosts, application restarts, the measurements outside Argos for the comparison, the decision to expand or roll back |
| Recorder | Keeps the records complete and dated in the private repository, fills in the acceptance record, writes the anonymized field report |
| Reviewers (two people) | Check the field report against the anonymization checklist of [docs/field-reports/README.md](../field-reports/README.md) before it is published |

## What goes where

All operating records stay in the private repository **`<PRIVATE-RECORDS-REPO>`** (fill in its
location for your organization here before you start; do not commit the filled-in value to a
public fork). This public repository never receives a record, a log, a screenshot or a name of an
in-house system.

| Item | Private repository `<PRIVATE-RECORDS-REPO>` | Public repository |
|---|---|---|
| Real names of systems, hosts, addresses, teams and people | yes, and only in `systems.md` (the map from System A, System B to the real system) | never: use System A, System B, `app-a`, `<collector-host>` |
| `--check` output (table and `--json`), agent logs, application logs | yes | never |
| Filled-in comparison tables and the raw measurements behind them | yes | ranges or relative values in the field report only |
| Daily operating log, `ops-report.sh` output, issue log | yes | never; public issues for product bugs, written without in-house details |
| Acceptance records (T-ACPT-07, T-ACPT-08) and the Gate 1 verdict | yes | the verdict and the counts in the field report |
| Diagnostic bundles (`argos-compose.sh diag`) | yes, read before you store them | never |
| Anonymized summary | draft | `docs/field-reports/YYYY-MM-<n>.md` after two reviewers checked it |

A layout for the private repository that the templates refer to:

```
<PRIVATE-RECORDS-REPO>/
  systems.md                          map System A, System B -> real system, owners, hosts
  system-a/
    check-<instance>-<yyyymmdd>.txt   --check table (pass 1 and pass 2)
    check-<instance>-<yyyymmdd>.json  --check --json of pass 2
    comparison-<yyyymmdd>.md          from comparison-template.md, with raw files in raw/
    acceptance.md                     from acceptance-record-template.md (T-ACPT-07)
    raw/                              pidstat, jstat, GC log extracts, access log extracts
  system-b/
    ...
  server/
    outage-drill-<yyyymmdd>.md        T-ACPT-08 drill record
    backups.md                        backup set names, master key storage confirmation (no key!)
  ops/
    operating-log.md                  from operating-log-template.md, one row per day
    daily/<yyyy-mm-dd>.md             ops-report.sh output of the day
  issues/
    issue-log.md                      from issue-log-template.md
  gate1-verdict.md                    the verdict table of acceptance-record-template.md
```

Never store an agent token, a password, the master key or the content of `secrets/` in the
private repository either. Tokens appear as their prefix (`argos_xxxxxxxx`) at most.
