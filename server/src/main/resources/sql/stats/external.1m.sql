-- SPDX-License-Identifier: Apache-2.0
--
-- Outgoing call statistics (14 §4.8): minute fragment.
-- @tables ext_stat_1m
SELECT host_id,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(timeout_count) AS t,
       sum(connect_fail_count) AS f,
       sum(sum_ms) AS s,
       max(max_ms) AS mx,
       sumForEachMerge(buckets) AS bk
FROM {db}.{p}{rollup:ext_stat_1m}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all_hosts:UInt8} = 1 OR host_id IN {hosts:Array(UInt64)})
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY host_id
