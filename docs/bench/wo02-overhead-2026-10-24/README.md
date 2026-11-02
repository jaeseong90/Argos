# WO-02 agent overhead (P-2): raw evidence (2026-10-24)

Raw results behind [`docs/poc/agent-overhead.md`](../../poc/agent-overhead.md) (P-2, WO-02, 01 §7.3
item 11). Every number comes from one shared developer laptop on which the load generator, the app
and unrelated work ran at the same time. They are **reference values only**. No set here is VALID,
and Gate 0 overhead (03 §4) is **not** decided by this data; it is decided by the human re-run on
dedicated hardware described in section 9 of the PoC document.

## Run conditions

| Item | Value |
|---|---|
| Date | 2026-10-24 10:43Z to 21:14Z (19:43 to 06:14 local time, UTC+9). All times below are UTC |
| Code | commit `4a43e77` plus the uncommitted working tree of WO-00 to WO-04 (`set.json` -> `git`) |
| Agent | `argos-agent-0.0.0-SNAPSHOT.jar`, 4,074,064 bytes, SHA-256 `f108eaee6afe10033c396835bf106bec9dac8a34cae5b054209b0b5673ac3cfd` (modules servlet, spring-webmvc, jdbc, executor, httpclient-jdk; no transport). Settings `-Dargos.exporter=noop -Dargos.log.dir=<run>/agent-logs` |
| App | `shop-boot3.jar` SHA-256 `e15278a7490fc2dfc3b9aea2a1d3705c7ccca00509ae7493b98a243c6edbda75`; `shop-heavy.jar` `1d2ab2581e31039698ded900748c0c19723b605cfd6b79ee973cd70f7245fcf6`; `ext-stub.jar` `27fb16bcc3a7db7be7c3bef0966e69e68561e6261786a2017e7f2e0f1476c489` |
| Load | `sample-apps/load/bench.js` SHA-256 `cc26dbd1868021f700e860045e44b3f76583cde92dbb127a52dfb982c6640919`, k6 v2.3.0 windows/amd64 native binary (same machine); container pilot: `grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34` |
| Harness | `test/agent/overhead` (`overhead-bench.jar` SHA-256 `90cfbe964d755a542c175c85f72f3ddab4f2e9acaff6ef69ba25b73757fd6c8f`; the jar rebuilt on 2026-10-25 from the repository has the same hash) |
| Machine | AMD Ryzen 5 5600H (6 cores, 12 threads), 15.3 GiB RAM (available memory 0.5 to 5.9 GB before a run; minimum during a window 0.46 to 3.2 GB in the kept runs of `main-3`/`main-9`, down to 15 MB in `main-1`), Windows 11 Home 10.0.26200, AC power, power plan GUID `48684d4a-8524-4093-8a63-ea7132b79c1c` |
| App JDK | Microsoft Build of OpenJDK 21.0.10+7-LTS; harness JDK OpenJDK 21+35 |
| JVM options | load sets `-Xms1g -Xmx1g -XX:+UseG1GC -XX:+AlwaysPreTouch -Xlog:gc:file=gc.log -XX:NativeMemoryTracking=summary`; startup sets `-Xms2g -Xmx2g -XX:+UseG1GC` |
| Concurrent work | WO-03 (ClickHouse load, reviews, Gradle builds), other WO-02 lanes, Windows Defender scans, the WSL 2 VM and interactive programs. Per run: machine CPU every second (`machine.json`), the 5-second load before the start (`run.json` -> `before`) and the "background" CPU (machine minus app minus k6) |

## Sets

Classification: VALID = complete set whose harness verdict is PASS or FAIL under valid conditions;
INVALID = complete (or pilot) set the harness or the protocol rejects; ABORTED = stopped before the
end. The verdict column quotes `result.json`; for sets that stopped early the result was recomputed
on 2026-10-25 with `overhead-bench.jar report --out <set>` (no new measurement).

| Directory | Kind | Window (UTC) | Class | Harness verdict and reason |
|---|---|---|---|---|
| `pilot1/` | load, process, 1 round, warm-up 20 s, window 40 s | 10:43-10:46 | INVALID (pilot) | INVALID: off-run CPU CV NaN (one off run) |
| `pilot2c/` | load, container `--cpus 2 --memory 2g`, 1 round, 20 s / 70 s | 10:47-10:54 | INVALID (pilot) | INVALID: off CV NaN; run 1 (off) k6 gate INVALID (load machine 96%); run 2 (on) k6 gate FAIL (dropped 1.2%) |
| `pilot3s/` | startup, 1 start per cell | 10:58-11:04 | INVALID (pilot) | FAIL (+5.4 s and others); n = 1 per cell and 23-94% CPU before the starts, so not the C-49 median of 10 |
| `main-1/` | load, process | 11:05-11:22 | ABORTED | stopped by hand after run 1; recomputed INVALID: off CV NaN; run 1 (off) k6 gate FAIL (unexpected 0.0065%) |
| `main-2/` | load, process | 11:29-11:54 | ABORTED | queue exit 1 while waiting; recomputed INVALID: off CV NaN; run 1 (off) k6 gate INVALID (machine 93%; 15,673 refused connections and 80 timeouts) |
| `startup-1/` | startup | 11:54-11:55 | ABORTED | queue exit 1 before the first start (machine 100%); only `set.json` |
| (no directory) `container-1` | load, container | 11:55-12:04 | ABORTED | no output; the queue was restarted by hand |
| `main-3/` | load, process | 12:04-14:55 | INVALID | INVALID: off-run CPU CV 26.17% > 2% (run 1 off kept at 50.8% background after 4 discarded attempts) |
| `startup-2/` | startup | 14:55-15:06 | ABORTED (partial) | harness error at start 37 (`AccessDeniedException` moving a run directory, Windows lock); recomputed FAIL: shop-boot3 cds=off port +2088 ms > 2,000 ms. shop-boot3 default CDS complete (10 pairs), CDS off 8 pairs, shop-heavy not started |
| `main-4/` | load, process | 15:12-15:41 | ABORTED | stopped by hand (background floor 16-17% > limit 15%); 3 warm-up discards; recomputed INVALID (no kept run) |
| `main-5/` | load, process | 15:40-16:45 | ABORTED | stopped by hand (attempts started in short gaps); 2 discards, one with a full window (background 30%) |
| `main-6/` | load, process | 16:45-16:56 | ABORTED | stopped by hand (harness change: stream the app output); 1 warm-up discard |
| `main-7/` | load, process | 16:58-17:48 | ABORTED | harness error (`NoSuchFileException`: run directory removed by another job's empty-directory clean-up while waiting, disk full); run 1 off complete; recomputed INVALID: off CV NaN |
| `main-8/` | load, process | 17:53-18:28 | ABORTED | stopped by hand (background floor 17-23%; limit raised 22% -> 25%); 2 discards, one with a full window (background 23%) |
| `main-9/` | load, process | 18:28-21:03 | INVALID | INVALID: off-run CPU CV 4.39% > 2%; run 6 (on) k6 gate FAIL (2 of 600,000 requests failed on the client side with WSAEADDRINUSE); peak relative CPU +17.68% > 8% |
| `startup-3/` | startup | 21:03-21:14 | ABORTED (partial) | stopped by the PM at 21:14:11Z (user decision); recomputed FAIL: shop-boot3 cds=off port +2009 ms > 2,000 ms (6 pairs). Default CDS complete (10 pairs), shop-heavy not started |

Planned but never started: `vt` (item 10), `diag` (startup decomposition), `otel` (item 11 reference
point). Queue logs: [`queue-log.txt`](queue-log.txt) (first queue), [`queue2-log.txt`](queue2-log.txt)
(second queue, with the reasons for each manual stop).

## Files

Per set: `set.json` (parameters, environment, input hashes), `result.json` and `report.md` (verdict;
see the table above for recomputed ones), `console-log.txt` (the harness console: quiet-period waits,
discards, per-run summary lines).

Per load run, `runs/<NN>-<variant>/` (kept) or `runs/<NN>-<variant>-discarded-<k>/` (discarded attempt):

| File | Content |
|---|---|
| `run.json` / `attempt.json` | Everything the verdict uses: load before the start, spawn-to-port, warm-up and window k6 results (requests, dropped, unexpected, percentiles per route), app CPU seconds over the window and its 1-second timeline, k6 CPU, machine and background CPU, C-46 gate, agent thread CPU, memory (agent heap, top agent classes, NMT committed per category, metaspace, classes, threads), premain. `attempt.json` adds the discard reason |
| `k6.json`, `k6-warmup.json` | k6 summary exports of the window and of the warm-up |
| `k6-log.txt`, `k6-warmup-log.txt` | k6 console summary (renamed from `.log`; the repository ignores `*.log`) |
| `machine.json` | Machine CPU %, available memory and app CPU seconds, every second over the window |
| `nmt-summary.txt`, `metaspace.txt` | `jcmd VM.native_memory summary` and `jcmd VM.metaspace` after the window |
| `threads-argos-start.txt`, `threads-argos-end.txt` | Extract of `jcmd Thread.print` before and after the window: thread count and the header line (with `cpu=`) of each `argos-*` thread |
| `class-histogram-argos.txt` | Extract of `jcmd GC.class_histogram` after `GC.run` x2: header, every `io.argos.agent.*` row (N-02) and the total |
| `app-log-levels.txt` | Count of WARN/ERROR and exception lines in the app log (the 5% intended errors) |
| `command.txt` | App command line |
| `k6-cgroup-samples.txt`, `k6-warmup-cgroup-samples.txt` | Container pilot only: cgroup `cpu.stat` samples of the app container |
| `k6-log-extract.txt` | `main-2/runs/01-off` only: the k6 console log was 3.7 MB; the 15,754 warning lines are counted by error text, the summary lines are kept |

Per startup run, `runs/<NNN>-<app>-<cds>-<variant>/`: `run.json` (spawn to port, spawn to ready,
Spring `Started ... in`, premain, app CPU to ready, load before), `attempt.json` (discarded or
unfinished start), `command.txt`.

## Changes made when copying

Only these normalizations were applied; no value was changed:

- The scratch directory of the work machine, which contains the local user name, was replaced by
  `<scratch>` and the user's home directory by `<home>` (547 occurrences in paths of `set.json`,
  `command.txt`, k6 logs and reports).
- CRLF converted to LF; `.log` files renamed to `-log.txt`.
- Thread dumps, class histograms (about 0.3 and 0.8 MB per run) and the 3.7 MB k6 log of
  `main-2/runs/01-off` were reduced to the extracts described above.

Not copied: `gc.log`, app log excerpts and startup `stdout.log`/`stderr.log` (app output),
`agent-logs/`, full thread dumps and class histograms, `processes-before.txt`/`processes-after.txt`
(names of the busiest processes, which include the user's personal programs; the interference they
show is quantified by `machine.json` and the background CPU), `bin/` (jar copies; hashes in
`set.json`), the `calib/` parser calibration run (no measurement), and the queue and analysis
scripts. No JFR recording was made.

Size: about 2.9 MB in 581 files (including this README).

## Source

Copied on 2026-10-25 from the scratch directory of the WO-02 session,
`%TEMP%\claude\C--workspace-Argos\2b93fd47-468f-4262-bae3-62f54b0ac448\scratchpad\wo02perf\`:
`pilot1\`, `pilot2c\`, `pilot3s\` -> the directories of the same name; `sets\<set>\` -> `<set>/`;
`sets\<set>.log` -> `<set>/console-log.txt`; `sets\queue.log`, `sets\queue2.log` -> `queue-log.txt`,
`queue2-log.txt`. The scratch directory is deleted after WO-02; this copy is the only raw data.
