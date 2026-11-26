# Server load test (`serverLoadTest`, WO-29)

Fake agents at protocol level (18 §8) against the whole server (boot jar, child JVM), PostgreSQL and ClickHouse; 01 N-06, N-18, D-13; 12 §11.

> **INVALID as a measurement.** Profile `smoke` on machine profile `dev-laptop-shared`: this is not the N-06 run (5,000 TPS, 50,000 step rows/s, 10,000 statistics rows/s for 1 hour on 13 §7.1 hardware). Every timing and resource verdict is INVALID and its value is a reference only; the invariants (D-13 counts, OOM 0, ClickHouse settings) are judged in every profile. The full run on target hardware: `./gradlew serverLoadTest -Pbench.machine-profile=<id> -Pserver.load.profile=full`.

> **Integration note (added by hand after the run, WO-29).** The HdrHistogram extrapolation below
> (about 235 KiB per (app, service) window, 3.36 GiB for 30 apps x 500 services) exceeds 10 % of
> the 4 GiB heap of 12 §11, and about 70 MiB of the 115 MiB are iterator objects
> (`HistogramIterationValue`, `PercentileIterator`, `RecordedValuesIterator`) that HdrHistogram
> keeps per histogram: a PM decision for 12 open item 3 (laptop value, INVALID).
>
> **Resolved after the run (pair 16 wrap-up, ADR-0010 C-244 (1)).** The alert window slots no longer
> hold an HdrHistogram each: a slot keeps its non-empty buckets as packed longs and a window records
> them into one histogram of the same layout (same percentiles). `WindowHeapBudgetTest` measures the
> live heap of one application at 1/30 of N-06 over 500 services for a full 15-minute retention:
> 119.86 MiB per application before, 11.31 MiB after, i.e. 3,595.7 MiB -> 339.4 MiB for 30 apps x
> 500 services (budget 400 MiB). The figures of this report are those of the run, before the change.

## Run conditions

| Item | Value |
|---|---|
| Date (UTC) | 2026-11-25T15:27:27Z |
| Code | `eb9693b` plus uncommitted changes |
| Machine profile | `dev-laptop-shared` |
| Machine | Windows 11 10.0 (amd64), 12 logical CPUs, 15.35 GiB RAM (as the generator JVM sees them; Docker may see less) |
| Java | Oracle Corporation 21 |
| Stack | PostgreSQL postgres:16.15@sha256:5db6dac2ef5dcff512f4f00a009c3c332b9f2f28f24277175bf3e4a3d02ef83a (container limit 1.00 GiB), ClickHouse 26.8.34.13 (container limit 4.00 GiB, max_server_memory_usage 3.60 GiB) |
| Server JVM options | `-Xms2g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxDirectMemorySize=512m -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=C:\workspace\Argos\test\load\fake-agents\build\reports\server-load\work\server -XX:ErrorFile=C:\workspace\Argos\test\load\fake-agents\build\reports\server-load\work\server\hs_err_pid%p.log -Xlog:gc*:file=C:\workspace\Argos\test\load\fake-agents\build\reports\server-load\work\server\gc.log:time,uptime:filecount=5,filesize=20m -XX:NativeMemoryTracking=summary -XX:StartFlightRecording=name=argos-load,settings=C:\workspace\Argos\test\load\fake-agents\build\reports\server-load\work\server\pinned.jfc,disk=true,maxsize=256m -Djava.security.egd=file:/dev/urandom -Dfile.encoding=UTF-8 -Duser.timezone=UTC -Djava.io.tmpdir=C:\workspace\Argos\test\load\fake-agents\build\reports\server-load\work\server\server-tmp -DLOG_PATH=C:\workspace\Argos\test\load\fake-agents\build\reports\server-load\work\server\server-logs` |
| Profile | `smoke` |
| Generator | --instances=10 (apps 5, env prod/stg)<br>--tps=1000<br>--profile-rate=0.12 --steps-per-tx=83 (derived from the N-06 step rate; 9960 step rows/s)<br>--stats-rows=2000/s (1800 SqlStat + 200 ExtStat per instance and 10 s window)<br>--metrics=100 series per instance and 5 s<br>--warm-up=PT1M --duration=PT5M<br>--late-arrival=0.005 (PT10S..PT1M) --duplicate=0.001 --clock-skew=+-PT2S (2 instance(s) PT1M)<br>--services=100 (Zipf s=1.1) --error-rate=0.010, elapsed log-normal (median 80 ms, sigma 1.0) + 1% outliers 2-30 s<br>--attack=gzip-bomb,batch-overflow,dict-flood,instance-flood,unauth-flood<br>--users=10 every PT5S |
| Steps per profile | 83 = round(step rows/s / (TPS x profile rate)): 18 §8's default of 15 steps gives 9,000 step rows/s at 5,000 TPS and contradicts N-06's 50,000; the N-06 rate governs until the PM decides (WO-29 PM question 1) |
| Sessions | 10 accepted, over 3 shared TLS channel(s); apps [load-app-0/prod, load-app-0/stg, load-app-1/prod, load-app-1/stg, load-app-2/prod] |
| Agent clock skews (ms) | [60000, 60000, -1848, 1203, -1180, 80, -1586, 756, 399, -890] |
| Windows (UTC) | start 2026-11-25T15:27:27Z, measured 2026-11-25T15:28:51Z .. 2026-11-25T15:33:51Z (300 s), load stopped 2026-11-25T15:34:09Z, drained in 2 s, end 2026-11-25T15:35:06Z |
| Topology | managed single host: generator, server child JVM and the Testcontainers databases share this machine (18 §2.1 asks for a separate load generator; an external-target mode is a follow-up, WO-29 PM question 2) |

## Verdicts (N-06, N-18, D-13, 12 §11)

| ID | Req. | Criterion | Target | Measured | Verdict | Note |
|---|---|---|---|---|---|---|
| T-NFR-06 | N-06 | Transactions per second, sustained | >= 5,000/s | 1,000/s | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. accepted by the server (ack minus dropped_items) in the measured window |
| T-NFR-06 | N-06 | Profile step rows per second, sustained | >= 50,000/s | 9,915/s | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. steps of acknowledged profiles in the measured window |
| T-NFR-06 | N-06 | Statistics rows per second (SqlStat + ExtStat), sustained | >= 10,000/s | 2,006/s | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. acknowledged rows of the 10 s windows in the measured window |
| T-NFR-06 | N-06 | Duration of the measured window | >= 1 h | 5 min | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. after the warm-up |
| T-NFR-06 | N-06 | Ingest lag p99 (argos_ingest_lag_seconds) | <= 5 s | 2.13 s | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. receive to ClickHouse insert acknowledged; histogram difference over the window |
| T-NFR-06 | N-06 | Dashboard 10 users, 5 s polling: p95 | <= 2 s with 10 users, no error | 87 ms (1,800 calls, 0 errors) | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. client wall time of dashboard 1 h, XLog 5 min and SQL Top N 1 h |
| T-NFR-06 | N-06 | Active parts per partition (maximum during the run) | < 300 | 8 | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. system.parts every 5 s |
| T-NFR-06 | N-06 | DelayedInserts during the run | 0 | 0 | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. system.events difference from the first to the last sample |
| T-NFR-06 | D-13 | Sent = count(xlog) = uniqExact(tx_id) = sum(service_stat_1m.count) | equal (late arrivals, duplicates and clock skew included) | 381,896 / 381,896 / 381,896 / 381,896 | **PASS** | generator counter of acknowledged transactions against the load apps' rows |
| T-NFR-18 | N-18 | ClickHouse memory settings | max_server_memory_usage per MemoryBudget, mark_cache_size 512 MiB, uncompressed_cache_size 0, reader query 3 GiB share / 10 s / 4 threads | as configured | **PASS** | merged server configuration and the reader's getSetting() |
| T-NFR-18 | N-18 | OOM | 0 (server, ClickHouse, containers) | 0 | **PASS** | server exit, hs_err, OutOfMemoryError in the log; ClickHouse MEMORY_LIMIT_EXCEEDED; docker OOMKilled. The 72 h part is WO-52 |
| T-NFR-06 | 12 §11 | Server resident set peak | <= heap + 1 GiB (3.00 GiB) | 1.95 GiB | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. see the memory section for the source |
| T-NFR-18 | N-18 | ClickHouse MemoryTracking peak | <= max_server_memory_usage (3.60 GiB) | 434.61 MiB | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. system.metrics every 5 s |
| T-SEC-11 | SEC-11 | gzip bomb under load refused before decoding | every call RESOURCE_EXHAUSTED, reason message_size | RESOURCE_EXHAUSTED message_size 3 | **PASS** | 18 §8 gzip-bomb, 4 MiB after decompression |
| T-SEC-11 | SEC-11 | 5,001-record batches under load refused | every call RESOURCE_EXHAUSTED, reason batch_items | RESOURCE_EXHAUSTED batch_items 3 | **PASS** | 18 §8 batch-overflow, 11 §4.4 |
| T-SEC-13 | SEC-12, SEC-13 | dictionary flood under load accepted within the instance quota | every call answered OK (ACK_OK or ACK_PARTIAL_DROP) | ACK_PARTIAL_DROP 2 | **PASS** | 18 §8 dict-flood; quota drops are counted, the per-app cap is T-SV-014 |
| T-SEC-14 | SEC-14 | instance flood under load capped at 100 new instances per app and hour | exactly 100 accepted, the rest INSTANCE_LIMIT / RESOURCE_EXHAUSTED | 100 accepted, 100 refused (INSTANCE_LIMIT/RESOURCE_EXHAUSTED 100) | **PASS** | 18 §8 instance-flood; per-token session limits raised for the run so that the per-app hourly cap is what stops it |
| T-SEC-11..14 | N-10d | another app's transactions reach argos_reader during the attacks | <= 5 s | 52 transactions, p50 997 ms, max 2058 ms, 0 not visible, 0 calls not ACK_OK | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. quiet instance, one transaction every 200 ms, polled every 100 ms |
| T-SEC-11..14 | N-10d | steady load ingest lag p99 during the attacks | <= 5 s | p50 1.20 s, p99 2.14 s, 331 observations | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. argos_ingest_lag_seconds difference over the attack phase (every app) |
| T-SEC-11 | SEC-11, N-10d | server resident set growth during the attacks | <= 200 MB | +31.29 MiB (before 1.86 GiB, max 1.89 GiB) | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. resident set sampled every 250 ms |
| T-NFR-06 | C-157 (1) | idle sessions accepted up to the planned number | 1000 accepted and still open | 1000 accepted, 1000 open | **PASS** | idle-session phase after the load |
| T-NFR-06 | C-157 (1), 12 §11 | heap of 5,000 sessions (extrapolated) | <= 10 % of -Xmx | 71.11 MiB (14.56 KiB per session) | **INVALID** | profile smoke, not N-06 at full scale on 13 §7.1 hardware: reference only. proposal threshold of this WO, not a design target |
| T-SEC-15 | SEC-05, SEC-15 | unauthenticated Hello flood blocked after 20 failures | 20 TOKEN_INVALID, then RESOURCE_EXHAUSTED auth_blocked; counter +80 | 20 TOKEN_INVALID, 80 blocked (auth_blocked), argos_collector_auth_blocked_total +80, in 76 ms | **PASS** | 18 §8 unauth-flood, 100 Hellos with unknown tokens from 127.0.0.1 |

Every invariant passed.

## Sent and stored

| Data | Acknowledged (generator) | Stored (load apps) | Rate in the window |
|---|---|---|---|
| Transactions (xlog) | 381,896 | 381,896 | 1,000/s |
| Profile steps (profile_step) | 3,794,843 | 3,794,843 | 9,915/s |
| SqlStat (sql_stat_raw) | 685,800 | 685,800 | 1,805/s |
| ExtStat (ext_stat_raw) | 76,200 | 76,200 | 201/s |
| Metric samples (metric) | 76,100 | 76,862 | 200/s |

Transactions: 1,915 late, 4 duplicate batches (4 answered ACK_DUPLICATE), 3,667 generated as errors, 0 dropped by the server (dropped_items), 0 unacknowledged after every retry. Profiles: 45,721. The metric table also holds the agents' window counters (argos_agent_*), so it can exceed the samples sent.

| App | Acknowledged | count(xlog) |
|---|---|---|
| load-app-0/prod (id 1) | 76,400 | 76,400 |
| load-app-0/stg (id 2) | 76,400 | 76,400 |
| load-app-1/prod (id 3) | 76,397 | 76,397 |
| load-app-1/stg (id 4) | 76,399 | 76,399 |
| load-app-2/prod (id 5) | 76,300 | 76,300 |

## Ingest lag and WAL fsync

| Item | Value |
|---|---|
| argos_ingest_lag_seconds (measured window) | p50 1.19 s, p99 2.13 s, mean 1.15 s, 7,549 observations |
| Worst 5 s interval p99 (whole run) | 2.39 s |
| argos_wal_fsync_seconds (measured window, C-158 (6), D-21) | p50 1 ms, p99 4 ms, max 6 ms, 7,752 fsyncs |

Quantiles are interpolated in the histogram buckets of the difference between the scrapes at the start and the end of the measured window; the fsync max is the largest `_max` (Micrometer's 2-minute decaying maximum) seen by the 5 s samples in the window. WAL fsync on a dedicated disk needs a human run (a laptop SSD shared with Docker is not representative).

## Dashboard users

10 users, every 5 s; 1,800 calls in the measured window, 0 errors, p95 87 ms.

| Call | Calls | Errors | p50 | p95 | max |
|---|---|---|---|---|---|
| dashboard 1 h | 600 | 0 | 83 ms | 91 ms | 114 ms |
| sql top-n 1 h | 600 | 0 | 33 ms | 40 ms | 53 ms |
| xlog 5 min | 600 | 0 | 45 ms | 54 ms | 78 ms |

## ClickHouse

| Item | Value |
|---|---|
| Active parts per partition, maximum | 8 (profile_step/20261125) |
| DelayedInserts during the run | 0 |
| QueryMemoryLimitExceeded during the run | 0 |
| MemoryTracking peak | 434.61 MiB |
| MemoryResident peak | 978.61 MiB |
| Settings (N-18) | max_server_memory_usage 3.60 GiB, mark_cache_size 512.00 MiB, uncompressed_cache_size 0; reader 1.44 GiB / 10 s / 4 threads |
| MEMORY_LIMIT_EXCEEDED in the error log | 0 |

## Memory breakdown (12 §11, 17 §7.1)

| Item | Value |
|---|---|
| Budgets | server -Xmx 2g + off-heap 1 GiB (direct 512m); ClickHouse container 4.00 GiB; PostgreSQL container 1.00 GiB |
| Server resident set peak | 1.95 GiB (sampled maximum 1.94 GiB); source: Windows working set: Get-Process WorkingSet64 (sampled) and PeakWorkingSet64 (OS peak); not identical to a Linux RSS |
| Server heap used / committed peak | 1.37 GiB / 2.00 GiB |
| Server non-heap used peak | 192.02 MiB |
| Server direct buffers peak | 47.05 MiB |
| Server live threads peak | 102 |
| Sessions peak | 1000 |
| Writer queue rows peak | 34,124 |
| ClickHouse container peak (docker stats) | 626.10 MiB of 4.00 GiB |
| PostgreSQL container peak (docker stats) | 74.51 MiB of 1.00 GiB |
| Native memory (NMT, end of run): committed | 2.41 GiB in total, off-heap 423.63 MiB |
| NMT committed by category (>= 1 MiB) | Java Heap 2.00 GiB, Class 16.22 MiB, Thread 8.90 MiB, Code 75.94 MiB, GC 99.79 MiB, Compiler 1.09 MiB, Internal 1.96 MiB, Other 47.17 MiB, Symbol 32.50 MiB, Native Memory Tracking 7.38 MiB, Shared class space 12.56 MiB, Arena Chunk 3.28 MiB, Tracing 15.37 MiB, Synchronization 1.52 MiB, Metaspace 99.09 MiB |

## Collector and generator counters

| Item | Value |
|---|---|
| argos_collector_rejected_total by reason | batch_items 3, instance_limit 100, message_size 3 |
| argos_collector_dropped_total by reason | dead_letter 0, quota 5,907 |
| Samples | 88 taken, 0 failed |
| Generator calls | 8,714 calls, 0 answered with an error status, 0 retries, 0 reconnects, 0 throttle hints |
| Generator failures | 0 |

## Heap histogram and pinned virtual threads (end of run)

Live heap after a full GC (`jcmd GC.class_histogram`): 263.05 MiB in 2,906,091 objects. HdrHistogram classes (org.HdrHistogram.*): 114.78 MiB in 1,284,248 objects.

| # | Class | Instances | Bytes |
|---|---|---|---|
| 1 | `[B` | 188,503 | 42.10 MiB |
| 2 | `[J` | 186,270 | 40.70 MiB |
| 3 | `org.HdrHistogram.PackedHistogram` | 183,453 | 32.19 MiB |
| 4 | `org.HdrHistogram.HistogramIterationValue` | 366,924 | 30.79 MiB |
| 5 | `org.HdrHistogram.PercentileIterator` | 183,462 | 21.00 MiB |
| 6 | `org.HdrHistogram.RecordedValuesIterator` | 183,462 | 18.20 MiB |
| 7 | `io.argos.server.alert.metric.WindowProvider$Slot` | 183,453 | 12.60 MiB |
| 8 | `org.HdrHistogram.packedarray.PackedArrayContext` | 183,453 | 7.00 MiB |
| 9 | `[Lio.argos.server.alert.metric.WindowProvider$Slot;` | 2,013 | 6.94 MiB |
| 10 | `org.HdrHistogram.packedarray.PackedLongArray` | 183,453 | 5.60 MiB |
| 11 | `java.lang.String` | 179,889 | 4.12 MiB |
| 12 | `Ljdk.internal.vm.FillerArray;` | 2,873 | 3.75 MiB |
| 13 | `[Ljava.lang.Object;` | 36,645 | 3.38 MiB |
| 14 | `java.util.concurrent.ConcurrentHashMap$Node` | 99,002 | 3.02 MiB |
| 15 | `java.lang.Class` | 22,266 | 2.52 MiB |

JFR `jdk.VirtualThreadPinned` (threshold 20 ms): 0 events, longest 0 ms, total 0 ms; top frames none.

## Attack scenarios under load (T-SEC-11..15, N-10d)

Run after the measured window while the steady load continued; 11 s in total. Apps `attack-quiet`, `attack-attacker`, `attack-instances` (outside the D-13 count). Sizes of profile `smoke` (reduced, 18 §8 sizes at full).

| Attack | Sent | Outcome | Expected |
|---|---|---|---|
| gzip-bomb | 3 x 1 MB (inflates to 1 GiB) | RESOURCE_EXHAUSTED message_size 3 | RESOURCE_EXHAUSTED message_size |
| batch-overflow | 3 x 5,001 records | RESOURCE_EXHAUSTED batch_items 3 | RESOURCE_EXHAUSTED batch_items |
| dict-flood | 10,000 random services in 2 batches | ACK_PARTIAL_DROP 2, 5,907 items dropped | accepted, quota-trimmed (ACK_OK / ACK_PARTIAL_DROP) |
| instance-flood | 200 Hellos with new instance names | 100 accepted, 100 refused (INSTANCE_LIMIT/RESOURCE_EXHAUSTED 100) | 100 accepted, the rest INSTANCE_LIMIT |

| Item | Value |
|---|---|
| Quiet instance (another app) | 52 transactions, p50 997 ms, max 2058 ms, 0 not visible, 0 calls not ACK_OK |
| Steady load ingest lag during the attacks | p50 1.20 s, p99 2.14 s, 331 observations |
| argos_collector_rejected_total increase by reason | batch_items 3, instance_limit 100, message_size 3 |
| argos_collector_dropped_total increase by reason | quota 5,907 |
| Server resident set | before 1.86 GiB, max 1.89 GiB |

The unauthenticated Hello flood (T-SEC-15) blocks the loopback address that every fake agent shares, so it runs last, after the load and the other phases (section below).

## Dictionary under dict-flood (C-238 (14))

| Item | Value |
|---|---|
| Random services sent | 10,000 |
| New rows in `argos.dictionary` | 4,100 |
| `pg_total_relation_size('argos.dictionary')` | 9.48 MiB -> 9.66 MiB (+176.00 KiB, 43 bytes per new row) |
| Server heap, Caffeine cache classes (class histogram) | 506.39 KiB -> 640.23 KiB (+133.84 KiB) |
| Server live heap after a full GC | 226.22 MiB -> 243.09 MiB (+16.87 MiB) |
| argos_dictionary_overflow_total increase | 0 |

The live heap difference covers everything the steady load holds between the two histograms, not only the dictionary; the Caffeine classes are the resolver's known cache (and other Caffeine caches of the server). The nightly pre-clean-up (`DictionaryCleanupJob`, 03:30, `last_seen` older than 90 days, 10,000 rows per statement) holds one batch of keys at a time; its delete cost for 100,000 rows is the `[wo29-dict-cleanup]` line of `DictionaryCleanupJobIT` (T-SV-014f). The rows of this run are fresh and are not deleted by it.

## Session cap memory (C-157 (1))

1,000 idle sessions planned, 1,000 accepted in 5,052 ms (refused: none), 1,000 still open at the measurement; `argos_sessions_active` 0 -> 1,000. One TLS connection per session, 100 sessions per app (the hourly cap of new instances), a heartbeat every 10-20 s. Measured after the load stopped and drained.

| Item | Before | With the idle sessions | Difference | Per session | Extrapolated to 5,000 |
|---|---|---|---|---|---|
| Heap used after a full GC (raw) | 267.46 MiB | 295.84 MiB | +28.38 MiB | 29.06 KiB | 141.90 MiB |
| Heap drift of the idle server over the same interval (baseline 15 s before) |  |  | +14.16 MiB |  |  |
| Heap used after a full GC, drift taken off | 267.46 MiB | 295.84 MiB | +14.22 MiB | 14.56 KiB | 71.11 MiB |
| NMT committed (heap reserved up front: -Xms = -Xmx) | 2.40 GiB | 2.43 GiB | +24.48 MiB | 25.07 KiB | 122.41 MiB |
| Resident set | 1.91 GiB | 1.93 GiB | +15.60 MiB | 15.97 KiB | 77.99 MiB |
| Live threads | 98 | 101 | 3 |  |  |

After closing them and 5 s: heap +26.67 MiB, resident set +15.73 MiB against the start of the phase.

The heap figure is the server's own state per session (registry, session handler, gRPC stream and Netty channel objects, the tracker's epoch memory, instance caches); the resident set adds Netty's pooled direct buffers and the TLS engine. Proposal (C-157 (1)): keep the 5,000 constant if the extrapolated heap stays well below 10 % of the 4 GiB heap of 12 §11 on the target machine; this laptop's value is a reference (INVALID).

## Unauthenticated Hello flood (T-SEC-15)

| Item | Value |
|---|---|
| Hellos | 100 with unknown tokens, back to back |
| Outcome | 20 TOKEN_INVALID, 80 blocked (auth_blocked), argos_collector_auth_blocked_total +80, in 76 ms |
| argos_collector_rejected_total increase by reason | auth_blocked 80, token_unknown 20 |
| Server resident set | 1.93 GiB -> 1.93 GiB (+0 B) |

Run last: the block covers the loopback address of every fake agent for 10 minutes. A run with a separate load generator (18 §2.1, follow-up) can send it during the load from another address.

## HdrHistogram and pinned virtual threads (12 open items 3 and 4)

**12 open item 3 (HdrHistogram memory, assumption: a few hundred MB).** End of run: 114.78 MiB in HdrHistogram objects for 5 apps x 100 services (235.06 KiB per (app, service) window, every window of the run assumed alive). Extrapolated to 12 §11's 30 apps x 500 services: 3.36 GiB. Proposal: the extrapolation exceeds 10 % of the 4 GiB heap of 12 §11; the cap of 500 services per app (or the window's slot layout) needs a PM decision.

**12 open item 4 (virtual thread pinning in Tomcat REST/WS).** JFR `jdk.VirtualThreadPinned` (20 ms threshold) over the run with 10 dashboard users: 0 events, longest 0 ms. Proposal: the assumption "none (JDK 21+)" holds for this load; confirm on the target machine.

## Memory budget and ratio proposals (12 §11, 17 §7.3, 17 open item 9)

| Component | Budget of this run | Peak | Use | Proposal |
|---|---|---|---|---|
| argos-server (heap + 1 GiB off-heap, 12 §11) | 3.00 GiB | 1.95 GiB resident | 65 % | within budget (65 %): keep the ratio |
| ClickHouse container | 4.00 GiB | 626.10 MiB (MemoryTracking 434.61 MiB) | 15 % | within budget (15 %): keep the ratio |
| PostgreSQL container | 1.00 GiB | 74.51 MiB | 7 % | within budget (7 %): keep the ratio |

Server off-heap (NMT committed outside the Java heap, end of run): 423.63 MiB against the 1 GiB of 12 §11 (direct buffer peak 47.05 MiB, threads peak 102). Heap per idle session 14.56 KiB, 71.11 MiB for the 5,000-session cap (C-157 (1)).

**17 open item 9 / 17 §7.3 ratios (ClickHouse 0.47, server heap 0.25, PostgreSQL shared_buffers 0.03 of RAM).** At 16 GiB they give ClickHouse 7.5 GiB, server 4 + 1 GiB, PostgreSQL 512 MB x 3; at 32 GiB 15 GiB, 8 + 1 GiB and 1 GiB x 3. Proposal rule: a component whose peak at full scale stays within 85 % of its budget keeps its ratio; one above it takes the difference from ClickHouse first (17 §7.3 check). This run's peaks are from profile `smoke` on a shared machine with reduced budgets: INVALID, no ratio change is proposed from them. The full run on 13 §7.1 hardware (16 GiB) and a 32 GiB run are needed (a human run on that hardware).

## Files of the run

- `build/reports/server-load/samples.tsv`
- `build/reports/server-load/class-histogram.txt`
- `build/reports/server-load/native-memory.txt`
- `build/reports/server-load/work/server/server-stdout.log`
- `build/reports/server-load/work/server/gc.log`
- `build/reports/server-load/work/server-pinned.jfr`

