-- SPDX-License-Identifier: Apache-2.0
--
-- Q12 D-03 consistency: per minute, count(xlog) against the sums of app_stat_1m, service_stat_1m
-- and instance_stat_1m over 30 days (13 section 15 Q12). Each result row is one (app, kind, minute)
-- that disagrees; the criterion is 0 rows. Stricter than 13 section 6.3 (13), which compares HTTP
-- minutes of all applications together: here every application and kind is compared on its own,
-- so an error in one application cannot be hidden by another.
--   minute_30d   the Q12 check (T-ST-030)
--   hourly_30d   the 1h cascade: sum of app_stat_1m / service_stat_1m per hour against app_stat_1h
--                and service_stat_1h (D-03 for the > 7 day dashboards of 6.1)
--   errors_30d   failed transactions with an error group against error_stat_1m (the fourth view)
--   nightly      13 section 6.3 (13) as the nightly job runs it (one day, HTTP)
-- Form: the sources are combined with UNION ALL (one src label per source) and grouped by the key;
-- sumIf(c, src = ...) is 0 for a key missing on one side, and every key column comes from its own
-- source row, so the result does not depend on join_use_nulls (a FULL JOIN USING chain of three or
-- more tables labels a key found only in the third table with default key values). The nightly
-- variant keeps the FULL JOIN form of 13 section 6.3 (13) and runs with join_use_nulls = 0 (file
-- setting): a minute missing on one side compares against 0.
-- Run as argos_migrator (the nightly job of 13 section 18 has no 10 s limit; 30 days at full scale
-- read about 1.3 billion xlog rows).
--
-- @id        Q12
-- @title     D-03 consistency: count(xlog) vs app/service/instance_stat_1m per minute, 30 days
-- @source    docs/13 §6.3 (13), §15 Q12; D-03; T-ST-030
-- @kind      consistency
-- @account   migrator
-- @criterion rows == 0
-- @settings  max_execution_time=1800, join_use_nulls=0

-- @probe check_range
SELECT toDateTime(greatest({first_day:Date}, {last_day:Date} - 29)) AS check_from,
       toDateTime({last_day:Date} + 1)                              AS check_to

-- @variant minute_30d
SELECT app_id, kind, m,
       sumIf(c, src = 'xlog')     AS xlog_count,
       sumIf(c, src = 'app')      AS app_1m,
       sumIf(c, src = 'service')  AS service_1m,
       sumIf(c, src = 'instance') AS instance_1m
FROM
(
    SELECT 'xlog' AS src, app_id, kind, toStartOfMinute(ts) AS m, count() AS c
    FROM {db}.{p}xlog
    WHERE ts >= {check_from:DateTime} AND ts < {check_to:DateTime}
    GROUP BY app_id, kind, m
    UNION ALL
    SELECT 'app', app_id, kind, minute, sum(count)
    FROM {db}.{p}app_stat_1m
    WHERE minute >= {check_from:DateTime} AND minute < {check_to:DateTime}
    GROUP BY app_id, kind, minute
    UNION ALL
    SELECT 'service', app_id, kind, minute, sum(count)
    FROM {db}.{p}service_stat_1m
    WHERE minute >= {check_from:DateTime} AND minute < {check_to:DateTime}
    GROUP BY app_id, kind, minute
    UNION ALL
    SELECT 'instance', app_id, kind, minute, sum(count)
    FROM {db}.{p}instance_stat_1m
    WHERE minute >= {check_from:DateTime} AND minute < {check_to:DateTime}
    GROUP BY app_id, kind, minute
)
GROUP BY app_id, kind, m
HAVING xlog_count != app_1m OR xlog_count != service_1m OR xlog_count != instance_1m
ORDER BY m, app_id, kind
LIMIT 1000

-- @variant hourly_30d
SELECT app_id, kind, h,
       sumIf(c, src = 'app_1m')     AS app_1m,
       sumIf(c, src = 'app_1h')     AS app_1h,
       sumIf(c, src = 'service_1m') AS service_1m,
       sumIf(c, src = 'service_1h') AS service_1h
FROM
(
    SELECT 'app_1m' AS src, app_id, kind, toStartOfHour(minute) AS h, sum(count) AS c
    FROM {db}.{p}app_stat_1m
    WHERE minute >= {check_from:DateTime} AND minute < {check_to:DateTime}
    GROUP BY app_id, kind, h
    UNION ALL
    SELECT 'app_1h', app_id, kind, hour, sum(count)
    FROM {db}.{p}app_stat_1h
    WHERE hour >= {check_from:DateTime} AND hour < {check_to:DateTime}
    GROUP BY app_id, kind, hour
    UNION ALL
    SELECT 'service_1m', app_id, kind, toStartOfHour(minute) AS h, sum(count)
    FROM {db}.{p}service_stat_1m
    WHERE minute >= {check_from:DateTime} AND minute < {check_to:DateTime}
    GROUP BY app_id, kind, h
    UNION ALL
    SELECT 'service_1h', app_id, kind, hour, sum(count)
    FROM {db}.{p}service_stat_1h
    WHERE hour >= {check_from:DateTime} AND hour < {check_to:DateTime}
    GROUP BY app_id, kind, hour
)
GROUP BY app_id, kind, h
HAVING app_1m != app_1h OR app_1m != service_1m OR app_1m != service_1h
ORDER BY h, app_id, kind
LIMIT 1000

-- @variant errors_30d
-- The fourth view on xlog: failed transactions with an error group against error_stat_1m.
SELECT app_id, kind, m, x.c AS xlog_errors, e.c AS error_1m
FROM
(
    SELECT app_id, kind, toStartOfMinute(ts) AS m, count() AS c
    FROM {db}.{p}xlog
    WHERE ts >= {check_from:DateTime} AND ts < {check_to:DateTime} AND error = 1 AND error_group_id != 0
    GROUP BY app_id, kind, m
) AS x
FULL OUTER JOIN
(
    SELECT app_id, kind, minute AS m, sum(count) AS c
    FROM {db}.{p}error_stat_1m
    WHERE minute >= {check_from:DateTime} AND minute < {check_to:DateTime}
    GROUP BY app_id, kind, m
) AS e USING (app_id, kind, m)
WHERE x.c != e.c
ORDER BY m, app_id, kind
LIMIT 1000

-- @variant nightly
SELECT m, a, s
FROM (SELECT toStartOfMinute(ts) AS m, count() AS c FROM {db}.{p}xlog
      WHERE toDate(ts) = {full_day:Date} AND kind = 'HTTP' GROUP BY m) AS x
FULL JOIN (SELECT minute AS m, sum(count) AS a FROM {db}.{p}app_stat_1m
           WHERE toDate(minute) = {full_day:Date} AND kind = 'HTTP' GROUP BY m) AS y USING m
FULL JOIN (SELECT minute AS m, sum(count) AS s FROM {db}.{p}service_stat_1m
           WHERE toDate(minute) = {full_day:Date} AND kind = 'HTTP' GROUP BY m) AS z USING m
WHERE x.c != y.a OR x.c != z.s

-- @step window_minute
-- The minute_30d check restricted to a time window (Q15, Q16 procedures).
SELECT app_id, kind, m,
       sumIf(c, src = 'xlog')     AS xlog_count,
       sumIf(c, src = 'app')      AS app_1m,
       sumIf(c, src = 'service')  AS service_1m,
       sumIf(c, src = 'instance') AS instance_1m
FROM
(
    SELECT 'xlog' AS src, app_id, kind, toStartOfMinute(ts) AS m, count() AS c
    FROM {db}.{p}xlog
    WHERE ts >= {window_from:DateTime} AND ts < {window_to:DateTime}
    GROUP BY app_id, kind, m
    UNION ALL
    SELECT 'app', app_id, kind, minute, sum(count)
    FROM {db}.{p}app_stat_1m
    WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime}
    GROUP BY app_id, kind, minute
    UNION ALL
    SELECT 'service', app_id, kind, minute, sum(count)
    FROM {db}.{p}service_stat_1m
    WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime}
    GROUP BY app_id, kind, minute
    UNION ALL
    SELECT 'instance', app_id, kind, minute, sum(count)
    FROM {db}.{p}instance_stat_1m
    WHERE minute >= {window_from:DateTime} AND minute < {window_to:DateTime}
    GROUP BY app_id, kind, minute
)
GROUP BY app_id, kind, m
HAVING xlog_count != app_1m OR xlog_count != service_1m OR xlog_count != instance_1m
ORDER BY m, app_id, kind
LIMIT 1000
