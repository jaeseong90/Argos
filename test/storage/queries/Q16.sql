-- SPDX-License-Identifier: Apache-2.0
--
-- Q16 Late arrival (13 section 15 Q16, D-03, T-ST-033). Procedure LateArrival: 20,000 xlog rows
-- whose ts lie one hour before the end of the data are inserted through the writer path; the
-- rollups of exactly those minutes (and their hours) must grow by exactly the inserted rows, and
-- Q12 must stay at 0 mismatches afterwards (window and full range).
-- Criterion: Q12 0 rows; per-minute rollup increase = inserted rows.
--
-- @id        Q16
-- @title     Late arrival: 20,000 rows with ts 1h ago -> minute rollups updated, Q12 again
-- @source    docs/13 §15 Q16; D-03; T-ST-033
-- @kind      procedure
-- @account   migrator
-- @criterion rows == 0, delta_mismatches == 0
-- @settings  max_execution_time=600, join_use_nulls=0

-- @step window_minutes
-- Per minute of the late window: rows of xlog and the sums of the three 1m rollups.
-- UNION ALL + GROUP BY (Q12 form): a minute missing on one side counts 0.
SELECT m,
       sumIf(c, src = 'xlog')     AS xlog_count,
       sumIf(c, src = 'app')      AS app_1m,
       sumIf(c, src = 'service')  AS service_1m,
       sumIf(c, src = 'instance') AS instance_1m
FROM
(
    SELECT 'xlog' AS src, toStartOfMinute(ts) AS m, count() AS c FROM {db}.{p}xlog
    WHERE ts >= {window_from:DateTime} AND ts < {window_to:DateTime} GROUP BY m
    UNION ALL
    SELECT 'app', minute, sum(count) FROM {db}.{p}app_stat_1m
    WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime} GROUP BY minute
    UNION ALL
    SELECT 'service', minute, sum(count) FROM {db}.{p}service_stat_1m
    WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime} GROUP BY minute
    UNION ALL
    SELECT 'instance', minute, sum(count) FROM {db}.{p}instance_stat_1m
    WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime} GROUP BY minute
)
GROUP BY m
ORDER BY m

-- @step window_hours
-- The hours of the late window in the 1h cascade.
SELECT h, sumIf(c, src = 'app') AS app_1h, sumIf(c, src = 'service') AS service_1h
FROM
(
    SELECT 'app' AS src, hour AS h, sum(count) AS c FROM {db}.{p}app_stat_1h
    WHERE hour >= toStartOfHour({window_from:DateTime}) AND hour < {window_to:DateTime} GROUP BY h
    UNION ALL
    SELECT 'service', hour AS h, sum(count) FROM {db}.{p}service_stat_1h
    WHERE hour >= toStartOfHour({window_from:DateTime}) AND hour < {window_to:DateTime} GROUP BY h
)
GROUP BY h
ORDER BY h
