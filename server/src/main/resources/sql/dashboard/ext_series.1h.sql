-- SPDX-License-Identifier: Apache-2.0
--
-- Outgoing call errors of the dashboard: hour fragment (application level only, see the 1m one).
-- @tables ext_stat_1h
SELECT toStartOfInterval(hour, toIntervalSecond({step_seconds:UInt32})) AS b,
       sum(count) AS c,
       sum(error_count) AS e,
       sum(timeout_count) AS t,
       sum(connect_fail_count) AS f
FROM {db}.{p}{rollup:ext_stat_1h}
WHERE app_id = {app:UInt32} AND kind IN {kinds:Array(String)}
  AND hour >= {from:DateTime('UTC')} AND hour < {to:DateTime('UTC')}
GROUP BY b
