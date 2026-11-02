-- SPDX-License-Identifier: Apache-2.0
--
-- Q11 'Contains SQL' search with has(sql_ids, ?) over 1 day (13 section 6.3 (12), A-10; idx_sql
-- bloom filter on Array(UInt64), 13 section 17 item 8), and over 30 days (the related transactions
-- of an SQL, the reason for A-10) as a record. Criterion of 13 section 15: 2 s.
--
-- The disk cost of the column (GB per day) is measured by the SqlIdsCost procedure with the steps
-- below: one day of xlog is copied into two tables with the xlog sort key, one with sql_ids and its
-- bloom index and one without, and the difference of their compressed sizes (system.parts) is the
-- cost of the column. A column-level system table (system.columns, system.parts_columns) would be
-- simpler but no Argos account may read it (SEC-76).
--
-- @id        Q11
-- @title     has(sql_ids, ?) 1d + sql_ids disk cost (GB/day)
-- @source    docs/13 §6.3 (12), §15 Q11, §17 #8; A-10
-- @kind      timing
-- @account   reader
-- @criterion warm_ms <= 2000
-- @settings  max_execution_time=10, max_result_rows=1000

-- @probe sql_id
-- A SQL of medium frequency (rank 20 by executions over the last day): the top SQL appears in
-- most transactions, which would turn the search into a scan.
SELECT sql_id AS sql_id
FROM {db}.{p}sql_stat_1m
WHERE app_id = {app:UInt32}
  AND minute >= {end_minute:DateTime} - INTERVAL 1 DAY AND minute < {end_minute:DateTime}
GROUP BY sql_id
ORDER BY sum(count) DESC, sql_id
LIMIT 1 OFFSET 20

-- @variant has_1d days=1
-- @expect rows >= 1
SELECT ts, tx_id, service_id, elapsed_ms, sql_count, sql_time_ms
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32}
  AND ts >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND ts < {end_minute:DateTime}
  AND has(sql_ids, {sql_id:UInt64})
ORDER BY ts DESC
LIMIT 1000

-- @variant has_30d days=30
-- @criterion record
-- @expect rows >= 1
SELECT ts, tx_id, service_id, elapsed_ms, sql_count, sql_time_ms
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32}
  AND ts >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND ts < {end_minute:DateTime}
  AND has(sql_ids, {sql_id:UInt64})
ORDER BY ts DESC
LIMIT 1000

-- @step cost_create_with
-- @account migrator
CREATE TABLE IF NOT EXISTS {db}.{p}q11_with
(
    app_id  UInt32,
    ts      DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(1)),
    tx_id   UInt64 CODEC(Delta, ZSTD(1)),
    sql_ids Array(UInt64),
    INDEX idx_sql sql_ids TYPE bloom_filter(0.01) GRANULARITY 4
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, ts, tx_id)

-- @step cost_create_without
-- @account migrator
CREATE TABLE IF NOT EXISTS {db}.{p}q11_without
(
    app_id  UInt32,
    ts      DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(1)),
    tx_id   UInt64 CODEC(Delta, ZSTD(1))
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (app_id, ts, tx_id)

-- @step cost_fill_with
-- Data copies, not timed lookups: the migrator profile limit replaces the reader limit above.
-- @account migrator
-- @settings max_execution_time=3600
INSERT INTO {db}.{p}q11_with SELECT app_id, ts, tx_id, sql_ids FROM {db}.{p}xlog WHERE toDate(ts) = {day:Date}

-- @step cost_fill_without
-- @account migrator
-- @settings max_execution_time=3600
INSERT INTO {db}.{p}q11_without SELECT app_id, ts, tx_id FROM {db}.{p}xlog WHERE toDate(ts) = {day:Date}

-- @step cost_sizes
-- Compressed bytes of each copy (data and skip indexes) and the uncompressed size of the array.
SELECT table,
       sum(rows) AS rows,
       sum(data_compressed_bytes) AS data_compressed,
       sum(data_uncompressed_bytes) AS data_uncompressed,
       sum(secondary_indices_compressed_bytes) AS index_compressed
FROM system.parts
WHERE database = {database:String} AND active AND table IN ({with_table:String}, {without_table:String})
GROUP BY table
ORDER BY table

-- @step cost_drop_with
-- @account migrator
DROP TABLE IF EXISTS {db}.{p}q11_with SYNC

-- @step cost_drop_without
-- @account migrator
DROP TABLE IF EXISTS {db}.{p}q11_without SYNC
