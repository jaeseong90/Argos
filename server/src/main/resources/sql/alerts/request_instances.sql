-- SPDX-License-Identifier: Apache-2.0
--
-- Alert engine (12 §5.2 ClickHouseProvider, WO-25): request metrics of an application or one
-- instance over a rule window longer than the 15-minute memory windows, from the minute rollup.
-- Instances in their warm-up grace are left out ({excluded}). quantilesDDMerge gives [p50, p95, p99].
-- @tables instance_stat_1m
SELECT sum(count) AS c,
       sum(error_count) AS e,
       sum(apdex_satisfied) AS s,
       sum(apdex_tolerating) AS t,
       quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}{rollup:instance_stat_1m}
WHERE app_id = {app:UInt32} AND kind = {kind:String}
  AND ({all:Int64} = 1 OR instance_id = {instance:UInt32})
  AND instance_id NOT IN {excluded:Array(UInt32)}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
