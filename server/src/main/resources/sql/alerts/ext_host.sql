-- SPDX-License-Identifier: Apache-2.0
--
-- Alert engine (12 §5.2 ClickHouseProvider, WO-25): outgoing calls to one host over a rule window
-- longer than the 15-minute memory windows, from the minute rollup (15 latency buckets, C-14).
-- @tables ext_stat_1m
SELECT sum(count) AS c,
       sum(error_count) AS e,
       sum(timeout_count) + sum(connect_fail_count) AS x,
       max(max_ms) AS m,
       sumForEachMerge(buckets) AS b
FROM {db}.{p}{rollup:ext_stat_1m}
WHERE app_id = {app:UInt32} AND host_id = {host:UInt64}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
