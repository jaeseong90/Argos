-- SPDX-License-Identifier: Apache-2.0
--
-- Q2 Dashboard, rollup sources (13 section 6.3 (2), source rule 6.1): 7 days from app_stat_1m in
-- 15 minute buckets (7 days / 720 buckets = 14 minutes, rounded up to the 15 minute step of 6.1)
-- and 90 days from app_stat_1h in 6 hour buckets (90 days / 720 = 3 hours, 6.1 steps 1h/6h/1d).
-- The API adds the last 2 minutes from xlog (6.1); that tail is Q1's query on a 2 minute range.
-- Criterion of 13 section 15: 1 s each.
--
-- @id        Q02
-- @title     Dashboard 7d app_stat_1m / 90d app_stat_1h
-- @source    docs/13 §6.3 (2), §6.1, §15 Q2; N-07
-- @kind      timing
-- @account   reader
-- @criterion warm_ms <= 1000
-- @settings  max_execution_time=10

-- @variant 7d_1m days=7 step=15
-- @expect rows >= 1
SELECT toStartOfInterval(minute, toIntervalMinute({step:UInt32})) AS b,
       sum(count) AS cnt,
       sum(error_count) AS err,
       sum(sum_elapsed_ms) / sum(count) AS avg_ms,
       quantilesTDigestMerge(0.5, 0.95, 0.99)(q_elapsed) AS q,
       (sum(apdex_satisfied) + sum(apdex_tolerating) / 2) / sum(count) AS apdex
FROM {db}.{p}app_stat_1m
WHERE app_id = {app:UInt32} AND kind = 'HTTP'
  AND minute >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND minute < {end_minute:DateTime}
GROUP BY b
ORDER BY b

-- @variant 90d_1h days=90 step=6
-- @expect rows >= 1
SELECT toStartOfInterval(hour, toIntervalHour({step:UInt32})) AS b,
       sum(count) AS cnt,
       sum(error_count) AS err,
       sum(sum_elapsed_ms) / sum(count) AS avg_ms,
       quantilesTDigestMerge(0.5, 0.95, 0.99)(q_elapsed) AS q,
       (sum(apdex_satisfied) + sum(apdex_tolerating) / 2) / sum(count) AS apdex
FROM {db}.{p}app_stat_1h
WHERE app_id = {app:UInt32} AND kind = 'HTTP'
  AND hour >= {end_hour:DateTime} - toIntervalDay({days:UInt32}) AND hour < {end_hour:DateTime}
GROUP BY b
ORDER BY b
