-- SPDX-License-Identifier: Apache-2.0
--
-- One service's series: minute fragment.
-- @tables service_stat_1m
SELECT toStartOfInterval(minute, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}{rollup:service_stat_1m}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND service_id = {service:UInt64}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY b
