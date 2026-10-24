-- SPDX-License-Identifier: Apache-2.0
--
-- Q13 D-12 consistency: the SQL and outgoing call counts carried by the transactions
-- (sum(xlog.sql_count), sum(xlog.ext_count)) against the complete statistics
-- (sum(sql_stat_1m.count), sum(ext_stat_1m.count)), 13 section 15 Q13. One result row per
-- disagreeing group; the criterion is 0 rows.
--   total_30d      per (app, kind) over 30 days (T-ST-031)
--   daily_30d      per (app, kind, day): the granularity of the nightly check 13 section 6.3 (14).
--                  A statement executed after midnight by a transaction that started before it
--                  is counted on another day by a time-window aggregator; this variant shows
--                  whether the day granularity holds for the data at hand.
--   rollups_14d    sql_stat_raw / ext_stat_raw against their 1m and 1h rollups per (app, kind,
--                  day), within the 14 day raw retention
--   minute_raw_1m  sql_stat_raw / ext_stat_raw against sql_stat_1m / ext_stat_1m per (app, kind,
--                  minute) within the raw retention: catches a rollup that files counts under the
--                  wrong minute of the same day, which the per-day forms cannot see
--   nightly        13 section 6.3 (14) as the nightly job runs it (one day, all applications)
-- Form: the sources are combined with UNION ALL (one src label per source) and grouped by the key;
-- sumIf(c, src = ...) is 0 for a key missing on one side, independent of join_use_nulls (a FULL
-- JOIN USING chain of three or more tables labels a key found only in the third table with default
-- key values). join_use_nulls = 0 is still fixed in the file settings.
-- Run as argos_migrator (nightly job, no 10 s limit).
--
-- @id        Q13
-- @title     D-12 consistency: sum(xlog.sql_count/ext_count) vs sql/ext_stat_1m, 30 days
-- @source    docs/13 §6.3 (14), §15 Q13; D-12; T-ST-031
-- @kind      consistency
-- @account   migrator
-- @criterion rows == 0
-- @settings  max_execution_time=1800, join_use_nulls=0

-- @probe check_range
SELECT toDateTime(greatest({first_day:Date}, {last_day:Date} - 29)) AS check_from,
       toDateTime(greatest({first_day:Date}, {last_day:Date} - 13)) AS raw_from,
       toDateTime({last_day:Date} + 1)                              AS check_to

-- @variant total_30d
SELECT app_id, kind,
       sumIf(c, src = 'xlog_sql') AS xlog_sql, sumIf(c, src = 'sql_1m') AS sql_1m,
       sumIf(c, src = 'xlog_ext') AS xlog_ext, sumIf(c, src = 'ext_1m') AS ext_1m
FROM
(
    SELECT 'xlog_sql' AS src, app_id, kind, sum(sql_count) AS c
    FROM {db}.{p}xlog
    WHERE ts >= {check_from:DateTime} AND ts < {check_to:DateTime}
    GROUP BY app_id, kind
    UNION ALL
    SELECT 'xlog_ext', app_id, kind, sum(ext_count)
    FROM {db}.{p}xlog
    WHERE ts >= {check_from:DateTime} AND ts < {check_to:DateTime}
    GROUP BY app_id, kind
    UNION ALL
    SELECT 'sql_1m', app_id, kind, sum(count)
    FROM {db}.{p}sql_stat_1m
    WHERE minute >= {check_from:DateTime} AND minute < {check_to:DateTime}
    GROUP BY app_id, kind
    UNION ALL
    SELECT 'ext_1m', app_id, kind, sum(count)
    FROM {db}.{p}ext_stat_1m
    WHERE minute >= {check_from:DateTime} AND minute < {check_to:DateTime}
    GROUP BY app_id, kind
)
GROUP BY app_id, kind
HAVING xlog_sql != sql_1m OR xlog_ext != ext_1m
ORDER BY app_id, kind

-- @variant daily_30d
SELECT app_id, kind, d,
       sumIf(c, src = 'xlog_sql') AS xlog_sql, sumIf(c, src = 'sql_1m') AS sql_1m,
       sumIf(c, src = 'xlog_ext') AS xlog_ext, sumIf(c, src = 'ext_1m') AS ext_1m
FROM
(
    SELECT 'xlog_sql' AS src, app_id, kind, toDate(ts) AS d, sum(sql_count) AS c
    FROM {db}.{p}xlog
    WHERE ts >= {check_from:DateTime} AND ts < {check_to:DateTime}
    GROUP BY app_id, kind, d
    UNION ALL
    SELECT 'xlog_ext', app_id, kind, toDate(ts) AS d, sum(ext_count)
    FROM {db}.{p}xlog
    WHERE ts >= {check_from:DateTime} AND ts < {check_to:DateTime}
    GROUP BY app_id, kind, d
    UNION ALL
    SELECT 'sql_1m', app_id, kind, toDate(minute) AS d, sum(count)
    FROM {db}.{p}sql_stat_1m
    WHERE minute >= {check_from:DateTime} AND minute < {check_to:DateTime}
    GROUP BY app_id, kind, d
    UNION ALL
    SELECT 'ext_1m', app_id, kind, toDate(minute) AS d, sum(count)
    FROM {db}.{p}ext_stat_1m
    WHERE minute >= {check_from:DateTime} AND minute < {check_to:DateTime}
    GROUP BY app_id, kind, d
)
GROUP BY app_id, kind, d
HAVING xlog_sql != sql_1m OR xlog_ext != ext_1m
ORDER BY d, app_id, kind
LIMIT 1000

-- @variant rollups_14d
SELECT app_id, kind, d,
       sumIf(c, src = 'sql_raw') AS sql_raw, sumIf(c, src = 'sql_1m') AS sql_1m, sumIf(c, src = 'sql_1h') AS sql_1h,
       sumIf(c, src = 'ext_raw') AS ext_raw, sumIf(c, src = 'ext_1m') AS ext_1m, sumIf(c, src = 'ext_1h') AS ext_1h
FROM
(
    SELECT 'sql_raw' AS src, app_id, kind, toDate(ts) AS d, sum(count) AS c FROM {db}.{p}sql_stat_raw
    WHERE ts >= {raw_from:DateTime} AND ts < {check_to:DateTime} GROUP BY app_id, kind, d
    UNION ALL
    SELECT 'sql_1m', app_id, kind, toDate(minute) AS d, sum(count) FROM {db}.{p}sql_stat_1m
    WHERE minute >= {raw_from:DateTime} AND minute < {check_to:DateTime} GROUP BY app_id, kind, d
    UNION ALL
    SELECT 'sql_1h', app_id, kind, toDate(hour) AS d, sum(count) FROM {db}.{p}sql_stat_1h
    WHERE hour >= {raw_from:DateTime} AND hour < {check_to:DateTime} GROUP BY app_id, kind, d
    UNION ALL
    SELECT 'ext_raw', app_id, kind, toDate(ts) AS d, sum(count) FROM {db}.{p}ext_stat_raw
    WHERE ts >= {raw_from:DateTime} AND ts < {check_to:DateTime} GROUP BY app_id, kind, d
    UNION ALL
    SELECT 'ext_1m', app_id, kind, toDate(minute) AS d, sum(count) FROM {db}.{p}ext_stat_1m
    WHERE minute >= {raw_from:DateTime} AND minute < {check_to:DateTime} GROUP BY app_id, kind, d
    UNION ALL
    SELECT 'ext_1h', app_id, kind, toDate(hour) AS d, sum(count) FROM {db}.{p}ext_stat_1h
    WHERE hour >= {raw_from:DateTime} AND hour < {check_to:DateTime} GROUP BY app_id, kind, d
)
GROUP BY app_id, kind, d
HAVING sql_raw != sql_1m OR sql_raw != sql_1h OR ext_raw != ext_1m OR ext_raw != ext_1h
ORDER BY d, app_id, kind
LIMIT 1000

-- @variant minute_raw_1m
SELECT app_id, kind, m,
       sumIf(c, src = 'sql_raw') AS sql_raw, sumIf(c, src = 'sql_1m') AS sql_1m,
       sumIf(c, src = 'ext_raw') AS ext_raw, sumIf(c, src = 'ext_1m') AS ext_1m
FROM
(
    SELECT 'sql_raw' AS src, app_id, kind, toStartOfMinute(ts) AS m, sum(count) AS c FROM {db}.{p}sql_stat_raw
    WHERE ts >= {raw_from:DateTime} AND ts < {check_to:DateTime} GROUP BY app_id, kind, m
    UNION ALL
    SELECT 'sql_1m', app_id, kind, minute, sum(count) FROM {db}.{p}sql_stat_1m
    WHERE minute >= {raw_from:DateTime} AND minute < {check_to:DateTime} GROUP BY app_id, kind, minute
    UNION ALL
    SELECT 'ext_raw', app_id, kind, toStartOfMinute(ts) AS m, sum(count) FROM {db}.{p}ext_stat_raw
    WHERE ts >= {raw_from:DateTime} AND ts < {check_to:DateTime} GROUP BY app_id, kind, m
    UNION ALL
    SELECT 'ext_1m', app_id, kind, minute, sum(count) FROM {db}.{p}ext_stat_1m
    WHERE minute >= {raw_from:DateTime} AND minute < {check_to:DateTime} GROUP BY app_id, kind, minute
)
GROUP BY app_id, kind, m
HAVING sql_raw != sql_1m OR ext_raw != ext_1m
ORDER BY m, app_id, kind
LIMIT 1000

-- @variant nightly
SELECT sum(sql_count) AS x,
       (SELECT sum(count) FROM {db}.{p}sql_stat_1m WHERE toDate(minute) = {full_day:Date}) AS s
FROM {db}.{p}xlog
WHERE toDate(ts) = {full_day:Date}
HAVING x != s
