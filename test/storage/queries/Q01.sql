-- SPDX-License-Identifier: Apache-2.0
--
-- Q1 Dashboard, raw source: the last hour of the busiest application in 10 s buckets with count,
-- errors, average and p50/p95/p99 (13 section 6.3 (1); source rule 6.1: up to 6 hours from xlog,
-- bucket = range / 360). Criterion of 13 section 15 and N-07: 1 s. The 6 hour variant (1 minute
-- buckets, the largest raw range of 6.1) is recorded.
--
-- @id        Q01
-- @title     Dashboard 1h raw 10 s buckets (count, avg, p95)
-- @source    docs/13 §6.3 (1), §6.1, §15 Q1; N-07
-- @kind      timing
-- @account   reader
-- @criterion warm_ms <= 1000
-- @settings  max_execution_time=10, max_rows_to_read=2000000000

-- @variant 1h seconds=10 hours=1
-- @expect rows >= 1
SELECT toStartOfInterval(ts, toIntervalSecond({seconds:UInt32})) AS b,
       count() AS cnt,
       countIf(error = 1) AS err,
       avg(elapsed_ms) AS avg_ms,
       quantilesTDigest(0.5, 0.95, 0.99)(elapsed_ms) AS q
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind = 'HTTP'
  AND ts >= {end_minute:DateTime} - toIntervalHour({hours:UInt32}) AND ts < {end_minute:DateTime}
GROUP BY b
ORDER BY b

-- @variant 6h seconds=60 hours=6
-- @criterion record
-- @expect rows >= 1
SELECT toStartOfInterval(ts, toIntervalSecond({seconds:UInt32})) AS b,
       count() AS cnt,
       countIf(error = 1) AS err,
       avg(elapsed_ms) AS avg_ms,
       quantilesTDigest(0.5, 0.95, 0.99)(elapsed_ms) AS q
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32} AND kind = 'HTTP'
  AND ts >= {end_minute:DateTime} - toIntervalHour({hours:UInt32}) AND ts < {end_minute:DateTime}
GROUP BY b
ORDER BY b
