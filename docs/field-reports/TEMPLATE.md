<!-- SPDX-License-Identifier: Apache-2.0 -->

<!--
Copy this file to docs/field-reports/YYYY-MM-{n}.md and fill it in from the operating records in
the private repository. Replace every {...} placeholder and delete the guidance comments. Go
through the anonymization checklist in README.md before opening the pull request: ranges instead
of exact figures, no names, hosts, addresses, URLs or business data.
-->

# Field report YYYY-MM-{n}

| | |
|---|---|
| Period | {YYYY-MM-DD} to {YYYY-MM-DD} ({n} days) |
| Argos version | server {version}, agent {version} |
| Systems | {n} (called system A, system B, ...) |
| Report author | {maintainer role or public handle} |

## Summary

<!-- Three to five sentences: what ran, for how long, the headline result and the main open item. -->

{summary}

## Environment

One row per system. Give versions exactly, sizes and traffic as ranges.

| | System A | System B |
|---|---|---|
| JDK (vendor, version) | {for example Temurin 17.0.x} | |
| Spring Boot / Spring Framework | {version or none} | |
| Servlet container (embedded or standalone, version) | {for example embedded Tomcat 10.1.x} | |
| Databases (kind, version) and connection pool | {for example PostgreSQL 16, HikariCP} | |
| Outgoing HTTP clients | {libraries} | |
| Virtual threads | {on or off} | |
| Runtime (container or host, OS family) | {for example container, Linux} | |
| Instances with the agent | {range, for example 2 to 4} | |
| Peak transactions a second, all instances | {range, for example 50 to 200} | |
| Argos server | {Compose single node, CPU and memory range} | |

## Rollout procedure

The procedure of [docs/dogfooding/README.md](../dogfooding/README.md): pre-flight check,
registration and token, one canary instance, comparison, expansion.

| Check | System A | System B | Notes |
|---|---|---|---|
| T-ACPT-07: `--check` with no FAIL, registration and token, one canary instance, comparison within limits, self-diagnostics drops 0 and modules auto-disabled 0, procedure within 10 minutes | {pass or fail} | {pass or fail} | |
| T-ACPT-08: application unaffected while the collector is down, reconnect within 1 minute after recovery, gap shading and loss count shown | {pass or fail} | {pass or fail} | |
| Canary period before expansion | {hours} | {hours} | |
| Rollback used (kill switch or `-javaagent` removed) | {no, or which and why} | | |

## Overhead: canary against an instance without the agent

Both instances measured with the same tool outside Argos over the same window. Give relative
values or ranges and name the method; never the raw figures.

| Measure | System A | System B | Method |
|---|---|---|---|
| Response time p50, difference | {for example within 1 ms} | | {access log, load balancer metrics} |
| Response time p95, difference | | | |
| Response time p99, difference (limit 1 ms) | | | |
| Error rate, difference | | | |
| Process CPU, difference (limit 3 percentage points) | {for example +1 to +2 pp} | | {pidstat, top, container metrics} |
| Heap used after GC, difference | {for example +20 to +40 MiB} | | {jstat, GC log} |
| GC pause time, difference | | | {GC log} |
| Startup time, difference | {for example +1 to +2 s} | | {application log} |

## Two weeks of operation

The Gate 1 window: 14 consecutive days without a server restart; a restart starts the window again.

| Item | Result |
|---|---|
| Consecutive days without an Argos server restart | {n} of 14 |
| Incidents caused by the agent (application errors, latency, startup or shutdown problems) | {0, or count with public issue numbers} |
| Days with nightly consistency check mismatch 0 | {n} of 14 |
| Days with agent drops 0 | {n} of 14 |
| Agent modules disabled automatically | {0, or which modules} |
| Argos server incidents (disk, ingest lag, out of memory) | {none, or short description} |
| Backups taken and one restore rehearsed | {yes or no} |

## Problems found

Public issue numbers only. One line each: what happened, the effect, the state.

| Issue | Area | Effect | State |
|---|---|---|---|
| #{n} | {agent, server, UI, install} | {one line} | {fixed in version, open} |

## Not verified

What this report does not cover and why (for example a library the systems do not use, a
feature that was not turned on, a measurement that needs a dedicated machine).

- {item}: {reason}

## Contribution to the Gate 1 decision

Which Gate 1 dogfooding criteria this report supports, and which it does not.

| Gate 1 dogfooding criterion | Supported by this report |
|---|---|
| Two or more in-house systems | {yes or no} |
| Two consecutive weeks of operation without a server restart | {yes or no} |
| No incident caused by the agent | {yes or no} |
| Nightly consistency check mismatch 0 during the period | {yes or no} |
| Records kept in the private repository, public summary anonymized | {yes or no} |

<!-- The release decision itself is made by the maintainers with all Gate 1 evidence; this report is one input. -->

## Anonymization review

```text
Anonymization review 1: {maintainer role or public handle}, {YYYY-MM-DD}, checklist complete
Anonymization review 2: {maintainer role or public handle}, {YYYY-MM-DD}, checklist complete
```
