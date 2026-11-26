# Issue log template

Copy this file to `issues/issue-log.md` of the private repository `<PRIVATE-RECORDS-REPO>`. Add one
entry for every problem or suspicion during the dogfooding: anything the daily or weekly checks
([ops-checklist.md](ops-checklist.md)) list under "Checks to act on" and cannot explain at once,
every application symptom that might come from the agent, every rollback or kill switch, every
FAILED section of `ops-report.sh` that repeats. When in doubt, open an entry: it costs less than a
missed agent-caused incident.

Rules:

- A suspicion counts as **agent-caused until the analysis shows otherwise** (definition in
  [README.md](README.md)). Gate 1 needs 0 agent-caused incidents in the 14 days.
- Name systems by their label (System A, System B) and hosts by a role (`application host 1`).
  Real names, addresses and people belong in `systems.md` only.
- Never paste a token, a password, the master key, `secrets/`, request data (URLs with
  parameters, SQL with values, user ids, headers) or a whole log. Quote the few lines that matter,
  masked. Diagnostic bundles are stored next to the entry, never attached to a public issue.
- Close an entry only with a verdict and its evidence.

## Severity

The words of [docs/03-definition-of-done.md](../03-definition-of-done.md) (Gate 1.5 and Gate 2:
"known critical defects 0, high ones listed as known problems in the release notes"):

| Severity | Meaning in the dogfooding |
|---|---|
| Critical | The application is affected: wrong results, exceptions, a hang, a crash or a restart, data loss, or a security exposure (a secret or personal data where it must not be). Or Argos loses or corrupts monitoring data without saying so. Roll back first. |
| High | Clear degradation without an outage: overhead beyond the comparison thresholds, a module disabled with data missing, repeated consistency mismatches, a server or agent function that does not work as documented. |
| Medium | A wrong or missing value or message with a workaround; a check that needed manual work. |
| Low | Cosmetic or documentation problems. |

## Index

| Id | Found at (UTC) | System | Severity | Summary (one line, no names) | Agent-caused | Status |
|---|---|---|---|---|---|---|
| DF-001 | | | | | | |

## Entry

Copy this block for each issue.

```
### DF-<nnn>: <one-line summary, no names>

| Field | Value |
|---|---|
| Id | DF-<nnn> |
| Found at (UTC) | <yyyy-mm-dd hh:mm> |
| Found by (role) | operator / application owner / recorder / alert |
| How it was found | daily check D<n> / weekly check W<n> / alert <rule template> / application monitoring / user report |
| System | System A / System B / Argos server |
| Instances affected | <count, roles such as "application host 1"> |
| Agent and server version | <agent x.y.z>, <server x.y.z> |
| Severity | Critical / High / Medium / Low |

**Symptom.** What was seen, when it started and ended, with numbers (latency, error rate, CPU,
heap, GC, counts). Masked log lines only.

**Impact.** On the application (users, transactions, errors, duration) and on the monitoring
(data missing or wrong, for how long).

**Agent-caused verdict.** yes / no / open. Evidence for the verdict:
- What changes when the agent is off (kill switch at <time> or -javaagent removed): <result>
- The agent's self-diagnosis and log at that time: <drops, disabled modules, errors>
- Comparison with an instance without the agent, or with the same instance before the rollout:
  <numbers, link to the comparison file>
- Other causes ruled in or out: <deployments, traffic, infrastructure, database>

**Action and rollback.** What was done, when, by which role: kill switch (Admin > the application,
or the API), -javaagent removed and restart, module disabled, configuration change, server-side
fix. Time from detection to recovery.

**Reproduction.** Steps that reproduce it outside the in-house system (sample application, settings,
load), or "not reproduced" with what was tried.

**Public issue.** Needed: yes / no. If yes: written from the reproduction only, without in-house
names, hosts, addresses, data or log extracts that were not masked; reviewed by <role>;
link: <public issue number>.

**Status.** open / analysing / fixed in <version> / not a defect / closed on <yyyy-mm-dd>.

**Files.** <paths in this repository: diagnostic bundle, logs, measurements>
```
