-- SPDX-License-Identifier: Apache-2.0
--
-- Q10 Metrics: one instance's heap over 7 days from metric_1m (13 section 6.3 (10); the API fills
-- the time grid with null), and the application total over 90 days from metric_1h (sum over the
-- instances of the hourly average). Criterion of 13 section 15: 1 s.
--
-- @id        Q10
-- @title     Metrics: instance heap 7d (1m), application total 90d (1h)
-- @source    docs/13 §6.3 (10), §15 Q10
-- @kind      timing
-- @account   reader
-- @criterion warm_ms <= 1000
-- @settings  max_execution_time=10, max_result_rows=20000

-- @probe metric
-- A heap gauge name (the first gauge when the generator has no heap series) and the instance of the
-- busiest application that reported it most over the last day.
SELECT
    (SELECT name FROM {db}.{p}metric_1m
        WHERE app_id = {app:UInt32} AND kind = 'gauge'
          AND minute >= {end_minute:DateTime} - INTERVAL 1 DAY AND minute < {end_minute:DateTime}
        GROUP BY name ORDER BY positionCaseInsensitive(name, 'heap') = 0, name LIMIT 1)          AS metric_name,
    (SELECT instance_id FROM {db}.{p}metric_1m
        WHERE app_id = {app:UInt32}
          AND minute >= {end_minute:DateTime} - INTERVAL 1 DAY AND minute < {end_minute:DateTime}
        GROUP BY instance_id ORDER BY sum(sample_count) DESC, instance_id LIMIT 1)               AS inst

-- @variant instance_7d days=7
-- @expect rows >= 1
SELECT minute, name, kind, sample_count, avg_sum / sample_count AS avg_v, min_v, max_v, last_v, increase
FROM
(
    SELECT minute, name, any(kind) AS kind,
           sum(sample_count) AS sample_count, sum(avg_sum) AS avg_sum,
           min(min_v) AS min_v, max(max_v) AS max_v, anyLast(last_v) AS last_v, sum(increase) AS increase
    FROM {db}.{p}metric_1m
    WHERE app_id = {app:UInt32} AND instance_id = {inst:UInt32} AND name IN ({metric_name:String})
      AND minute >= {end_minute:DateTime} - toIntervalDay({days:UInt32}) AND minute < {end_minute:DateTime}
    GROUP BY minute, name
)
ORDER BY name, minute

-- @variant app_total_90d days=90
-- @expect rows >= 1
SELECT hour, sum(avg_v) AS total, count() AS instances
FROM
(
    SELECT hour, instance_id, sum(avg_sum) / sum(sample_count) AS avg_v
    FROM {db}.{p}metric_1h
    WHERE app_id = {app:UInt32} AND name = {metric_name:String}
      AND hour >= {end_hour:DateTime} - toIntervalDay({days:UInt32}) AND hour < {end_hour:DateTime}
    GROUP BY hour, instance_id
)
GROUP BY hour
ORDER BY hour
