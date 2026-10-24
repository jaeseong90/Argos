-- SPDX-License-Identifier: Apache-2.0
--
-- Q17 Concurrent load (13 section 15 Q17, N-06): while the replay generator writes 5,000 TPS +
-- 50,000 step/s + 10,000 stat rows/s for an hour, 10 virtual users run Q1, Q3 and Q7 every 5 s for
-- 30 minutes. Procedure ConcurrentLoad runs the users (the variants named by @uses) and samples the
-- steps below every 10 s as argos_reader; the replay itself and its ingestion delay p99 belong to
-- the generator (test/storage/generator).
-- Window: during a replay (--q17-ingestion other than none, or --q17-window live) every iteration
-- of a user ends its query window at the server's current minute (step server_minute, renewed at
-- most once per second), so the users read the parts being written, as a dashboard of the last
-- hour does; Q7 is the 1d_live form (last 2 minutes from xlog, 13 section 6.1). Without ingestion
-- (--q17-window fixed) the window ends at end_minute of the loaded data.
-- Criteria: query p95 2 s, ingestion delay p99 5 s (generator), active parts per partition < 300,
-- DelayedInserts 0, memory peak < 7.5 GiB (13 section 7.1; on a smaller container the limit is its
-- max_server_memory_usage).
--
-- @id        Q17
-- @title     Concurrent load: 10 users x (Q1, Q3, Q7) every 5 s during the replay
-- @source    docs/13 §15 Q17; N-06, N-18
-- @kind      procedure
-- @account   reader
-- @criterion query_p95_ms <= 2000, parts_per_partition < 300, delayed_inserts == 0, memory_peak_bytes < 8053063680
-- @uses      Q01/1h, Q03/10m, Q07/1d_live
-- @settings  max_execution_time=10

-- @step server_minute
-- end_minute of the live window.
SELECT toStartOfMinute(now()) AS m

-- @step parts_per_partition
-- The partition with the most active parts among the tables of this prefix.
SELECT table, partition, count() AS parts
FROM system.parts
WHERE database = {database:String} AND active AND startsWith(table, {prefix:String})
GROUP BY table, partition
ORDER BY parts DESC, table, partition
LIMIT 1

-- @step insert_events
SELECT
    sumIf(value, event = 'DelayedInserts')            AS delayed_inserts,
    sumIf(value, event = 'RejectedInserts')           AS rejected_inserts,
    sumIf(value, event = 'DelayedInsertsMilliseconds') AS delayed_ms,
    sumIf(value, event = 'InsertedRows')              AS inserted_rows
FROM system.events
WHERE event IN ('DelayedInserts', 'RejectedInserts', 'DelayedInsertsMilliseconds', 'InsertedRows')

-- @step memory
-- MemoryTracking: what the server accounts against max_server_memory_usage; MemoryResident: RSS.
SELECT
    (SELECT toInt64(value) FROM system.metrics WHERE metric = 'MemoryTracking')                AS tracked_bytes,
    (SELECT toInt64(value) FROM system.asynchronous_metrics WHERE metric = 'MemoryResident')   AS resident_bytes,
    (SELECT count() FROM system.merges)                                                        AS merges,
    (SELECT toInt64(value) FROM system.metrics WHERE metric = 'PartsActive')                   AS parts_active
