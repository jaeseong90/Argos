-- SPDX-License-Identifier: Apache-2.0
--
-- Alert engine (12 §5.2 ClickHouseProvider, WO-25): request metrics of one service over a rule
-- window longer than the 15-minute memory windows, from the minute rollup.
-- @tables service_stat_1m
SELECT sum(count) AS c,
       sum(error_count) AS e,
       sum(apdex_satisfied) AS s,
       sum(apdex_tolerating) AS t,
       quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}{rollup:service_stat_1m}
WHERE app_id = {app:UInt32} AND kind = {kind:String} AND service_id = {service:UInt64}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
