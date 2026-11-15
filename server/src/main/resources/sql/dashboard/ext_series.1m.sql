-- SPDX-License-Identifier: Apache-2.0
--
-- Outgoing call errors of the dashboard: minute fragment (no instance dimension in the rollup:
-- the dashboard reads it only without an instance filter).
-- @tables ext_stat_1m
SELECT toStartOfInterval(minute, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(timeout_count) AS t,
       sum(connect_fail_count) AS f
FROM {db}.{p}{rollup:ext_stat_1m}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}
GROUP BY b
