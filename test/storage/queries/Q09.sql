-- SPDX-License-Identifier: Apache-2.0
--
-- Q9 Errors: error group aggregation of one day from error_stat_1m (count, first/last seen,
-- affected services and versions; the group metadata lives in PostgreSQL error_groups), and the
-- related transactions of one group over 30 days from xlog (13 section 6.3 (9)).
-- Criterion of 13 section 15: 1 s.
--
-- @id        Q09
-- @title     Error groups 1d + group -> related transactions 30d
-- @source    docs/13 §6.3 (9), §15 Q9; F-S19
-- @kind      timing
-- @account   reader
-- @criterion warm_ms <= 1000
-- @settings  max_execution_time=10, max_result_rows=500

-- @probe error_group
-- The most frequent error group of the busiest application over the last day.
SELECT error_group_id AS egroup
FROM {db}.{p}error_stat_1m
WHERE app_id = {app:UInt32}
  AND minute >= {end_minute:DateTime} - INTERVAL 1 DAY AND minute < {end_minute:DateTime}
GROUP BY error_group_id
ORDER BY sum(count) DESC, error_group_id
LIMIT 1

-- @variant groups_1d days=1
-- @expect rows >= 1
SELECT error_group_id,
       sum(count) AS cnt,
       min(first_ts) AS first_seen,
       max(last_ts) AS last_seen,
       uniqExact(service_id) AS services,
       groupUniqArray(10)(version_id) AS versions
FROM {db}.{p}error_stat_1m
WHERE app_id = {app:UInt32}
  AND minute >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND minute < {end_minute:DateTime}
GROUP BY error_group_id
ORDER BY cnt DESC
LIMIT 500

-- @variant related_30d days=30
-- @expect rows >= 1
SELECT ts, tx_id, service_id, instance_id, version_id, elapsed_ms
FROM {db}.{p}xlog
WHERE app_id = {app:UInt32}
  AND ts >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND ts < {end_minute:DateTime}
  AND error_group_id = {egroup:UInt64}
ORDER BY ts DESC
LIMIT 10
