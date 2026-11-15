-- SPDX-License-Identifier: Apache-2.0
--
-- One SQL statement's series: minute fragment.
-- @tables sql_stat_1m
SELECT toStartOfInterval(minute, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(sum_ms) AS s,
       max(max_ms) AS mx,
       sumForEachMerge(buckets) AS bk
FROM {db}.{p}{rollup:sql_stat_1m}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND sql_id = {sql:UInt64}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY b
