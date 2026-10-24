-- SPDX-License-Identifier: Apache-2.0
--
-- Q5 Transaction detail by tx_id (13 section 6.3 (5), DSN-02-09, A-6):
--   restored          ts restored from the id (41 bit ms since 2026-01-01) +-2 s, with the app hint
--   restored_no_app   the same without the app hint (6.3 (5): partition and sort key narrow enough)
--   url_ts            the ts carried by the detail URL (exact ms) and the app
--   profile           the steps of a captured transaction (profile_step, the second query of the
--                     detail page)
--   bloom_only        no time range at all, only the idx_tx bloom filter over every partition (the
--                     bloom parameters fpp 0.01 / 0.025 x GRANULARITY 1 / 4 are compared by the
--                     bloom variant procedure on copies of the same days)
--   restored_skewed   the restored form for a transaction of a skewed clock (probe skewed): an
--                     instance whose clock runs ahead gets its ts clamped to the receive time by
--                     the server (D-15) while tx_id keeps the agent time, so +-2 s finds no row
--                     (recorded: 0 rows is the expected outcome that motivates the fallback)
--   fallback_2d       the A-6 fallback proposed by the PoC (docs/poc/clickhouse-benchmark.md 12.1):
--                     the day of the restored time and the day before, bloom filter idx_tx, no app
--   fallback_coverage how many transactions of the last day have a restored time more than 2 s off,
--                     and how many of those the 2 day fallback cannot reach (ts before the day
--                     before the restored day: a clock more than 24 h to 48 h ahead); recorded
-- Without a skewed clock in the data the probe finds nothing and the two skewed variants are not
-- applicable (NOT_APPLICABLE), not failed.
-- Criteria of 13 section 15 and N-07/N-24: 0.5 s / 0.5 s / measured (decision).
--
-- @id        Q05
-- @title     tx_id detail: restored ts +-2 s / URL ts / bloom only
-- @source    docs/13 §6.3 (5), §15 Q5; N-07, N-24; A-6, DSN-02-09
-- @kind      timing
-- @account   reader
-- @criterion warm_ms <= 500
-- @settings  max_execution_time=10

-- @probe tx
-- A transaction of the busiest application 12 hours before the end (or at the start of shorter
-- data), one of the oldest third of the data (bloom only lookups scan every partition before it is
-- found), and a transaction with steps in profile_step during the last hour (profile_step keeps 7
-- days, the PoC generator writes it at the end of the range).
SELECT
    (SELECT tx_id FROM {db}.{p}xlog
        WHERE app_id = {app:UInt32}
          AND ts >= greatest({start_minute:DateTime}, {end_minute:DateTime} - INTERVAL 12 HOUR)
          AND ts < greatest({start_minute:DateTime}, {end_minute:DateTime} - INTERVAL 12 HOUR) + INTERVAL 1 HOUR
        ORDER BY ts, tx_id LIMIT 1)                                                           AS tx,
    (SELECT ts FROM {db}.{p}xlog
        WHERE app_id = {app:UInt32}
          AND ts >= greatest({start_minute:DateTime}, {end_minute:DateTime} - INTERVAL 12 HOUR)
          AND ts < greatest({start_minute:DateTime}, {end_minute:DateTime} - INTERVAL 12 HOUR) + INTERVAL 1 HOUR
        ORDER BY ts, tx_id LIMIT 1)                                                           AS tx_ts,
    (SELECT tx_id FROM {db}.{p}xlog
        WHERE app_id = {app:UInt32}
          AND ts >= {start_minute:DateTime}
                    + toIntervalSecond(intDiv(dateDiff('second', {start_minute:DateTime}, {end_minute:DateTime}), 3))
          AND ts < {start_minute:DateTime}
                    + toIntervalSecond(intDiv(dateDiff('second', {start_minute:DateTime}, {end_minute:DateTime}), 3) + 3600)
        ORDER BY ts, tx_id LIMIT 1)                                                           AS tx_old,
    (SELECT min(tx_id) FROM {db}.{p}profile_step
        WHERE app_id = {app:UInt32}
          AND ts >= {end_minute:DateTime} - INTERVAL 1 HOUR AND ts < {end_minute:DateTime})   AS ptx

-- @probe skewed
-- @optional no transaction whose ts differs from the time in its tx_id by more than 2 s (no skewed clock)
-- A transaction of the last day whose ts is more than 2 s away from the time restored from its
-- tx_id, preferring the largest day difference (the hardest case for the 2 day fallback), then the
-- latest. All applications (the fallback has no app hint); one day of tx_id and ts.
SELECT
    (SELECT tx_id FROM {db}.{p}xlog
        WHERE ts >= {end_minute:DateTime} - INTERVAL 1 DAY AND ts < {end_minute:DateTime}
          AND abs(dateDiff('millisecond', ts,
                  toDateTime64((bitShiftRight(tx_id, 23) + 1767225600000) / 1000, 3, 'UTC'))) > 2000
        ORDER BY dateDiff('day', toDate(ts),
                          toDate(toDateTime64((bitShiftRight(tx_id, 23) + 1767225600000) / 1000, 3, 'UTC'))) DESC,
                 ts DESC, tx_id
        LIMIT 1)                                                                              AS tx_skewed

-- @variant restored
-- @expect rows == 1
WITH toDateTime64((bitShiftRight({tx:UInt64}, 23) + 1767225600000) / 1000, 3, 'UTC') AS t0
SELECT *
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32}
  AND ts BETWEEN t0 - INTERVAL 2 SECOND AND t0 + INTERVAL 2 SECOND
  AND tx_id = {tx:UInt64}
LIMIT 2

-- @variant restored_no_app
-- @expect rows == 1
WITH toDateTime64((bitShiftRight({tx:UInt64}, 23) + 1767225600000) / 1000, 3, 'UTC') AS t0
SELECT *
FROM {db}.{p}xlog
WHERE ts BETWEEN t0 - INTERVAL 2 SECOND AND t0 + INTERVAL 2 SECOND
  AND tx_id = {tx:UInt64}
LIMIT 2

-- @variant url_ts
-- @expect rows == 1
SELECT *
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND ts = {tx_ts:DateTime64(3)} AND tx_id = {tx:UInt64}
LIMIT 2

-- @variant profile
-- @expect rows >= 1
SELECT *
FROM {db}.{p}profile_step
WHERE app_id = {app:UInt32} AND tx_id = {ptx:UInt64}
ORDER BY seq

-- @variant bloom_only
-- @criterion record
-- @expect rows == 1
SELECT *
FROM {db}.{p}xlog
WHERE tx_id = {tx_old:UInt64}
LIMIT 2

-- @variant restored_skewed
-- @criterion record
WITH toDateTime64((bitShiftRight({tx_skewed:UInt64}, 23) + 1767225600000) / 1000, 3, 'UTC') AS t0
SELECT *
FROM {db}.{p}xlog
WHERE ts BETWEEN t0 - INTERVAL 2 SECOND AND t0 + INTERVAL 2 SECOND
  AND tx_id = {tx_skewed:UInt64}
LIMIT 2

-- @variant fallback_2d
-- @expect rows == 1
WITH toDateTime64((bitShiftRight({tx_skewed:UInt64}, 23) + 1767225600000) / 1000, 3, 'UTC') AS t0
SELECT *
FROM {db}.{p}xlog
WHERE toDate(ts) BETWEEN toDate(t0) - 1 AND toDate(t0)
  AND tx_id = {tx_skewed:UInt64}
LIMIT 2

-- @variant fallback_coverage
-- @criterion record
WITH toDateTime64((bitShiftRight(tx_id, 23) + 1767225600000) / 1000, 3, 'UTC') AS t0
SELECT count()                                                         AS transactions,
       countIf(abs(dateDiff('millisecond', ts, t0)) > 2000)            AS restore_misses,
       countIf(toDate(ts) < toDate(t0) - 1)                            AS fallback_misses,
       max(dateDiff('second', ts, t0))                                 AS max_ahead_s
FROM {db}.{p}xlog
WHERE ts >= {end_minute:DateTime} - INTERVAL 1 DAY AND ts < {end_minute:DateTime}

-- @step bloom_fill
-- Bloom variant procedure (BloomVariants): the same days of xlog copied into one table per
-- (fpp, GRANULARITY) of the five bloom indexes (V001 statement rendered with the variant).
-- A data copy, not a timed lookup: the migrator profile limit replaces the reader limit above.
-- @account migrator
-- @settings max_execution_time=3600
INSERT INTO {db}.{p}{variant}xlog SELECT * FROM {db}.{p}xlog WHERE ts >= {from:DateTime} AND ts < {to:DateTime}

-- @step bloom_targets
-- Lookup targets inside the copied days: a transaction, a trace and a user id from the first hours
-- of the last copied day ({to} - 1 day = full_day, which always has those hours; the first copied
-- day can be the partial first day of a reduced-scale data set, e.g. 1/100 starts at 20:24), and a
-- SQL of medium frequency of the busiest application.
SELECT
    (SELECT tx_id FROM {db}.{p}xlog WHERE app_id = {app:UInt32}
        AND ts >= {to:DateTime} - INTERVAL 1 DAY + INTERVAL 1 HOUR
        AND ts < {to:DateTime} - INTERVAL 1 DAY + INTERVAL 2 HOUR
        ORDER BY ts, tx_id LIMIT 1)                                                           AS btx,
    (SELECT hex(trace_id) FROM {db}.{p}xlog WHERE app_id = {app:UInt32}
        AND ts >= {to:DateTime} - INTERVAL 1 DAY + INTERVAL 2 HOUR
        AND ts < {to:DateTime} - INTERVAL 1 DAY + INTERVAL 3 HOUR
        ORDER BY caller_app_id != 0 DESC, ts, tx_id LIMIT 1)                                  AS btrace,
    (SELECT any(user_id) FROM {db}.{p}xlog WHERE app_id = {app:UInt32}
        AND ts >= {to:DateTime} - INTERVAL 1 DAY + INTERVAL 3 HOUR
        AND ts < {to:DateTime} - INTERVAL 1 DAY + INTERVAL 4 HOUR
        AND user_id != '')                                                                    AS buid,
    (SELECT sql_id FROM {db}.{p}sql_stat_1m WHERE app_id = {app:UInt32}
        AND minute >= {from:DateTime} AND minute < {to:DateTime}
        GROUP BY sql_id ORDER BY sum(count) DESC, sql_id LIMIT 1 OFFSET 20)                   AS bsql

-- @step bloom_tx
-- @account reader
SELECT * FROM {db}.{p}{variant}xlog WHERE tx_id = {btx:UInt64} LIMIT 2

-- @step bloom_trace
-- @account reader
SELECT app_id, tx_id, ts FROM {db}.{p}{variant}xlog WHERE trace_id = toFixedString(unhex({btrace:String}), 16) LIMIT 500

-- @step bloom_user
-- @account reader
SELECT ts, tx_id FROM {db}.{p}{variant}xlog WHERE app_id = {app:UInt32} AND user_id = {buid:String} ORDER BY ts DESC LIMIT 1000

-- @step bloom_sql
-- @account reader
SELECT ts, tx_id FROM {db}.{p}{variant}xlog WHERE app_id = {app:UInt32} AND has(sql_ids, {bsql:UInt64}) ORDER BY ts DESC LIMIT 1000

-- @step bloom_sizes
-- @account reader
SELECT table, sum(rows) AS rows, sum(secondary_indices_compressed_bytes) AS index_compressed,
       sum(data_compressed_bytes) AS data_compressed
FROM system.parts
WHERE database = {database:String} AND active AND table = {table:String}
GROUP BY table

-- @step bloom_drop
-- @account migrator
DROP TABLE IF EXISTS {db}.{p}{variant}xlog SYNC
