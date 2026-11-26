# test/chaos

Chaos tests of [docs/18 §11.1](../../docs/18-test-design.md) (DSN-18-08, WO-52): shop-boot3 with
the agent <-> **toxiproxy** <-> the real server <-> ClickHouse and PostgreSQL, under a fixed-rate
load, one fault per scenario. Development-only, never shipped.

| | |
|---|---|
| Gradle project | `:test:chaos` |
| Task | `chaosTest` (JUnit suite `src/chaosTest`, not part of `check` or `build`) |
| Unit tests | `src/test` (no Docker, part of `check`): T-CH-020a..o (proposed) |
| Owning work order | WO-52 (chaos and 72-hour soak), docs/04 |
| Scenarios here | T-CH-001..009 (T-CH-003 also with tc netem on Linux); T-NFR-05 aggregated; T-CH-010 and T-CH-011 listed SKIPPED (not WO-52's acceptance) |
| Runs on | self-hosted `argos-matrix` (18 §2.3), before releases and for pull requests that change the collection path (manual); `.github/workflows/chaos.yml` |
| Operator view | what an operator sees in each scenario and what to do: [docs/ops/runbook.md](../../docs/ops/runbook.md) section 5 |

## Running

```bash
# Short profile (default): every phase in minutes, to prove the runner and the verdicts. INVALID.
bash test/lock/argos-lock.sh run server-it --purpose "chaos" -- \
  ./gradlew chaosTest -Pbench.machine-profile=dev-laptop [-Pchaos.scenarios=T-CH-001,T-CH-009] [-Pchaos.rate=100]

# 18 §11.1 as designed (server down 1 h, ClickHouse down 30 min) on the dedicated runner.
./gradlew chaosTest -Pbench.machine-profile=argos-matrix -Pchaos.profile=full -Pchaos.publish=true
```

| Property | Default | |
|---|---|---|
| `-Pbench.machine-profile` | (required) | Without it the root guard (`argos.test-layers`) fails before anything is built; the task checks it again |
| `-Pchaos.profile` | `short` | `short` or `full` (table below) |
| `-Pchaos.scenarios` | every scenario | IDs separated by commas or blanks |
| `-Pchaos.rate` | 100 (short), 200 (full) | requests per second of the load generator |
| `-Pchaos.publish` | `false` | `true` copies the report to `docs/bench/chaos-<date>.md` |
| `-Pchaos.netem.image` | none | an image with `tc` (iproute2) pinned by digest: enables the netem variant of T-CH-003 on a Linux Docker Engine host |

Requirements: Docker, JDK 21 (Gradle toolchain), about 6 GiB of free memory (PostgreSQL 1 GiB,
ClickHouse 2 GiB, server heap 1 GiB, shop-boot3 heap 512 MiB, the generator and toxiproxy). The
task builds the server boot jar, the agent jar, shop-boot3 and the load generator first. Run one
heavy job at a time (the `server-it` lock).

| Phase | short | full |
|---|---|---|
| Warm-up before the first scenario | 30 s | 2 min |
| Baseline (noise band) | 60 s | 5 min |
| Fault T-CH-001 (server down) | 3 min | 1 h |
| Fault T-CH-009 (ClickHouse paused) | 3 min | 30 min |
| Fault T-CH-002..008 (T-CH-007: load on the verifying app) | 2 min | 10 min |
| Recovery window | 60 s | 2 min |
| Drain (at most) | 3 min | 10 min |

A short run of all nine scenarios takes about 50 to 70 minutes (T-CH-001..003 and 009 about 22
minutes on the development laptop); a full run about 3.5 hours. T-CH-005 to T-CH-007 start their own
applications (a container of 1 GiB or a second JVM of 512 MiB heap), one at a time.

## The stack

One stack per run, started for the first scenario and shared by the scenario classes in a fixed
order (`@Order`: T-CH-001, 002, 003, then T-CH-004..007, T-CH-009, T-CH-008 last because it resets
PostgreSQL):

1. PostgreSQL 16 and ClickHouse in Testcontainers (`ServerTestEnvironment`, pinned images).
2. The server boot jar as a child JVM with its TLS collector on loopback (`LoadServerJvm`, WO-29),
   with the run's own collector certificate (generated at run time in a private temporary directory
   that is deleted with the stack: names `localhost`, `127.0.0.1`, `argos-chaos-toxiproxy`,
   `argos-chaos-collector.test`; never `argos-chaos-mismatch.test`) and the failure injection hook
   `argos.testing.ingest-fault-file` = `work/ingest-fault` (absent: no fault; development builds
   only, 18 §19).
3. toxiproxy (`ghcr.io/shopify/toxiproxy:2.12.0`, pinned by digest, MIT, container
   `argos-chaos-toxiproxy-<run>`, label `io.argos.test=chaos`, 128 MiB) with one proxy
   `collector`. Docker Desktop: bridge network, ports published on 127.0.0.1, upstream
   `host.docker.internal:<collector port>`, and toxiproxy also joins the run's Docker network
   `argos-chaos-<run>` as `argos-chaos-toxiproxy` (the T-CH-005 container dials it there). Linux
   Docker Engine: host network on 127.0.0.1; with `-Pchaos.netem.image` or when T-CH-005 is part of
   the run the bridge network, and the collector then listens on every interface so that the
   container reaches it. The runner talks to toxiproxy's HTTP API with the JDK's HTTP
   client (`ToxiproxyClient`), no client library.
4. One application (`argos-chaos`/`chaos`) and its agent token (`AgentTokens`); T-CH-005..007
   register their own applications (`argos-chaos-logfull`, `argos-chaos-dns`, `argos-chaos-tls`,
   `argos-chaos-tls-skip`) so that their rows and instances never mix with the main app's. The
   probes read the scenario's application while it runs.
5. shop-boot3 with the agent on JDK 21 (`AppLauncher`): `server.address` = toxiproxy's port,
   TLS with the collector CA, the token in an owner-only file, the self-diagnostics snapshot
   (`argos.test.selfdiag-file`, C-95). Every transaction is an xlog row (sampling keeps or drops
   profiles only). ext-stub runs in process for `GET /api/pay/{id}`.
6. A viewer signed in to the API (`/api/v1/apps/{id}/instances`, `/collection-health`).

Each scenario runs the demo load generator (`sample-apps/load`, mix `bench` of 18 §7.2, fixed
rate, one progress line per 10 s) over baseline, fault and recovery. 18 §11.1 names k6 at 200 TPS
and a Compose stack with `docker stop argos-server`; the runner uses the project's own generator
(same open-model schedule, p99 per interval, nothing to install on the runner) and stops the server
JVM (SIGTERM on POSIX, so the server closes the sessions and writes its `stop` event; on Windows the
JVM is terminated like a crash). Both are deviations the report states.

## Verdicts

Common rows of every scenario (18 §11.1):

| Row | Kind | Rule |
|---|---|---|
| App p99 within the noise band | measurement | The baseline's interval p99 values (first interval left out) give `upper = max + max(max - min, 10 % of the median)`; the median of the fault window's interval p99 values must stay at or below it |
| Recovery | measurement | From the fault's removal until the instance is `UP` or `WARN` with a heartbeat after the removal and the first xlog row of a transaction after the removal is stored: at most 60 s |
| Collection resumed | invariant | Both signals of the recovery seen by the end of the drain |
| Agent log ERROR lines | invariant | 0 in the scenario's time span (`logs/argos-agent*.log`) |
| Retransformation failure lines | info | Count of the agent's `retransformation of N already loaded class(es) failed` WARN (ADR-0010 C-257 (2)) |
| Drop accounting (D-13) | invariant | xlog rows added + `argos_agent_dropped_total{queue="transaction"}` + `argos_agent_server_dropped_items_total{kind="transactions"}` = answered requests (a range when requests had no answer) |
| Duplicates (D-13) | invariant | `count()` = `uniqExact(tx_id)` of the application's xlog rows |
| Shown in collection-health | invariant | `agent_drops` > 0 when the agent dropped, `server_drops` > 0 when the server dropped, and the expected outage (`server_down` for T-CH-001) overlapping the fault |

Scenario rows:

| ID | Fault | Extra rows |
|---|---|---|
| T-CH-001 | the server stopped and started again on the same WAL and collector port | reconnect attempts (toxiproxy `Accepted client` times) never more than 36 s apart (back-off cap 30 s, ±20 % jitter); at least one new session; drops reference `rate × (outage − 120 s)`; `server_down` outage (SKIPPED on Windows: a terminated JVM writes no stop event) |
| T-CH-002 | toxiproxy `latency` 500 ms each way | acknowledged batches per second ≤ 4 in flight / 1 s round trip + 10 %; agent heap (`io.argos.agent.` classes, `GC.class_histogram`) grows ≤ `buffer.max-bytes` (32 MB) |
| T-CH-003 | toxiproxy `limit_data` 256 KiB up and `reset_peer` 3 s down at toxicity 0.1, plus `timeout` both ways for 1 s of every 10 s | resends and reconnects (reference); duplicates 0 is the point. The `tc netem loss 10%` variant needs a Linux Docker Engine host and `-Pchaos.netem.image`, else SKIPPED with the reason |
| T-CH-004 | the server's test hook `argos.testing.ingest-fault-file` (`IngestFaultInjector`, 18 §19): `UNAVAILABLE`, `INTERNAL`, `RESOURCE_EXHAUSTED` (trailer `argos-retry-after-ms` 1 s) and `QUOTA:0.5` (half of each transaction batch through the real quota path: `ACK_PARTIAL_DROP`, server drops reason `quota`), a quarter of the fault each | refused batches resent (`argos_agent_batch_retries_total` > 0); agent drops and server drops both counted by the agent; `collection-health` shows `agent_drops` > 0 and `server_drops_quota` > 0 as separate series ("드롭(쿼터) 구분 표시") |
| T-CH-005 | its own shop-boot3 in a container (`eclipse-temurin:21-jdk` pinned, 1 GiB, the run's Docker network, the collector as `argos-chaos-toxiproxy:26000`) with `log.dir` on a 1 MB tmpfs (`AppLauncher.containerTmpfs`): `dd` fills the tmpfs, and the collector connection is cut for the first half (toxiproxy proxy disabled) so that the agent has WARNs to log | agent records reach the container's stderr after the fill (fallback, not silent); at most 10 per minute (the sink's one-minute windows); every request answered and the container running ("앱 영향 0", with the common p99 row) |
| T-CH-006 | its own shop-boot3 resolving names from its own hosts file (`-Djdk.net.hosts.file`, DNS cache 1 s; no administrator rights), agent dialing `argos-chaos-collector.test`: started with the name absent, then the name added; the fault removes it again and cuts the connection for 2 s | premain does not wait: app ready, a connect WARN and no session while the name is absent, ready time ≤ the main app's + 10 s (measurement); the agent connects on its own after the name is added (≤ 90 s); connect WARN during the fault ≥ 1 and at most one per minute; whether the WARN names `UnknownHostException` (info) |
| T-CH-007 | standalone (no baseline or recovery: the fault exists from the start): two own apps dial `argos-chaos-mismatch.test` (hosts file to 127.0.0.1), a name the collector certificate does not carry | verifying agent: a WARN naming the TLS failure, no session, no instance on the server, every request answered under load for the fault length, ERROR 0; `insecure-skip-verify` agent: connects, reconnects twice after the proxy is cut, `TLS verification disabled` WARN ≥ sessions established (16 §3.4 "매 접속") |
| T-CH-008 | last: the server stopped, the PostgreSQL database dropped and created again with `deploy/postgres/roles.sql` (ClickHouse and WAL kept), the server restarted (migrations), the app row (same id) and the agent's token (same text) put back; toxiproxy keeps the agent away until then | a new session and an instance row again; the new instance id differs from the earlier one; the XLog API (`GET /api/v1/apps/{id}/xlog`, what the UI draws) leaves the earlier rows' instance without a dictionary name (unknown instance, 17 §12) |
| T-CH-009 | `docker pause` of ClickHouse | `argos_wal_bytes` (every 5 s) < 10 GiB; WAL growth (reference); re-ingest is the drop accounting after the drain |
| T-CH-010, T-CH-011 | not run | SKIPPED with the reason "not in WO-52" (T-CH-011's emergency retention is proven by WO-28 `EmergencyRetentionIT`) |

T-NFR-05 (N-05 "앱 영향 0") is one report row aggregated from the p99, ERROR and accounting rows of
T-CH-001..003.

Invariant rows are PASS or FAIL in every run. Measurement rows are PASS or FAIL only with
`-Pchaos.profile=full` on a dedicated runner (`-Pbench.machine-profile=argos-matrix`), INVALID
otherwise (a reference value). A scenario is FAIL when one row is FAIL, else INVALID when one row is
INVALID, else PASS; the scenario's JUnit test fails only on FAIL.

## Output

`test/chaos/build/reports/chaos/<run>/`: `report.md`, `results.json` (per scenario and row: verdict,
value, reason), `work/` with the server work directories (`server-<n>/server-stdout.log`, GC log),
the app's work directory (`app/logs/argos-agent.log`, `app/out/selfdiag.json`, stdout and stderr),
`toxiproxy.log` (Docker timestamps) and one directory per scenario with the generator's log and
summary. `-Pchaos.publish=true` copies `report.md` to `docs/bench/chaos-<date>.md`; a run that is not
valid carries an INVALID banner. The release notes keep saying "chaos not run" until a valid run on
the dedicated runner exists (ADR-0010 C-246 (2)).

## Adding a scenario

Implement `Fault` (`inject`, optional `during`, `remove`, optional `expectedOutage`,
`errorExpected`, `checks`; `prepare`/`cleanup` for a scenario's own application started with
`ChaosStack.startApp`; `StandaloneFault` when the fault exists from the application's start) in
`src/main`, and add one class in `src/chaosTest` with `@Order`,
`@ExtendWith(ChaosRunExtension.class)`, `req:` tags, a display name starting with the ID, and
`ScenarioAssertions.assertScenario(session.run(id, title, fault))`. `ScenarioDriver` runs the phases
and the common rows; `ChaosStack` gives the probes (xlog counts, agent counters, agent log, WAL bytes,
instances, collection-health, server stop and start, ClickHouse pause).

See [test/README.md](../README.md) for every test layer and how to run it.
