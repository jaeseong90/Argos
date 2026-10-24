-- SPDX-License-Identifier: Apache-2.0
--
-- Shared parameters of Q01..Q20 (docs/13 section 15, docs/18 section 9).
--
-- The runner resolves these probes once per run, as argos_reader, from the loaded data. Each probe
-- returns exactly one row; its column names become query parameters that the Qnn files bind with
-- the ClickHouse query parameter syntax {name:Type} (sent as param_<name>, never spliced into the
-- SQL text). Probes run in file order and may use parameters of earlier probes. A Qnn file may add
-- its own probes the same way. Textual placeholders ({db}, {p}, {retention.<key>}) are rendered from
-- the schema parameters before execution (13 section 2, DSN-13-11).
--
-- Probes read the 1m rollups where possible so that they stay cheap at full scale (1.3 billion
-- xlog rows): the dataset range and the busiest application come from app_stat_1m.

-- @probe range
-- end_minute is exclusive (the minute after the last minute with data); the time windows of the
-- queries end there. full_day is the last complete day (the day before the last day when the data
-- covers at least three days), the day used by the daily checks of 13 section 6.3 (13), (14).
SELECT
    min(minute)                                                  AS start_minute,
    max(minute) + INTERVAL 1 MINUTE                              AS end_minute,
    toDateTime64(max(minute) + INTERVAL 1 MINUTE, 3)             AS end_ts,
    toStartOfHour(max(minute)) + INTERVAL 1 HOUR                 AS end_hour,
    toDate(min(minute))                                          AS first_day,
    toDate(max(minute))                                          AS last_day,
    if(dateDiff('day', toDate(min(minute)), toDate(max(minute))) >= 2,
       toDate(max(minute)) - 1, toDate(max(minute)))             AS full_day,
    toUInt32(dateDiff('day', toDate(min(minute)), toDate(max(minute))) + 1) AS days
FROM {db}.{p}app_stat_1m

-- @probe app
-- The busiest application of the last day (the dashboard and search queries run on it).
SELECT app_id AS app
FROM {db}.{p}app_stat_1m
WHERE minute >= {end_minute:DateTime} - INTERVAL 1 DAY AND minute < {end_minute:DateTime}
GROUP BY app_id
ORDER BY sum(count) DESC, app_id
LIMIT 1
