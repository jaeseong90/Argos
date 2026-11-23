-- SPDX-License-Identifier: Apache-2.0
--
-- One host's series: minute fragment.
-- @tables ext_stat_1m
SELECT toStartOfInterval(minute, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(timeout_count) AS t,
       sum(connect_fail_count) AS f,
       sum(sum_ms) AS s,
       sumForEachMerge(buckets) AS bk
FROM {db}.{p}{rollup:ext_stat_1m}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND host_id = {host:UInt64}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY b
