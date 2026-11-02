# test/storage/bench

Runbook of the storage PoC benchmark (WO-03, P-4; docs/13 section 15, docs/18 section 9) on one server:
bulk load, Q1..Q20, replay with concurrent queries (Q17). The results go to
[docs/poc/clickhouse-benchmark.md](../../../docs/poc/clickhouse-benchmark.md); the manual Gate 0 checks
T-ST-001..020 run this procedure on the target host (`argos-storage`: 8 cores, 16 GB, NVMe).

`bench-server.sh` starts the bundled ClickHouse (`deploy/clickhouse/image.properties`) with the repository
configuration on a named volume, so that the generator and the query runner can use the same server at the same
time. It generates the three account passwords into `test/storage/bench/build/bench-secrets.env` (mode 600,
ignored by git) and passes only their SHA-256 hashes to the container.

## Procedure

```sh
# 0. Server: 8 GiB container on the target host (server 7.2 GiB, docs/13 section 7.1); 5 GiB on a 16 GB laptop.
ARGOS_BENCH_MEMORY_GIB=8 test/storage/bench/bench-server.sh start
set -a; . test/storage/bench/build/bench-secrets.env; set +a
URL=http://127.0.0.1:18123/

# 1. Bulk load (full scale: xlog 1.3 billion rows over 30 days; --profile=local is 1/20, ci 1/100).
./gradlew :test:storage:generator:storageBench -Pbench.machine-profile=argos-storage \
  -Pstorage.profile=full -Pstorage.target=url:$URL -Pstorage.apply-schema=true -PstorageXmx=4g

# 2. Q1..Q16, Q18..Q20 (cold = server restart + page cache drop before each cold run, then 3 warm runs).
#    Q18 compares the two deletion forms on two copies of days d - 1 and d (4 repetitions, alternating order;
#    two 1/100 runs with 2 repetitions each ranked the forms in opposite order);
#    Q19 includes the real retention change (ttl_change_1); Q20 compares V001 (base, ZSTD(1), ADR-0007 C-54)
#    with the pre-ADR-0007 LZ4 definition (lz4) and the other variants, 3 repetitions.
./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url $URL \
  --procedures Q05/bloom,Q11/cost,Q14,Q15,Q16,Q18,Q19,Q20 --bloom-days 3 --q18-wait-s 600 --q18-repeats 4 \
  --q20-repeats 3" \
  -Pqueries.cold="command:$PWD/test/storage/bench/bench-server.sh restart-dropcache"

# 2b. Decision checks (docs/poc/clickhouse-benchmark.md 13, items 9, 11, 12), each also part of step 2:
#   codecs (ADR-0007 C-54): Q20 variants base (V001), lz4 (V001 before ADR-0007), lz4_nocodec, lz4cols_zstd,
#   nocodec, proposed (WO-03 proposal = base + profile_step.tx_id Delta), 3 repetitions
./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url $URL --queries none --procedures Q20 \
  --q20-repeats 3"
#   A-6 fallback: Q05 restored_skewed (0 rows expected), fallback_2d (1 row, <= 500 ms), fallback_coverage
#   (transactions of the last day that +-2 s and the 2 day fallback miss); NOT_APPLICABLE without a skewed clock
./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url $URL --queries Q05 --procedures none"
#   D-14 determinism: report table "D-14 rebuild determinism" of Q14 (state bytes and values, 1m and 1h,
#   rebuild single-threaded vs 8 threads) on the loaded data and on the synthetic sample; on the loaded data
#   also Q14/d14_rebuild: the rollups of the last full day rebuilt twice with the C-59 procedure (staging +
#   REPLACE PARTITION), stored quantilesDD states compared byte for byte (criterion: 0 mismatches). Q14 is judged
#   on the C-53 criteria (1m group p95 value error <= 2 % for p50/p95, p99 <= 5 % at 1h and above, 1m p99 rank
#   error <= 0.5 pp)
./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url $URL --queries none --procedures Q14 \
  --q14-source dataset --q14-days 3"
./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url $URL --queries none --procedures Q14 \
  --q14-source synthetic --q14-days 3"

# 3a. Replay 500 -> 2,000 -> 5,000 TPS (1 hour each, 13 section 15 distributions, 100 ms simulation ticks for
#     the ingest lag) and, during the 5,000 TPS hour, Q17 (10 users, 30 min). Expected load (generator,
#     rows per simulated second): 500 TPS: xlog 500, profile_step ~850, statistics ~145, metric 1,000;
#     2,000 TPS: 2,000 / ~3,400 / ~200 / 1,000; 5,000 TPS: 5,000 / ~8,500 / ~260 / 1,000. That is about 13.8k
#     rows/s of xlog + profile_step + statistics, 21 % of N-06 (65k): Q17 at a reduced load.
#     Q17 runs with a live window (--q17-window live, the default when --q17-ingestion is given): every
#     iteration of a user ends its query windows at the server's current minute, so the users read the parts
#     the replay is writing; Q7 is the 1d_live form (last 2 minutes from xlog, 13 section 6.1).
./gradlew :test:storage:generator:storageGenerate -Pstorage.mode=replay -Pstorage.target=url:$URL \
  -Pstorage.tps-steps=500,2000,5000 -Pstorage.step-duration=PT1H -Pstorage.tick-ms=100 &
sleep 7500   # into the 5,000 TPS step
./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url $URL --queries none --procedures Q17 \
  --q17-duration-s 1800 --q17-ingestion replay_5000_TPS"
wait

# 3b. Q17 at the N-06 load (Gate 0 judgement): 5,000 TPS for 1 hour with at least 10,000 statistics rows/s
#     (ADR-0007 C-62: the N-06 bar stays; the load is raised to reach it, statistics still derived from the
#     transactions so that D-12 holds) and 50,000 profile_step rows/s; Q17 from minute 15 to minute 45.
#     500 instances (10 apps x 50), 600 active SQL per instance drawn from 600 statements per app
#     (--sqls=6000; --active-sqls may not exceed sqls / apps). Statistics rows/s measured with the same
#     options at 5,000 TPS (--target=none, 10 simulated minutes, 2026-11-02):
#       instances x active SQL   300 x 200: 5,398   300 x 350: 7,606   500 x 200: 7,381
#                                500 x 400: 10,086  500 x 600: 11,458  500 x 1,000: 12,846
#     Expected load with 500 x 600: xlog 5,000, profile_step 50,000, sql_stat_raw ~11,250 + ext_stat_raw ~200
#     (statistics ~11,450), metric 10,000 (500 instances x 100 series / 5 s): about 76k rows/s in total.
#     The generator prints the achieved rate at the end ("statistics rows/simulated s: ... N-06 bar 10,000:
#     reached"; report.md "Simulation" line). Record it; if it says NOT reached, the run does not judge N-06
#     and stays a Gate 0 open item (C-62).
./gradlew :test:storage:generator:storageGenerate -Pstorage.mode=replay -Pstorage.target=url:$URL \
  -Pstorage.tps-steps=5000 -Pstorage.step-duration=PT1H -Pstorage.instances-per-app=50 \
  -Pstorage.sqls=6000 -Pstorage.active-sqls=600 -Pstorage.profile-step-rate=50000 -Pstorage.tick-ms=100 \
  -PstorageXmx=4g &
sleep 900    # 15 minutes into the hour
./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url $URL --queries none --procedures Q17 \
  --q17-duration-s 1800 --q17-ingestion replay_5000_TPS_N06"
wait

# 4. Consistency after the replay (Q12, Q13 = 0).
./gradlew :test:storage:queries:storageQueries -Pqueries.args="--url $URL --queries Q12,Q13 --procedures none"

# 5. Clean up.
test/storage/bench/bench-server.sh rm-volume
```

Notes:

- `-Pqueries.args` is split on spaces; `-Pqueries.cold` passes the cold strategy as one argument (a `command:`
  strategy contains spaces). `restart-dropcache` drops the page cache of the whole Docker host: run it on a
  dedicated host, or use `-Pqueries.cold=none` (cold = first run) on a shared one.
- The generator's replay report (`build/reports/storage-generator/<run>/report.md`) has the ingest lag per TPS
  step (receive to ack, p50/p99/max); Q17 takes the query latencies, parts, DelayedInserts and memory from the
  server. Record the host CPU load of every phase (both tools sample it).
- The ingest lag is measured on a simulated WAL (no file write, no 10 ms group fsync, no server decode) and
  includes up to one simulation tick of waiting (`tick-ms`; 1,000 ms by default, which overstates the lag by up
  to 1 s). The report gives the load actually generated (`Rows/simulated s` per table, `simulation` in
  `manifest.json`); compare it with the expected values above.
- Column-level sizes (`system.parts_columns`) need an operator account on the host; none of the three Argos
  accounts may read them (SEC-76).
- The writer account may insert into the five raw tables of the default prefix only (users.d/argos.xml, ADR-0007
  C-60): load the bench server without `-Pstorage.table-prefix` / `--prefix`. The Testcontainers targets
  (`-Pstorage.target=docker`, `--volume`) grant the writer the raw tables of the chosen prefix.
- Per-day consistency (Q13 `daily_30d`, `nightly`) follows ADR-0007 C-57; with the generator's clock-skewed
  instances (`skewed-instances`, D-15 clamp) it reports the days around midnight of their applications
  (docs/poc/clickhouse-benchmark.md 17.3). The range and minute forms must be 0.
