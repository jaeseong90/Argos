-- SPDX-License-Identifier: Apache-2.0
--
-- One service's series: hour fragment.
-- @tables service_stat_1h
SELECT toStartOfInterval(hour, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}{rollup:service_stat_1h}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)} AND service_id = {service:UInt64}
  AND hour >= {from:DateTime('UTC')} AND hour < {to:DateTime('UTC')}
GROUP BY b
