-- SPDX-License-Identifier: Apache-2.0
--
-- Service statistics (14 §4.8): minute fragment.
-- @tables service_stat_1m
SELECT service_id AS sv,
       if({split:UInt8} = 1, app_version_id, toUInt64(0)) AS v,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(sum_elapsed_ms) AS s,
       max(max_elapsed_ms) AS mx,
       sum(sum_sql_time_ms) AS sq,
       sum(sum_ext_time_ms) AS x,
       sum(apdex_satisfied) AS sat,
       sum(apdex_tolerating) AS tol,
       quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q
FROM {db}.{p}{rollup:service_stat_1m}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND ({all_services:UInt8} = 1 OR service_id IN {services:Array(UInt64)})
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY sv, v
