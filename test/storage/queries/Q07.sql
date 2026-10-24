-- SPDX-License-Identifier: Apache-2.0
--
-- Q7 Service Top N from service_stat_1m with merged percentiles (13 section 6.3 (7)): 1 day and 7
-- days, and the same split by application version (F-A21). The sort column is a query parameter
-- (Identifier); the API offers cnt, err, avg_ms, max_ms and total time. Criterion of 13 section 15
-- and N-07: 1 s.
-- 1d_live is the 1 day form with the late-arrival correction of 13 section 6.1 (the last 2 minutes
-- always come from the raw table): service_stat_1m up to end_minute - 2 min plus xlog for the last
-- 2 minutes, merged per service (tDigest states of both sides merge). Q17 runs it with a live
-- end_minute during the replay.
--
-- @id        Q07
-- @title     Service Top N 1d / 7d (merged percentiles), split by version
-- @source    docs/13 §6.3 (7), §15 Q7; N-07
-- @kind      timing
-- @account   reader
-- @criterion warm_ms <= 1000
-- @settings  max_execution_time=10, max_result_rows=500

-- @variant 1d days=1 sort=cnt
-- @expect rows >= 1
SELECT service_id,
       sum(count) AS cnt, sum(error_count) AS err,
       sum(sum_elapsed_ms) / sum(count) AS avg_ms, max(max_elapsed_ms) AS max_ms,
       quantilesTDigestMerge(0.5, 0.95, 0.99)(q_elapsed) AS q,
       (sum(apdex_satisfied) + sum(apdex_tolerating) / 2) / sum(count) AS apdex
FROM {db}.{p}service_stat_1m
WHERE app_id = {app:UInt32} AND kind = 'HTTP'
  AND minute >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND minute < {end_minute:DateTime}
GROUP BY service_id
ORDER BY {sort:Identifier} DESC
LIMIT 500

-- @variant 1d_live days=1 sort=cnt
-- @expect rows >= 1
SELECT service_id,
       sum(c) AS cnt, sum(e) AS err,
       sum(s) / sum(c) AS avg_ms, max(mx) AS max_ms,
       quantilesTDigestMerge(0.5, 0.95, 0.99)(qs) AS q,
       (sum(sat) + sum(tol) / 2) / sum(c) AS apdex
FROM
(
    SELECT service_id,
           sum(count) AS c, sum(error_count) AS e, sum(sum_elapsed_ms) AS s, max(max_elapsed_ms) AS mx,
           quantilesTDigestMergeState(0.5, 0.95, 0.99)(q_elapsed) AS qs,
           sum(apdex_satisfied) AS sat, sum(apdex_tolerating) AS tol
    FROM {db}.{p}service_stat_1m
    WHERE app_id = {app:UInt32} AND kind = 'HTTP'
      AND minute >= {end_minute:DateTime} - toIntervalDay({days:UInt32})
      AND minute < {end_minute:DateTime} - INTERVAL 2 MINUTE
    GROUP BY service_id
    UNION ALL
    SELECT service_id,
           count(), countIf(error = 1), sum(elapsed_ms), max(elapsed_ms),
           quantilesTDigestState(0.5, 0.95, 0.99)(elapsed_ms),
           countIf(elapsed_ms <= apdex_t_ms), countIf(elapsed_ms > apdex_t_ms AND elapsed_ms <= 4 * apdex_t_ms)
    FROM {db}.{p}xlog
    WHERE app_id = {app:UInt32} AND kind = 'HTTP'
      AND ts >= {end_minute:DateTime} - INTERVAL 2 MINUTE AND ts < {end_minute:DateTime}
    GROUP BY service_id
)
GROUP BY service_id
ORDER BY {sort:Identifier} DESC
LIMIT 500

-- @variant 7d days=7 sort=cnt
-- @expect rows >= 1
SELECT service_id,
       sum(count) AS cnt, sum(error_count) AS err,
       sum(sum_elapsed_ms) / sum(count) AS avg_ms, max(max_elapsed_ms) AS max_ms,
       quantilesTDigestMerge(0.5, 0.95, 0.99)(q_elapsed) AS q,
       (sum(apdex_satisfied) + sum(apdex_tolerating) / 2) / sum(count) AS apdex
FROM {db}.{p}service_stat_1m
WHERE app_id = {app:UInt32} AND kind = 'HTTP'
  AND minute >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND minute < {end_minute:DateTime}
GROUP BY service_id
ORDER BY {sort:Identifier} DESC
LIMIT 500

-- @variant 1d_version days=1 sort=cnt
-- @expect rows >= 1
SELECT service_id, version_id,
       sum(count) AS cnt, sum(error_count) AS err,
       sum(sum_elapsed_ms) / sum(count) AS avg_ms, max(max_elapsed_ms) AS max_ms,
       quantilesTDigestMerge(0.5, 0.95, 0.99)(q_elapsed) AS q,
       (sum(apdex_satisfied) + sum(apdex_tolerating) / 2) / sum(count) AS apdex
FROM {db}.{p}service_stat_1m
WHERE app_id = {app:UInt32} AND kind = 'HTTP'
  AND minute >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND minute < {end_minute:DateTime}
GROUP BY service_id, version_id
ORDER BY {sort:Identifier} DESC
LIMIT 500

-- @variant 7d_version days=7 sort=cnt
-- @expect rows >= 1
SELECT service_id, version_id,
       sum(count) AS cnt, sum(error_count) AS err,
       sum(sum_elapsed_ms) / sum(count) AS avg_ms, max(max_elapsed_ms) AS max_ms,
       quantilesTDigestMerge(0.5, 0.95, 0.99)(q_elapsed) AS q,
       (sum(apdex_satisfied) + sum(apdex_tolerating) / 2) / sum(count) AS apdex
FROM {db}.{p}service_stat_1m
WHERE app_id = {app:UInt32} AND kind = 'HTTP'
  AND minute >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND minute < {end_minute:DateTime}
GROUP BY service_id, version_id
ORDER BY {sort:Identifier} DESC
LIMIT 500
