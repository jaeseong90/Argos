# Canary comparison template

Copy this file into the private repository as `system-<x>/comparison-<yyyymmdd>.md` for each
canary ([procedure.md, step 4](procedure.md#step-4-compare-for-24-hours)). Keep the raw files the
numbers come from in `system-<x>/raw/`. Use System A, `app-a` and instance labels C, P1, P2 here;
the real host names stay in `systems.md` of the private repository.

## Why every number is measured outside Argos

The comparison needs the same numbers for an instance **with** the agent and an instance
**without** it. An instance without the agent sends nothing to Argos, and the instance comparison
of Argos (`Instance comparison` on the dashboard, `GET /api/v1/apps/{appId}/instances/compare`)
shows p95, CPU, heap and pool usage of instances with the agent only, without p99. So every value
in the tables below comes from a tool outside Argos that you run the **same way on every
instance** (access log, load balancer, `pidstat`, `jstat`, GC log). Argos numbers appear only in
the cross-check section, for the canary.

## Setup

| Field | Value |
|---|---|
| System | System A (`app-a`, environment `prod`) |
| Canary (C) | instance label, e.g. `app-a-1`: hardware, JDK, heap options |
| Peer 1 (P1), without the agent | same role, hardware, JDK, options and traffic share as C |
| Peer 2 (P2), without the agent | optional, the same; gives the noise between two instances without the agent |
| Load balancer weight of C, P1, P2 | equal (yes / no: if not, normalize per request) |
| Agent version, configuration hash | from the banner line and the instance page |
| "Before" window | 24 h, `<yyyy-mm-dd hh:mm>` to `<...>`, local time zone `<tz>`: no instance has the agent |
| "After" window | 24 h, `<...>` to `<...>`: C has the agent, P1 and P2 do not |
| Peak hour | the hour with the most requests of the after window, `<hh:00>` to `<hh+1:00>`, same hour in the before window |
| Changes during the windows | deployments, configuration changes, incidents, traffic events: none / list |
| Measured by (role), date | |

Prepare before the "before" window and identically on C, P1 and P2 (each needs a restart, so do it
in the same change window and before the before window starts):

- access log with the request processing time in **microseconds** if the server can (Tomcat
  `AccessLogValve`: `%D` is milliseconds up to Tomcat 9 and microseconds from Tomcat 10; recent
  versions also take `%{us}T` or `%{ms}T`, check your version's documentation). With milliseconds
  only, a p99 difference of 1 ms is at the resolution limit: note it in the verdict;
- GC log: Java 9 and later `-Xlog:gc,gc+phases:file=<dir>/gc.log:time,uptime:filecount=5,filesize=20m`;
  Java 8 `-Xloggc:<dir>/gc.log -XX:+PrintGCDetails -XX:+PrintGCDateStamps
  -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=5 -XX:GCLogFileSize=20m`;
- `sysstat` installed for `pidstat` and `sar`.

## How to collect each number

Run each collector for the whole window on every instance, as the application's user where the
JVM is involved. `<pid>` is the application's process id, `<dir>` the raw directory.

| Number | Primary source, the same on every instance | Command or reading |
|---|---|---|
| Requests (throughput) | access log of the window; load balancer requests per backend | number of access log lines in the window, divided by the seconds of the window (also for the peak hour) |
| Latency p50, p95, p99 | the processing time column of the access log; or the load balancer's per-backend response time histogram | put the time column of the window's lines into `times.txt` (one number per line, e.g. `awk '{print $NF}'` when it is the last field), then `LC_ALL=C sort -n times.txt \| awk '{v[NR]=$1} END {split("50 95 99",p," "); for(i=1;i<=3;i++){k=int(p[i]*NR/100); if(k<p[i]*NR/100)k++; if(k<1)k=1; printf "p%s %s\n",p[i],v[k]}; printf "count %d\n",NR}'` (nearest-rank percentiles) |
| Error rate | status column of the access log (5xx), or the load balancer's 5xx per backend | `awk '{n++; if($9>=500)e++} END {printf "requests %d errors %d error_rate %.4f%%\n",n,e,(n?100*e/n:0)}'` on the window's lines; `$9` is the status field of the common and combined log formats, change it for another format |
| Process CPU | `pidstat` | `pidstat -u -p <pid> 60 1440 > <dir>/cpu-<label>.txt`; the `Average:` line's `%CPU` is in percent of **one** CPU: divide by `nproc` for percent of the host |
| Host CPU | `sar` (or `vmstat`) | `sar -u 60 1440 > <dir>/host-cpu-<label>.txt`; `100 - %idle` of the `Average:` line |
| Heap after GC (lowest, highest) | GC log | the occupancy after each collection (`before->after(capacity)`) of the window: lowest and highest after-value |
| Heap occupancy over time | `jstat` | `jstat -gcutil <pid> 60s 1440 > <dir>/jstat-<label>.txt`: column `O` (old generation percent); `jcmd <pid> GC.heap_info` for a snapshot with sizes |
| GC count, total pause, longest pause | GC log (Java 9 and later) | `awk '/ Pause / && $NF ~ /ms$/ {t=$NF; sub(/ms$/,"",t); n++; s+=t; if(t+0>m)m=t+0} END {printf "pauses %d total_ms %.3f max_ms %.3f\n",n,s,m}' gc.log` on the window's lines. Concurrent phases (ZGC, Shenandoah, G1 concurrent mark) are not pauses and are not counted. Java 8: read the pause times of the GC log with the same tool on every instance; `jstat` `YGC`, `FGC`, `GCT` (last line minus first line) as a cross-check |
| Application start time | the application's log | Spring Boot: `Started <Application> in <s> seconds`; the start before the agent (C, last restart without it) against the start with the agent (C, step 3), plus P1's latest start |
| Agent start time | the agent log | `grep "started in" /opt/argos-agent/logs/argos-agent.log`: the `started in <ms> ms` value of the canary's start |

Windows: cut the access log and GC log to the window by their timestamps (a log rotated at
midnight in the server's time zone gives whole days). Use the same window boundaries on every
instance.

## Results: 24-hour windows

Correction for the difference the two instances already had: `corrected delta = (C after - P1
after) - (C before - P1 before)`. Noise = `|P1 after - P2 after|` (empty without P2). The verdict
applies the threshold to the corrected delta.

| Metric | C before | P1 before | C after | P1 after | P2 after | Corrected delta | Noise | Threshold (S-07) | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| Requests per second | | | | | | | | traffic share equal (±5 %) | |
| Latency p50 (ms) | | | | | | | | report | |
| Latency p95 (ms) | | | | | | | | report | |
| Latency p99 (ms) | | | | | | | | **≤ 1 ms** | |
| Error rate (%) | | | | | | | | no increase beyond noise | |
| Process CPU (% of host) | | | | | | | | **≤ 3 percentage points** | |
| Host CPU (% busy) | | | | | | | | report | |
| Heap after GC, lowest (MB) | | | | | | | | report | |
| Heap after GC, highest (MB) | | | | | | | | report | |
| GC count | | | | | | | | report | |
| GC total pause (ms) | | | | | | | | report | |
| GC longest pause (ms) | | | | | | | | report | |

## Results: peak hour

| Metric | C before | P1 before | C after | P1 after | P2 after | Corrected delta | Noise | Threshold | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| Requests per second | | | | | | | | report | |
| Latency p99 (ms) | | | | | | | | **≤ 1 ms** | |
| Process CPU (% of host) | | | | | | | | **≤ 3 percentage points** | |

## Start time

| Start | Application `Started ... in` (s) | Agent `started in` (ms) | Note |
|---|---|---|---|
| C, last start without the agent | | - | |
| C, first start with the agent (step 3) | | | |
| P1, latest start | | - | |
| Difference C with agent minus C without | | | report |

## Verdict rules

- **PASS**: p99 and process CPU within their thresholds in the 24-hour window and in the peak hour,
  error rate not above the noise, no incident.
- **FAIL**: a threshold exceeded by more than the noise, or an incident caused by the agent: stop,
  roll back ([procedure.md, step 6](procedure.md#step-6-rollback)) and open an issue.
- **INCONCLUSIVE**: a threshold exceeded but within the noise, or p99 measured in milliseconds
  only: do not expand. Extend by 24 hours, or swap the roles (agent on P1, removed from C) and
  compare again. Record the application owner's decision.

| Field | Value |
|---|---|
| Verdict (PASS / FAIL / INCONCLUSIVE) | |
| Reason | |
| Decision (expand / extend / swap / roll back), by (role), date | |

## Cross-check with Argos (canary only)

These values do not decide the verdict. They show that Argos sees the canary as the outside tools
do.

| Value | Argos | Outside Argos (C, after) | Note |
|---|---|---|---|
| p95 (ms), 24 h | dashboard `Instance comparison`, or its CSV export (`GET /api/v1/apps/{appId}/instances/compare?from=...&to=...&format=csv`) | | Argos measures inside the application, the access log at the connector: small differences are normal |
| Error rate (%), 24 h | same | | |
| CPU, heap | same, and the instance page's **Metrics** tab | | |
| Agent CPU time, drops | instance page, **Environment** tab, **Self diagnosis** | - | drops must be `0` |
| Modules disabled | instance page header | - | must be `None` |
| Transactions per second | dashboard, filtered to the instance | requests per second | Argos may exclude requests by configuration; see **Collection quality** |

After the expansion ([procedure.md, step 5](procedure.md#step-5-expand-one-instance-at-a-time))
the instance comparison of Argos compares the instances with the agent among themselves and marks
outliers in p95, error rate and CPU; note any outlier and what it was.
