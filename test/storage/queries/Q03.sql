-- SPDX-License-Identifier: Apache-2.0
--
-- Q3 XLog points with deterministic sampling (13 section 6.3 (3), A-5): total first, then
-- N = ceil(total / 100,000) and every error and slow transaction plus cityHash64(tx_id) % N = 0;
-- the response carries shown / total. The total is a scalar subquery of the same statement (one
-- round trip, same result as the two-step form of 6.3 (3)). LIMIT 120000 bounds the result exactly
-- (the reader profile's result_overflow_mode = break stops only at a block boundary).
--
-- Variants: the 10 minute window of 13 section 15, and a window sized so that the application has
-- about 100,000 points (N-07 "XLog 100,000 points 2 s"; at 500 TPS over 10 applications 10 minutes
-- hold about 30,000 points of one application). Criterion of 13 section 15 and N-07: 2 s.
--
-- @id        Q03
-- @title     XLog 10 min / 100k points (deterministic sampling, shown/total)
-- @source    docs/13 §6.3 (3), §15 Q3; N-07; A-5
-- @kind      timing
-- @account   reader
-- @criterion warm_ms <= 2000
-- @settings  max_execution_time=10, max_result_rows=120000

-- @probe xlog_window
-- Minutes of the busiest application that hold about 100,000 HTTP transactions (at most 24 hours,
-- the API range limit of 13 section 6.2), from the last day of app_stat_1m.
SELECT toUInt32(least(1440, greatest(10, ceil(100000 / greatest(1, sum(count) / 1440))))) AS window_100k
FROM {db}.{p}app_stat_1m
WHERE app_id = {app:UInt32} AND kind = 'HTTP'
  AND minute >= {end_minute:DateTime} - INTERVAL 1 DAY AND minute < {end_minute:DateTime}

-- @variant 10m minutes=10 slow_ms=3000
-- @expect rows >= 1
WITH
    (SELECT count() FROM {db}.{p}xlog
     WHERE app_id = {app:UInt32} AND kind = 'HTTP'
       AND ts >= {end_minute:DateTime} - toIntervalMinute({minutes:UInt32}) AND ts < {end_minute:DateTime}) AS total,
    greatest(toUInt64(1), toUInt64(ceil(total / 100000))) AS n
SELECT ts, tx_id, elapsed_ms, error, service_id, instance_id, kind, total
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind = 'HTTP'
  AND ts >= {end_minute:DateTime} - toIntervalMinute({minutes:UInt32}) AND ts < {end_minute:DateTime}
  AND (error = 1 OR elapsed_ms >= {slow_ms:UInt32} OR cityHash64(tx_id) % n = 0)
ORDER BY ts
LIMIT 120000

-- @variant 100k slow_ms=3000
-- @expect rows >= 1
WITH
    (SELECT count() FROM {db}.{p}xlog
     WHERE app_id = {app:UInt32} AND kind = 'HTTP'
       AND ts >= {end_minute:DateTime} - toIntervalMinute({window_100k:UInt32}) AND ts < {end_minute:DateTime}) AS total,
    greatest(toUInt64(1), toUInt64(ceil(total / 100000))) AS n
SELECT ts, tx_id, elapsed_ms, error, service_id, instance_id, kind, total
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind = 'HTTP'
  AND ts >= {end_minute:DateTime} - toIntervalMinute({window_100k:UInt32}) AND ts < {end_minute:DateTime}
  AND (error = 1 OR elapsed_ms >= {slow_ms:UInt32} OR cityHash64(tx_id) % n = 0)
ORDER BY ts
LIMIT 120000
