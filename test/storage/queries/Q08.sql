-- SPDX-License-Identifier: Apache-2.0
--
-- Q8 SQL Top N from sql_stat_1m (complete statistics, D-12) with the bucket p95 estimate (C-14),
-- 1 day and 7 days, and the trend of one SQL (detail page, 5 / 15 minute steps of 6.1).
--
-- Buckets: sql_stat_1m.buckets is AggregateFunction(sumForEach, Array(UInt64)) (13 section 17
-- item 10 fallback), read with sumForEachMerge (13 section 6.3 (8) writes sumForEach, which does
-- not apply to the stored state). The p95 estimate interpolates linearly inside the bucket that
-- holds the 95th percentile rank, with the 13 section 2 bounds; the open bucket above 30 s returns
-- 30,000 ms (its lower bound). The same estimate is BucketQuantile in the runner (Q14 bucket error).
-- Criterion of 13 section 15 and N-07: 1 s.
--
-- @id        Q08
-- @title     SQL Top N 1d / 7d (sql_stat_1m, bucket p95), SQL detail trend
-- @source    docs/13 §6.3 (8), §15 Q8; N-07; C-14
-- @kind      timing
-- @account   reader
-- @criterion warm_ms <= 1000
-- @settings  max_execution_time=10, max_result_rows=1000

-- @probe sql
-- The SQL with the largest total time of the busiest application over the last day.
SELECT sql_id AS sql
FROM {db}.{p}sql_stat_1m
WHERE app_id = {app:UInt32} AND kind = 'HTTP'
  AND minute >= {end_minute:DateTime} - INTERVAL 1 DAY AND minute < {end_minute:DateTime}
GROUP BY sql_id
ORDER BY sum(sum_ms) DESC, sql_id
LIMIT 1

-- @variant 1d days=1
-- @expect rows >= 1
WITH [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000] AS bounds
SELECT sql_id, db_id, cnt, err, total_ms, avg_ms, max_ms, rows, b,
       arrayCumSum(b) AS cum,
       0.95 * cum[15] AS target,
       arrayFirstIndex(c -> c >= target, cum) AS i,
       if(cum[15] = 0, nan,
          if(i <= 1, 0, bounds[i - 1])
          + (if(i >= 15, 30000, bounds[i]) - if(i <= 1, 0, bounds[i - 1]))
            * (target - if(i <= 1, 0, cum[i - 1])) / b[i]) AS p95_est
FROM
(
    SELECT sql_id, db_id,
           sum(count) AS cnt, sum(error_count) AS err,
           sum(sum_ms) AS total_ms, sum(sum_ms) / sum(count) AS avg_ms, max(max_ms) AS max_ms,
           sum(fetch_rows) AS rows, sumForEachMerge(buckets) AS b
    FROM {db}.{p}sql_stat_1m
    WHERE app_id = {app:UInt32} AND kind = 'HTTP'
      AND minute >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND minute < {end_minute:DateTime}
    GROUP BY sql_id, db_id
    ORDER BY total_ms DESC
    LIMIT 500
)
ORDER BY total_ms DESC

-- @variant 7d days=7
-- @expect rows >= 1
WITH [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000] AS bounds
SELECT sql_id, db_id, cnt, err, total_ms, avg_ms, max_ms, rows, b,
       arrayCumSum(b) AS cum,
       0.95 * cum[15] AS target,
       arrayFirstIndex(c -> c >= target, cum) AS i,
       if(cum[15] = 0, nan,
          if(i <= 1, 0, bounds[i - 1])
          + (if(i >= 15, 30000, bounds[i]) - if(i <= 1, 0, bounds[i - 1]))
            * (target - if(i <= 1, 0, cum[i - 1])) / b[i]) AS p95_est
FROM
(
    SELECT sql_id, db_id,
           sum(count) AS cnt, sum(error_count) AS err,
           sum(sum_ms) AS total_ms, sum(sum_ms) / sum(count) AS avg_ms, max(max_ms) AS max_ms,
           sum(fetch_rows) AS rows, sumForEachMerge(buckets) AS b
    FROM {db}.{p}sql_stat_1m
    WHERE app_id = {app:UInt32} AND kind = 'HTTP'
      AND minute >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND minute < {end_minute:DateTime}
    GROUP BY sql_id, db_id
    ORDER BY total_ms DESC
    LIMIT 500
)
ORDER BY total_ms DESC

-- @variant detail_1d days=1 step=5
-- @expect rows >= 1
SELECT toStartOfInterval(minute, toIntervalMinute({step:UInt32})) AS t,
       sum(count) AS cnt, sum(error_count) AS err,
       sum(sum_ms) / sum(count) AS avg_ms, max(max_ms) AS max_ms,
       sumForEachMerge(buckets) AS b
FROM {db}.{p}sql_stat_1m
WHERE app_id = {app:UInt32} AND sql_id = {sql:UInt64}
  AND minute >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND minute < {end_minute:DateTime}
GROUP BY t
ORDER BY t

-- @variant detail_7d days=7 step=15
-- @expect rows >= 1
SELECT toStartOfInterval(minute, toIntervalMinute({step:UInt32})) AS t,
       sum(count) AS cnt, sum(error_count) AS err,
       sum(sum_ms) / sum(count) AS avg_ms, max(max_ms) AS max_ms,
       sumForEachMerge(buckets) AS b
FROM {db}.{p}sql_stat_1m
WHERE app_id = {app:UInt32} AND sql_id = {sql:UInt64}
  AND minute >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND minute < {end_minute:DateTime}
GROUP BY t
ORDER BY t
